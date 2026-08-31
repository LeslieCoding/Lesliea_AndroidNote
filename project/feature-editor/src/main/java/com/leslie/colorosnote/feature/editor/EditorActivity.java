package com.leslie.colorosnote.feature.editor;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

import com.leslie.colorosnote.core.common.AppExecutors;
import com.leslie.colorosnote.core.model.InknoteContainer;
import com.leslie.colorosnote.feature.editor.databinding.ActivityEditorBinding;
import com.leslie.colorosnote.feature.editor.databinding.ItemEditorTabBinding;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Multi-document editor host (spec 28.5): a tab strip on top, one
 * {@link EditorFragment} per open document kept alive via show/hide so
 * switching tabs never loses editing state. Open tabs persist across sessions
 * ("记住最近打开文档").
 *
 * <p>Launch with {@link #createIntent(Context, String)}.
 */
public final class EditorActivity extends AppCompatActivity
        implements EditorFragment.HostActivity, ObjectToolController.ImageInsertHost {

    public static final String EXTRA_DOCUMENT_ID =
            "com.leslie.colorosnote.feature.editor.EXTRA_DOCUMENT_ID";
    public static final String EXTRA_PAGE_ID =
            "com.leslie.colorosnote.feature.editor.EXTRA_PAGE_ID";
    public static final String EXTRA_HIT_BOUNDS =
            "com.leslie.colorosnote.feature.editor.EXTRA_HIT_BOUNDS";

    private static final String PREFS = "editor_session";
    private static final String KEY_OPEN_TABS = "open_tabs";
    private static final String KEY_ACTIVE_TAB = "active_tab";
    /** Decode ceiling for inserted images (spec 18.1: preview is downsampled). */
    private static final int IMAGE_MAX_DIM_PX = 2048;

    public static Intent createIntent(Context context, String documentId) {
        return new Intent(context, EditorActivity.class)
                .putExtra(EXTRA_DOCUMENT_ID, documentId);
    }

    /** Opens a document and jumps to a search hit (page + bounding box). */
    public static Intent createIntent(Context context, String documentId,
                                      String pageId, float[] hitBounds) {
        return createIntent(context, documentId)
                .putExtra(EXTRA_PAGE_ID, pageId)
                .putExtra(EXTRA_HIT_BOUNDS, hitBounds);
    }

    /** Pending search-hit jump read from the launch intent. */
    @Nullable
    private String pendingHitPageId;
    @Nullable
    private float[] pendingHitBounds;

    private ActivityEditorBinding binding;
    private final List<String> tabIds = new ArrayList<>();
    private String activeId;

    @Nullable
    private ObjectToolController.ImagePickCallback pendingImagePick;

    private final ActivityResultLauncher<String> imagePicker =
            registerForActivityResult(new ActivityResultContracts.GetContent(),
                    this::onImagePicked);

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityEditorBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        if (savedInstanceState == null) {
            restoreSession();
        } else {
            // Fragments were restored by the FragmentManager; rebuild tab model.
            for (Fragment f : getSupportFragmentManager().getFragments()) {
                if (f instanceof EditorFragment) {
                    String id = ((EditorFragment) f).getDocumentId();
                    if (!tabIds.contains(id)) {
                        tabIds.add(id);
                    }
                }
            }
            if (tabIds.isEmpty()) {
                restoreSession();
            }
        }

        String intentDoc = getIntent().getStringExtra(EXTRA_DOCUMENT_ID);
        pendingHitPageId = getIntent().getStringExtra(EXTRA_PAGE_ID);
        pendingHitBounds = getIntent().getFloatArrayExtra(EXTRA_HIT_BOUNDS);
        if (intentDoc != null && !intentDoc.isEmpty()) {
            openDocumentTab(intentDoc);
        } else if (!tabIds.isEmpty()) {
            activateTab(tabIds.contains(savedActiveId()) ? savedActiveId() : tabIds.get(0));
        } else {
            // Nothing to edit: no document id and no restorable session.
            finish();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String docId = intent.getStringExtra(EXTRA_DOCUMENT_ID);
        pendingHitPageId = intent.getStringExtra(EXTRA_PAGE_ID);
        pendingHitBounds = intent.getFloatArrayExtra(EXTRA_HIT_BOUNDS);
        if (docId != null && !docId.isEmpty()) {
            openDocumentTab(docId);
        }
    }

    @Override
    protected void onPause() {
        for (Fragment f : getSupportFragmentManager().getFragments()) {
            if (f instanceof EditorFragment) {
                ((EditorFragment) f).flushSave();
            }
        }
        persistSession();
        super.onPause();
    }

    // ------------------------------------------------------------------
    // Tabs
    // ------------------------------------------------------------------

    /** Opens (or focuses) the tab for a document. */
    public void openDocumentTab(String documentId) {
        if (!tabIds.contains(documentId)) {
            tabIds.add(documentId);
        }
        activateTab(documentId);
        persistSession();
    }

    private void activateTab(String documentId) {
        activeId = documentId;
        FragmentTransaction tx = getSupportFragmentManager().beginTransaction();
        for (Fragment f : getSupportFragmentManager().getFragments()) {
            if (f instanceof EditorFragment) {
                tx.hide(f);
            }
        }
        EditorFragment fragment = findEditorFragment(documentId);
        if (fragment == null) {
            fragment = EditorFragment.newInstance(documentId);
            tx.add(R.id.fragment_container, fragment, tagFor(documentId));
        } else {
            tx.show(fragment);
        }
        tx.commitNowAllowingStateLoss();
        renderTabs();
        // Deliver a pending search-hit jump (deferred inside the fragment
        // until its document has loaded).
        if (pendingHitPageId != null || pendingHitBounds != null) {
            EditorFragment active = findEditorFragment(documentId);
            if (active != null) {
                active.jumpToHit(pendingHitPageId, pendingHitBounds);
            }
            pendingHitPageId = null;
            pendingHitBounds = null;
        }
    }

    private void closeTab(String documentId) {
        EditorFragment fragment = findEditorFragment(documentId);
        if (fragment != null) {
            fragment.flushSave();
            getSupportFragmentManager().beginTransaction()
                    .remove(fragment)
                    .commitNowAllowingStateLoss();
        }
        int index = tabIds.indexOf(documentId);
        tabIds.remove(documentId);
        persistSession();
        if (tabIds.isEmpty()) {
            finish();
            return;
        }
        int next = Math.min(Math.max(index, 0), tabIds.size() - 1);
        activateTab(tabIds.get(next));
    }

    private void renderTabs() {
        binding.tabContainer.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        for (String id : tabIds) {
            ItemEditorTabBinding tab = ItemEditorTabBinding.inflate(
                    inflater, binding.tabContainer, false);
            EditorFragment fragment = findEditorFragment(id);
            tab.tabTitle.setText(fragment != null
                    ? fragment.currentTitle()
                    : getString(R.string.editor_title_hint));
            tab.getRoot().setSelected(id.equals(activeId));
            tab.getRoot().setOnClickListener(v -> activateTab(id));
            tab.tabClose.setOnClickListener(v -> closeTab(id));
            binding.tabContainer.addView(tab.getRoot());
        }
    }

    @Override
    public void onDocumentTitleChanged(String documentId, String title) {
        renderTabs();
    }

    // ------------------------------------------------------------------
    // Image insertion host (spec 18.1: gallery pick delegated by the editor)
    // ------------------------------------------------------------------

    @Override
    public void requestImagePick(ObjectToolController.ImagePickCallback callback) {
        pendingImagePick = callback;
        imagePicker.launch("image/*");
    }

    private void onImagePicked(@Nullable Uri uri) {
        ObjectToolController.ImagePickCallback callback = pendingImagePick;
        pendingImagePick = null;
        if (callback == null) {
            return;
        }
        if (uri == null) {
            callback.onImagePickCancelled();
            return;
        }
        Bitmap bitmap = decodeDownsampled(uri);
        if (bitmap == null) {
            Toast.makeText(this, R.string.obj_image_decode_failed, Toast.LENGTH_SHORT).show();
            callback.onImagePickCancelled();
            return;
        }
        String resourceId = EditorBitmapRegistry.put(bitmap);
        persistImageResource(resourceId, bitmap);
        callback.onImagePicked(resourceId, bitmap.getWidth(), bitmap.getHeight());
    }

    /**
     * Stages the inserted image's PNG bytes into the document container
     * ({@code resources/<resourceId>}); DocumentRepository merges them on the
     * next (debounced) full save. Compression runs off the main thread.
     */
    private void persistImageResource(String resourceId, Bitmap bitmap) {
        if (activeId == null) {
            return;
        }
        String documentId = activeId;
        AppExecutors.get().io().execute(() -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            EditorServices.get(this).repository().putResource(documentId,
                    InknoteContainer.resourcePath(resourceId), out.toByteArray());
        });
    }

    /** Two-pass decode with inSampleSize so huge photos stay within memory. */
    @Nullable
    private Bitmap decodeDownsampled(Uri uri) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return null;
            }
            BitmapFactory.Options decode = new BitmapFactory.Options();
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= IMAGE_MAX_DIM_PX
                    || bounds.outHeight / (sample * 2) >= IMAGE_MAX_DIM_PX) {
                sample *= 2;
            }
            decode.inSampleSize = sample;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                return BitmapFactory.decodeStream(in, null, decode);
            }
        } catch (Exception e) {
            return null;
        }
    }

    @Nullable
    private EditorFragment findEditorFragment(String documentId) {
        Fragment f = getSupportFragmentManager().findFragmentByTag(tagFor(documentId));
        return f instanceof EditorFragment ? (EditorFragment) f : null;
    }

    private static String tagFor(String documentId) {
        return "doc_" + documentId;
    }

    // ------------------------------------------------------------------
    // Session persistence
    // ------------------------------------------------------------------

    private SharedPreferences sessionPrefs() {
        return getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private void restoreSession() {
        String saved = sessionPrefs().getString(KEY_OPEN_TABS, null);
        if (saved != null && !saved.isEmpty()) {
            tabIds.addAll(Arrays.asList(saved.split(",")));
        }
    }

    @Nullable
    private String savedActiveId() {
        return sessionPrefs().getString(KEY_ACTIVE_TAB, null);
    }

    private void persistSession() {
        StringBuilder joined = new StringBuilder();
        for (String id : tabIds) {
            if (joined.length() > 0) {
                joined.append(',');
            }
            joined.append(id);
        }
        sessionPrefs().edit()
                .putString(KEY_OPEN_TABS, joined.toString())
                .putString(KEY_ACTIVE_TAB, activeId)
                .apply();
    }
}
