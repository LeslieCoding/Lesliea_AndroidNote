package com.leslie.colorosnote.feature.editor;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.SubMenu;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.PopupWindow;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.leslie.colorosnote.core.common.AppExecutors;
import com.leslie.colorosnote.core.ink.Command;
import com.leslie.colorosnote.core.ink.EraserEngine;
import com.leslie.colorosnote.core.ink.InkCommands;
import com.leslie.colorosnote.core.ink.LassoEngine;
import com.leslie.colorosnote.core.ink.PalmRejectionPolicy;
import com.leslie.colorosnote.core.ink.UndoRedoStack;
import com.leslie.colorosnote.core.model.BrushPreset;
import com.leslie.colorosnote.core.model.InknoteContainer;
import com.leslie.colorosnote.core.model.NoteDocument;
import com.leslie.colorosnote.core.model.Page;
import com.leslie.colorosnote.core.model.PageBackground;
import com.leslie.colorosnote.core.model.Stroke;
import com.leslie.colorosnote.core.render.ImagePainter;
import com.leslie.colorosnote.core.render.OfficeBackgroundProvider;
import com.leslie.colorosnote.core.render.PageBackgroundProvider;
import com.leslie.colorosnote.core.render.PdfBackgroundProvider;
import com.leslie.colorosnote.core.render.StickerPainter;
import com.leslie.colorosnote.core.render.TapePainter;
import com.leslie.colorosnote.core.render.TemplatePickerDialog;
import com.leslie.colorosnote.core.render.ViewportTransform;
import com.leslie.colorosnote.core.storage.db.dao.IndexDao;
import com.leslie.colorosnote.core.storage.journal.JournalStore;
import com.leslie.colorosnote.document.office.ImportedDocument;
import com.leslie.colorosnote.document.office.ImportedDocumentJson;
import com.leslie.colorosnote.document.pdf.AndroidPdfEngineFactory;
import com.leslie.colorosnote.document.pdf.PdfEngine;
import com.leslie.colorosnote.feature.editor.databinding.FragmentEditorBinding;
import com.leslie.colorosnote.feature.editor.databinding.PopupAuxToolsBinding;
import com.leslie.colorosnote.feature.editor.databinding.PopupLassoActionsBinding;
import com.leslie.colorosnote.feature.editor.databinding.PopupMoreMenuBinding;
import com.leslie.colorosnote.feature.search.SearchQueryBuilder;
import com.leslie.colorosnote.feature.search.SearchResultAdapter;
import com.leslie.colorosnote.feature.search.SearchResultItem;
import com.leslie.colorosnote.feature.search.SearchResultMerger;
import com.leslie.colorosnote.feature.settings.EditorSettings;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-document editor screen (spec 26/28.4/29/30). Hosts the ink surface, the
 * unified slim toolbar (navigation + pen tools + insert popup), the page system
 * (single / continuous-scroll) with thumbnail navigation, undo/redo, autosave
 * (journal per stroke + debounced full save) and the recording tool.
 *
 * <p>Writing path keeps zero IO / zero allocation hotspots: strokes finish on
 * the InkView thread, journal append is async on the IO executor, undo commands
 * are main-thread lightweight.
 */
public final class EditorFragment extends Fragment
        implements EditorGestureController.Host, SelectionController.Listener,
        RecordingController.Ui {

    private static final String ARG_DOCUMENT_ID = "document_id";
    private static final long AUTOSAVE_DEBOUNCE_MS = 2000L;
    private static final long CONTENT_REFRESH_MS = 60L;
    private static final long HINT_SHOW_MS = 3000L;

    // Container extras ids of imported-document payloads; must match the
    // ImportCoordinator constants (feature-import keeps them package-private).
    private static final String OFFICE_LAYOUT_RESOURCE_ID = "office.json";
    private static final String PDF_RESOURCE_ID = "source.pdf";

    // Template submenu ids used by the page-thumbnail context menu.
    private static final int MENU_TMPL_BASE = 100;

    /** Tap-to-select hit slop in screen px (matches ObjectToolController). */
    private static final float TAP_HIT_SLOP_PX = 20f;

    /** Implemented by EditorActivity to keep tab labels in sync. */
    public interface HostActivity {
        void onDocumentTitleChanged(String documentId, String title);
    }

    public static EditorFragment newInstance(String documentId) {
        EditorFragment f = new EditorFragment();
        Bundle args = new Bundle();
        args.putString(ARG_DOCUMENT_ID, documentId);
        f.setArguments(args);
        return f;
    }

    private FragmentEditorBinding binding;
    private EditorServices services;
    private EditorSettings settings;
    private BrushPresetStore presetStore;

    private String documentId;
    private NoteDocument document;

    private final UndoRedoStack undoStack = new UndoRedoStack();
    /**
     * Mirrors every undoable stroke edit into the crash journal (requirement
     * 10): applies append, removals append a tombstone, so a crash between an
     * undo/erase and the next full save can no longer resurrect the stroke on
     * recovery.
     */
    private final UndoRedoStack.Listener journalMirrorListener = new UndoRedoStack.Listener() {
        @Override
        public void onApplied(Command command) {
            mirrorToJournal(command, true);
        }

        @Override
        public void onUndone(Command command) {
            mirrorToJournal(command, false);
        }
    };
    private PageController pageController;
    private final SelectionController selectionController = new SelectionController();
    private final EraserEngine eraserEngine = new EraserEngine();
    private final LassoEngine lassoEngine = new LassoEngine();

    private EditorTool currentTool = EditorTool.FOUNTAIN_PEN;
    private EditorTool toolBeforeTemporary;
    private final Map<EditorTool, BrushPreset> presets = new EnumMap<>(EditorTool.class);
    private EraserConfig eraserConfig;
    private EditorGestureController gestureController;

    private PageThumbnailAdapter thumbnailAdapter;
    private PageContinuousAdapter continuousAdapter;
    private boolean continuousMode;
    private int currentPageIndex;
    private boolean needsFit = true;
    /** Viewport scale right after fitPage; the fit-page button shows above it. */
    private float fitPageScale = 1f;

    private RecordingController recordingController;
    private AuxTool.AuxToolListener auxToolListener;
    private ObjectToolController objectToolController;

    /** Floating recording control bar state (driven by RecordingController.Ui). */
    private final RecordingBarState recordingBar = new RecordingBarState(new RecordingBarState.Ui() {
        @Override
        public void showBar() {
            if (binding != null) {
                binding.recordingBar.setVisibility(View.VISIBLE);
            }
        }

        @Override
        public void hideBar() {
            if (binding != null) {
                binding.recordingBar.setVisibility(View.GONE);
            }
        }

        @Override
        public void showElapsed(String formatted) {
            if (binding != null) {
                binding.recordingTime.setText(
                        recordingBar.isPaused() ? formatted : "● " + formatted);
            }
        }

        @Override
        public void showPaused(boolean paused) {
            if (binding == null) {
                return;
            }
            binding.btnRecordingPause.setText(
                    paused ? R.string.recording_resume : R.string.recording_pause);
            binding.recordingTime.setTextColor(getResources().getColor(
                    paused ? R.color.text_secondary : R.color.recording_red, null));
            String text = binding.recordingTime.getText().toString();
            binding.recordingTime.setText(
                    paused ? text.replace("● ", "") : "● " + text.replace("● ", ""));
        }
    });
    /** Currently open recording dialog (stopped/list); dismissed on destroy. */
    private AlertDialog recordingDialog;

    /** In-document search panel state. */
    private SearchResultAdapter searchAdapter;
    private final AtomicLong searchGeneration = new AtomicLong();
    private Future<?> pendingSearch;
    private Runnable searchDebounce;
    private String currentSearchQuery = "";

    /** Imported-document (PDF/OFFICE) page background; null for plain notes. */
    private PageBackgroundProvider backgroundProvider;
    /** Engine owned by the PDF background provider; closed on destroy. */
    private PdfEngine pdfEngine;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean dirty;
    private List<Stroke> eraseBefore;
    private boolean eraseChanged;
    /** Page being erased in continuous mode (long-press shortcut). */
    private Page eraseTargetPage;
    private PopupWindow toolPopup;
    private PopupWindow selectionPopup;
    private boolean recordingActive;

    private final Runnable saveRunnable = this::saveNow;
    private final Runnable contentRefreshRunnable = () -> {
        if (binding != null && !continuousMode) {
            binding.inkView.refreshCompletedLayer();
        }
    };
    private final Runnable hideHintRunnable = () -> {
        if (binding != null) {
            binding.pressureHint.setVisibility(View.GONE);
        }
    };

    private final ActivityResultLauncher<String> micPermission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(),
                    granted -> {
                        if (recordingController != null) {
                            recordingController.onPermissionResult(granted);
                        }
                    });

    /** Callback interface for the speech-to-text mic permission request. */
    public interface SpeechMicPermissionCallback {
        void onSpeechMicPermissionResult(boolean granted);
    }

    /**
     * Separate launcher for the 语音转文字 flow: reusing {@link #micPermission}
     * would auto-start a recording on grant (RecordingController.onPermissionResult).
     */
    private SpeechMicPermissionCallback speechMicPermissionCallback;
    private final ActivityResultLauncher<String> speechMicPermission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(),
                    granted -> {
                        SpeechMicPermissionCallback cb = speechMicPermissionCallback;
                        speechMicPermissionCallback = null;
                        if (cb != null) {
                            cb.onSpeechMicPermissionResult(granted);
                        }
                    });

    /** Requests RECORD_AUDIO for the speech-to-text dialog. */
    public void requestSpeechMicPermission(SpeechMicPermissionCallback callback) {
        speechMicPermissionCallback = callback;
        speechMicPermission.launch(Manifest.permission.RECORD_AUDIO);
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        documentId = requireArguments().getString(ARG_DOCUMENT_ID);
        services = EditorServices.get(requireContext());
        settings = services.settings().loadEditorSettings();
        presetStore = new BrushPresetStore(requireContext());
        eraserConfig = presetStore.loadEraserConfig();
        for (EditorTool tool : EditorTool.values()) {
            if (tool.isPen()) {
                presets.put(tool, presetStore.presetFor(tool, settings.globalPressureCurve));
            }
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentEditorBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        gestureController = new EditorGestureController(requireContext(), this,
                binding.inkView, binding.overlayView);
        gestureController.attach();
        binding.inkView.setStrokeListener(
                stroke -> onStrokeFinishedShared(binding.inkView.getPage(), stroke));
        selectionController.setListener(this);

        // Object tools (spec 18/19): painters + the armed-tool controller.
        binding.inkView.getRenderer().setResourceBitmapProvider(EditorBitmapRegistry::get);
        binding.inkView.getRenderer().addPainter(new ImagePainter(EditorBitmapRegistry::get));
        binding.inkView.getRenderer().addPainter(new StickerPainter(EditorBitmapRegistry::get));
        binding.inkView.getRenderer().addPainter(new TapePainter());
        objectToolController = new ObjectToolController(this);
        setAuxToolListener(objectToolController);
        gestureController.setTouchInterceptor(objectToolController);

        wireTopBar();
        wireCoreToolbar();
        wireRecordingBar();
        wireThumbnailBar();
        setupSearchPanel();
        applyToolbarSide();
        applyInputPolicy();
        registerDebugZoomHook();
        undoStack.setListener(journalMirrorListener);
        loadDocument();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (recordingController != null) {
            recordingController.onHostResume();
        }
    }

    @Override
    public void onPause() {
        saveNow();
        if (recordingController != null) {
            recordingController.onHostPause();
        }
        super.onPause();
    }

    @Override
    public void onDestroyView() {
        saveNow();
        dismissPopups();
        dismissRecordingDialog();
        cancelPendingSearch();
        if (searchDebounce != null) {
            AppExecutors.get().mainThread().removeCallbacks(searchDebounce);
            searchDebounce = null;
        }
        releaseDocumentBackground();
        if (objectToolController != null) {
            objectToolController.dispose();
            objectToolController = null;
        }
        handler.removeCallbacksAndMessages(null);
        gestureController.detach();
        if (debugZoomReceiver != null) {
            requireContext().unregisterReceiver(debugZoomReceiver);
            debugZoomReceiver = null;
        }
        binding.inkView.release();
        if (continuousAdapter != null) {
            continuousAdapter.releaseAll();
            continuousAdapter = null;
        }
        if (recordingController != null) {
            recordingController.release();
            recordingController = null;
        }
        binding = null;
        super.onDestroyView();
    }

    // ------------------------------------------------------------------
    // Public API (host activity / follow-up agents)
    // ------------------------------------------------------------------

    public String getDocumentId() {
        return documentId;
    }

    public String currentTitle() {
        if (document == null || document.title == null || document.title.isEmpty()) {
            return getString(R.string.editor_title_hint);
        }
        return document.title;
    }

    /** Flushes pending changes (debounce bypass); called on pause/tab close. */
    public void flushSave() {
        saveNow();
    }

    /**
     * Registers the object-tool implementation (next agent): receives
     * IMAGE/TEXT_BOX/SHAPE/TAPE/STICKER/OCR/HANDWRITING_TO_TEXT/SPEECH_TO_TEXT
     * selections from the auxiliary toolbar. RECORDING and PAGE_TOOLS are
     * handled internally before the listener is consulted.
     */
    public void setAuxToolListener(AuxTool.AuxToolListener listener) {
        auxToolListener = listener;
    }

    /** The document's shared undo stack (object tools push commands here). */
    public UndoRedoStack getUndoStack() {
        return undoStack;
    }

    /** Currently edited page, or null before the document loads. */
    @Nullable
    public Page getCurrentPage() {
        return currentPage();
    }

    /** Forces a completed-layer re-render after external document mutation. */
    public void refreshContent() {
        refreshInkAll();
        refreshThumbnails();
    }

    /**
     * Object-tool entry point: re-render, autosave-mark and refresh the
     * undo/redo buttons after an object mutation pushed by
     * {@link ObjectToolController}.
     */
    public void notifyObjectToolEdited() {
        refreshInkNow();
        refreshThumbnails();
        onContentEdited();
    }

    /**
     * Lightweight repaint for in-progress object drags (spec 18.2): objects
     * are painted live in {@code InkView.onDraw} on top of the completed
     * stroke bitmap, so a plain invalidate moves the dragged text box within
     * the next frame. The debounced {@link #scheduleContentRefresh()} path
     * re-renders the whole stroke bitmap off-thread and starves under a
     * continuous MOVE stream (each event re-posts it), so the text stayed
     * put until commit. The heavy refresh + dirty marking still happens once
     * on gesture commit via {@link #notifyObjectToolEdited()}.
     */
    public void scheduleObjectToolRefresh() {
        if (binding != null && !continuousMode) {
            binding.inkView.invalidate();
        }
    }

    /** The selection/lasso overlay above the ink view (object previews). */
    @Nullable
    public EditorOverlayView overlayView() {
        return binding != null ? binding.overlayView : null;
    }

    // ------------------------------------------------------------------
    // Document loading / autosave (spec 10)
    // ------------------------------------------------------------------

    private void loadDocument() {
        services.repository().openDocumentAsync(documentId, result ->
                AppExecutors.get().mainThread().post(() -> {
                    if (binding == null) {
                        return;
                    }
                    if (result.isSuccess() && result.getOrNull() != null) {
                        onDocumentLoaded(result.getOrNull());
                    } else {
                        toast(getString(R.string.editor_load_failed));
                    }
                }));
    }

    private void onDocumentLoaded(NoteDocument doc) {
        document = doc;
        pageController = new PageController(doc, undoStack);
        recordingController = new RecordingController(requireContext(),
                services.audioDirFor(documentId), "rec", this);
        // No-op today (a fresh controller is idle); keeps the bar correct if the
        // controller ever outlives the view, e.g. across a config change.
        syncRecordingBar();
        notifyTitleChanged();

        thumbnailAdapter = new PageThumbnailAdapter(doc.pages, services.thumbnails(),
                getResources().getDimensionPixelSize(R.dimen.editor_thumb_width),
                new PageThumbnailAdapter.Listener() {
                    @Override
                    public void onPageClick(int index) {
                        goToPage(index);
                    }

                    @Override
                    public void onPageLongClick(int index, View anchor) {
                        showPageContextMenu(index, anchor);
                    }
                });
        binding.thumbList.setAdapter(thumbnailAdapter);

        goToPage(0);
        updateUndoRedoState();
        applyCurrentBrush();
        loadImageResources();
        loadDocumentBackground(doc);
        // A search result may have asked for a specific page before the
        // document finished loading.
        if (pendingJumpPageId != null) {
            String pageId = pendingJumpPageId;
            float[] bounds = pendingJumpBounds;
            pendingJumpPageId = null;
            pendingJumpBounds = null;
            jumpToHit(pageId, bounds);
        }
    }

    // ------------------------------------------------------------------
    // Search-hit navigation (spec 22.3)
    // ------------------------------------------------------------------

    @Nullable
    private String pendingJumpPageId;
    @Nullable
    private float[] pendingJumpBounds;

    /**
     * Jumps to the page containing a search hit and flashes its bounding box.
     * Safe to call before the document has loaded — the jump is deferred.
     */
    public void jumpToHit(@Nullable String pageId, @Nullable float[] pageBounds) {
        if (document == null || binding == null) {
            pendingJumpPageId = pageId;
            pendingJumpBounds = pageBounds;
            return;
        }
        if (pageId != null) {
            for (int i = 0; i < document.pages.size(); i++) {
                if (pageId.equals(document.pages.get(i).id)) {
                    goToPage(i);
                    break;
                }
            }
        }
        if (pageBounds != null && pageBounds.length == 4 && !continuousMode) {
            binding.inkView.post(() -> {
                if (binding == null) {
                    return;
                }
                RectF screen = binding.inkView.getViewport().pageRectToScreen(
                        pageBounds[0], pageBounds[1], pageBounds[2], pageBounds[3]);
                binding.overlayView.flashRect(screen);
            });
        }
    }

    /**
     * PDF/OFFICE imports: rebuilds the page-background provider from the
     * container extras on the IO thread, then wires it into every
     * {@code PageRenderer} (main InkView, continuous-scroll views, thumbnails)
     * on the main thread. Plain notes skip this entirely.
     */
    private void loadDocumentBackground(NoteDocument doc) {
        final NoteDocument.DocType docType = doc.docType;
        if (docType != NoteDocument.DocType.OFFICE_IMPORT
                && docType != NoteDocument.DocType.PDF_IMPORT) {
            return;
        }
        final File cacheDir = requireContext().getCacheDir();
        AppExecutors.get().io().execute(() -> {
            PageBackgroundProvider provider = null;
            PdfEngine engine = null;
            try {
                Map<String, byte[]> extras = services.repository().readExtras(documentId);
                if (docType == NoteDocument.DocType.OFFICE_IMPORT) {
                    byte[] layout = extras.get(
                            InknoteContainer.resourcePath(OFFICE_LAYOUT_RESOURCE_ID));
                    if (layout == null) {
                        return; // legacy container without layout: pages stay blank
                    }
                    File resourceDir = new File(cacheDir, "office/" + documentId);
                    deleteChildren(resourceDir);
                    resourceDir.mkdirs();
                    ImportedDocument imported =
                            ImportedDocumentJson.fromBytes(layout, resourceDir);
                    // Bitmap payloads live in the container extras; materialize
                    // them so OfficePagePainter can decode by resourceId.
                    for (String resourceId : imported.getResourceFiles().keySet()) {
                        byte[] data = extras.get(InknoteContainer.resourcePath(resourceId));
                        if (data != null) {
                            writeBytes(imported.resourceFile(resourceId), data);
                        }
                    }
                    provider = new OfficeBackgroundProvider(documentId, imported);
                } else {
                    byte[] pdf = extras.get(InknoteContainer.resourcePath(PDF_RESOURCE_ID));
                    if (pdf == null) {
                        return;
                    }
                    File pdfFile = new File(cacheDir, "pdf/" + documentId + ".pdf");
                    pdfFile.getParentFile().mkdirs();
                    writeBytes(pdfFile, pdf);
                    engine = new AndroidPdfEngineFactory().open(pdfFile);
                    provider = new PdfBackgroundProvider(documentId, engine);
                }
            } catch (IOException | RuntimeException e) {
                // Unreadable container or corrupt payload: edit as plain pages.
                if (engine != null) {
                    engine.close();
                }
                return;
            }
            final PageBackgroundProvider readyProvider = provider;
            final PdfEngine readyEngine = engine;
            AppExecutors.get().mainThread().post(() -> {
                if (binding == null) {
                    // View died while loading: nothing wired, release directly.
                    readyProvider.release();
                    if (readyEngine != null) {
                        readyEngine.close();
                    }
                    return;
                }
                backgroundProvider = readyProvider;
                pdfEngine = readyEngine;
                binding.inkView.getRenderer().setPageBackgroundProvider(readyProvider);
                services.thumbnails().setPageBackgroundProvider(readyProvider);
                services.thumbnails().clear(); // drop provider-less blank thumbs
                if (continuousAdapter != null) {
                    continuousAdapter.setPageBackgroundProvider(readyProvider);
                }
                refreshInkAll();
                refreshThumbnails();
            });
        });
    }

    private static void writeBytes(File target, byte[] data) throws IOException {
        try (FileOutputStream out = new FileOutputStream(target)) {
            out.write(data);
        }
    }

    private static void deleteChildren(File dir) {
        File[] children = dir.listFiles();
        if (children != null) {
            for (File child : children) {
                child.delete();
            }
        }
    }

    /**
     * Unhooks and releases the imported-document background. The release runs
     * on the single background thread so queued thumbnail renders finish
     * before their bitmaps are recycled. Idempotent.
     */
    private void releaseDocumentBackground() {
        if (backgroundProvider == null) {
            return;
        }
        if (binding != null) {
            binding.inkView.getRenderer().setPageBackgroundProvider(null);
        }
        services.thumbnails().setPageBackgroundProvider(null);
        if (continuousAdapter != null) {
            continuousAdapter.setPageBackgroundProvider(null);
        }
        final PageBackgroundProvider provider = backgroundProvider;
        final PdfEngine engine = pdfEngine;
        backgroundProvider = null;
        pdfEngine = null;
        AppExecutors.get().background().execute(() -> {
            provider.release();
            if (engine != null) {
                engine.close();
            }
        });
    }

    /**
     * Re-registers the container's resources/ entries (inserted images, image
     * page backgrounds) into {@link EditorBitmapRegistry} so the painters
     * resolve them after a reopen instead of drawing placeholders. Decoding
     * runs on the IO executor; the content re-renders once entries are loaded.
     */
    private void loadImageResources() {
        AppExecutors.get().io().execute(() -> {
            Map<String, byte[]> extras;
            try {
                extras = services.repository().readExtras(documentId);
            } catch (IOException e) {
                return; // container unreadable: placeholders stay, editing unaffected
            }
            int prefix = InknoteContainer.DIR_RESOURCES.length();
            for (Map.Entry<String, byte[]> e : extras.entrySet()) {
                if (!e.getKey().startsWith(InknoteContainer.DIR_RESOURCES)) {
                    continue;
                }
                Bitmap bitmap = BitmapFactory.decodeByteArray(
                        e.getValue(), 0, e.getValue().length);
                if (bitmap != null) {
                    // Key is the entry name verbatim (e.g. an import's "image_0"
                    // or an inserted image's generated id), matching how
                    // PageRenderer/ImagePainter resolve resourceIds.
                    EditorBitmapRegistry.put(e.getKey().substring(prefix), bitmap);
                }
            }
            AppExecutors.get().mainThread().post(() -> {
                if (binding != null) {
                    refreshInkAll();
                }
            });
        });
    }

    private void markDirty() {
        dirty = true;
        handler.removeCallbacks(saveRunnable);
        handler.postDelayed(saveRunnable, AUTOSAVE_DEBOUNCE_MS);
    }

    private void saveNow() {
        handler.removeCallbacks(saveRunnable);
        if (document == null) {
            return;
        }
        if (!dirty) {
            return;
        }
        dirty = false;
        services.repository().saveDocumentAsync(document, result -> {
            // Failure handling/logging lives in the storage layer; the journal
            // still holds every committed stroke until a save succeeds.
        });
    }

    /** Renames the document; the tab strip reflects it via the host. */
    private void renameDocument(String title) {
        if (document == null || title == null) {
            return;
        }
        String trimmed = title.trim();
        if (!trimmed.isEmpty() && !trimmed.equals(document.title)) {
            document.title = trimmed;
            dirty = true;
            notifyTitleChanged();
            services.searchIndexer().indexFileName(documentId, trimmed);
        }
    }

    private void notifyTitleChanged() {
        if (getActivity() instanceof HostActivity) {
            ((HostActivity) getActivity()).onDocumentTitleChanged(documentId, currentTitle());
        }
    }

    // ------------------------------------------------------------------
    // Top bar
    // ------------------------------------------------------------------

    private void wireTopBar() {
        binding.btnBack.setOnClickListener(v ->
                requireActivity().getOnBackPressedDispatcher().onBackPressed());
        binding.btnUndo.setOnClickListener(v -> performUndo());
        binding.btnRedo.setOnClickListener(v -> performRedo());
        binding.btnSearch.setOnClickListener(v -> toggleSearchPanel());
        binding.btnMore.setOnClickListener(v -> showMoreMenu());
        binding.btnAux.setOnClickListener(v -> showAuxPopup());
        binding.btnFitPage.setOnClickListener(v -> fitCurrentPage());
    }

    // ------------------------------------------------------------------
    // In-document search (spec 22.3 hit jump, scoped to this document)
    // ------------------------------------------------------------------

    private static final long SEARCH_DEBOUNCE_MS = 300L;
    private static final int SEARCH_FTS_LIMIT = 200;
    private static final int SEARCH_RESULT_LIMIT = 50;

    private void setupSearchPanel() {
        searchAdapter = new SearchResultAdapter(this::onSearchResultClick);
        searchAdapter.setHighlightColor(ContextCompat.getColor(requireContext(),
                com.leslie.colorosnote.feature.search.R.color.search_highlight));
        binding.searchResults.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.searchResults.setAdapter(searchAdapter);
        binding.searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                onSearchQueryChanged(s == null ? "" : s.toString());
            }
        });
        binding.searchInput.setOnEditorActionListener((v, actionId, event) -> {
            hideKeyboard();
            return true;
        });
        binding.searchClear.setOnClickListener(v -> binding.searchInput.setText(""));
        binding.searchClose.setOnClickListener(v -> hideSearchPanel());
    }

    private void toggleSearchPanel() {
        if (binding.searchPanel.getVisibility() == View.VISIBLE) {
            hideSearchPanel();
        } else {
            showSearchPanel();
        }
    }

    private void showSearchPanel() {
        dismissPopups();
        binding.searchPanel.setVisibility(View.VISIBLE);
        if (binding.searchInput.getText().length() == 0) {
            showSearchIdle();
        } else {
            runEditorSearch(binding.searchInput.getText().toString());
        }
        binding.searchInput.requestFocus();
        InputMethodManager imm = ContextCompat.getSystemService(requireContext(),
                InputMethodManager.class);
        if (imm != null) {
            imm.showSoftInput(binding.searchInput, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    private void hideSearchPanel() {
        cancelPendingSearch();
        hideKeyboard();
        binding.searchPanel.setVisibility(View.GONE);
    }

    private void hideKeyboard() {
        if (binding == null) {
            return;
        }
        InputMethodManager imm = ContextCompat.getSystemService(requireContext(),
                InputMethodManager.class);
        if (imm != null) {
            imm.hideSoftInputFromWindow(binding.searchInput.getWindowToken(), 0);
        }
    }

    private void onSearchQueryChanged(String text) {
        currentSearchQuery = text;
        binding.searchClear.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
        if (searchDebounce != null) {
            AppExecutors.get().mainThread().removeCallbacks(searchDebounce);
        }
        searchDebounce = () -> runEditorSearch(text);
        AppExecutors.get().mainThread().postDelayed(searchDebounce, SEARCH_DEBOUNCE_MS);
    }

    /**
     * Debounced, generation-guarded query against the shared FTS index, scoped
     * to the open document. Document-level segments (file name / tag) are
     * dropped — inside the editor only page-level hits are actionable.
     */
    private void runEditorSearch(String raw) {
        cancelPendingSearch();
        if (document == null || SearchQueryBuilder.isBlank(raw)) {
            showSearchIdle();
            return;
        }
        final long gen = searchGeneration.incrementAndGet();
        final List<String> terms = SearchQueryBuilder.terms(raw);
        final String ftsQuery = SearchQueryBuilder.buildFtsQuery(raw);
        final String docId = documentId;
        pendingSearch = AppExecutors.get().io().submit(() -> {
            List<SearchResultItem> items;
            try {
                List<IndexDao.SearchMatch> matches = services.database()
                        .indexDao().search(ftsQuery, SEARCH_FTS_LIMIT);
                List<IndexDao.SearchMatch> scoped = new ArrayList<>();
                for (IndexDao.SearchMatch m : matches) {
                    if (m != null && docId.equals(m.documentId)
                            && m.pageId != null && !m.pageId.isEmpty()) {
                        scoped.add(m);
                    }
                }
                items = SearchResultMerger.merge(scoped, null, terms,
                        id -> new SearchResultMerger.DocMeta(
                                currentTitle(), "", document.updatedAt),
                        SEARCH_RESULT_LIMIT);
            } catch (RuntimeException e) {
                items = new ArrayList<>();
            }
            List<SearchResultItem> finalItems = items;
            AppExecutors.get().mainThread().post(() -> {
                if (gen == searchGeneration.get() && binding != null
                        && binding.searchPanel.getVisibility() == View.VISIBLE) {
                    showSearchResults(finalItems);
                }
            });
        });
    }

    private void cancelPendingSearch() {
        searchGeneration.incrementAndGet();
        if (pendingSearch != null) {
            pendingSearch.cancel(false);
            pendingSearch = null;
        }
    }

    private void showSearchIdle() {
        searchAdapter.submit(null);
        binding.searchResults.setVisibility(View.GONE);
        binding.searchEmpty.setVisibility(View.VISIBLE);
        binding.searchEmpty.setText(R.string.editor_search_empty_idle);
    }

    private void showSearchResults(List<SearchResultItem> items) {
        searchAdapter.submit(items);
        boolean empty = items.isEmpty();
        binding.searchResults.setVisibility(empty ? View.GONE : View.VISIBLE);
        binding.searchEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (empty) {
            binding.searchEmpty.setText(
                    getString(R.string.editor_search_empty_result, currentSearchQuery));
        }
    }

    /** Jumps to the hit page and flashes the hit region when it has one. */
    private void onSearchResultClick(SearchResultItem item) {
        if (document == null || binding == null) {
            return;
        }
        hideKeyboard();
        int index = -1;
        for (int i = 0; i < document.pages.size(); i++) {
            if (document.pages.get(i).id.equals(item.pageId)) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            return;
        }
        goToPage(index);
        // Flash the hit region (single-page mode only: in continuous mode the
        // overlay spans the whole scroll container, not one page).
        if (item.boundingBox != null && item.boundingBox.length == 4 && !continuousMode) {
            float[] tl = new float[2];
            float[] br = new float[2];
            binding.inkView.getViewport().pageToScreen(
                    item.boundingBox[0], item.boundingBox[1], tl);
            binding.inkView.getViewport().pageToScreen(
                    item.boundingBox[2], item.boundingBox[3], br);
            binding.overlayView.flashRect(new RectF(tl[0], tl[1], br[0], br[1]));
        }
    }

    // ------------------------------------------------------------------
    // Core toolbar
    // ------------------------------------------------------------------

    private void wireCoreToolbar() {
        Map<EditorTool, ImageButton> buttons = new EnumMap<>(EditorTool.class);
        buttons.put(EditorTool.FOUNTAIN_PEN, binding.btnToolFountain);
        buttons.put(EditorTool.BALLPOINT, binding.btnToolBallpoint);
        buttons.put(EditorTool.PENCIL, binding.btnToolPencil);
        buttons.put(EditorTool.HIGHLIGHTER, binding.btnToolHighlighter);
        buttons.put(EditorTool.ERASER, binding.btnToolEraser);
        buttons.put(EditorTool.LASSO, binding.btnToolLasso);
        for (Map.Entry<EditorTool, ImageButton> e : buttons.entrySet()) {
            EditorTool tool = e.getKey();
            e.getValue().setOnClickListener(v -> onToolClicked(tool, e.getValue()));
        }
        updateToolSelection();
    }

    private void onToolClicked(EditorTool tool, View anchor) {
        if (tool == currentTool) {
            // Aux tools (text box, image, ...) never change currentTool, so a
            // tap on the active pen tool would be misread as a second tap and
            // only open the brush popover, leaving the object tool armed
            // forever. Disarm first so the user can always get back to ink.
            if (objectToolController != null && objectToolController.isArmed()) {
                objectToolController.disarm();
                return;
            }
            onToolSecondTap(tool, anchor);
            return;
        }
        selectTool(tool);
    }

    /** Second tap on the active tool opens its property popover. */
    private void onToolSecondTap(EditorTool tool, View anchor) {
        dismissToolPopup();
        if (tool.isPen()) {
            toolPopup = BrushPropertiesPopup.show(anchor, tool, presets.get(tool),
                    presetStore.straightenFor(tool), (t, preset, straighten) -> {
                        presets.put(t, preset);
                        presetStore.savePreset(t, preset, straighten);
                        if (t == currentTool) {
                            applyCurrentBrush();
                        }
                    });
        } else if (tool == EditorTool.ERASER) {
            toolPopup = EraserPopup.show(anchor, eraserConfig,
                    config -> presetStore.saveEraserConfig(config));
        } else if (tool == EditorTool.LASSO) {
            toolPopup = LassoModePopup.show(anchor, presetStore.lassoMode(),
                    presetStore::saveLassoMode);
        }
    }

    private void selectTool(EditorTool tool) {
        if (continuousMode && (tool == EditorTool.ERASER || tool == EditorTool.LASSO)) {
            toast(getString(R.string.editor_eraser_single_only));
            return;
        }
        currentTool = tool;
        updateToolSelection();
        if (objectToolController != null) {
            objectToolController.disarm();
        }
        if (tool.isPen()) {
            applyCurrentBrush();
        }
        if (tool != EditorTool.LASSO) {
            selectionController.clear();
        }
        applyInputPolicy();
    }

    private void updateToolSelection() {
        binding.btnToolFountain.setSelected(currentTool == EditorTool.FOUNTAIN_PEN);
        binding.btnToolBallpoint.setSelected(currentTool == EditorTool.BALLPOINT);
        binding.btnToolPencil.setSelected(currentTool == EditorTool.PENCIL);
        binding.btnToolHighlighter.setSelected(currentTool == EditorTool.HIGHLIGHTER);
        binding.btnToolEraser.setSelected(currentTool == EditorTool.ERASER);
        binding.btnToolLasso.setSelected(currentTool == EditorTool.LASSO);
    }

    private void applyCurrentBrush() {
        BrushPreset preset = presets.get(currentTool);
        if (preset == null) {
            return;
        }
        binding.inkView.setBrush(preset);
        if (continuousAdapter != null) {
            continuousAdapter.setBrush(preset);
        }
    }

    /** Finger/stylus policy from settings (spec 29 防误触). */
    private void applyInputPolicy() {
        PalmRejectionPolicy policy;
        if (settings.fingerMode == EditorSettings.FingerMode.DRAW) {
            // Strictest palm-rejection level still reserves writing for the pen.
            policy = settings.palmRejectionLevel >= 3
                    ? PalmRejectionPolicy.STYLUS_ONLY
                    : PalmRejectionPolicy.FINGER_AND_STYLUS;
        } else {
            policy = PalmRejectionPolicy.STYLUS_ONLY;
        }
        binding.inkView.setPalmRejectionPolicy(policy);
    }

    /** 左手/右手模式：镜像整条统一工具栏顺序。 */
    private void applyToolbarSide() {
        int dir = settings.toolbarSide == EditorSettings.HandSide.LEFT
                ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR;
        binding.topBar.setLayoutDirection(dir);
    }

    // ------------------------------------------------------------------
    // Auxiliary tools ("+" insert popup)
    // ------------------------------------------------------------------

    /** Shows the insert/auxiliary tool panel anchored below the "+" button. */
    private void showAuxPopup() {
        PopupAuxToolsBinding aux = PopupAuxToolsBinding.inflate(
                LayoutInflater.from(requireContext()));
        PopupWindow popup = new PopupWindow(aux.getRoot(),
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setElevation(dp(16));
        int recColor = getResources().getColor(
                recordingActive ? R.color.recording_red : R.color.text_secondary, null);
        aux.imgAuxRecording.setImageTintList(android.content.res.ColorStateList.valueOf(recColor));
        aux.txtAuxRecording.setTextColor(recColor);
        wireAuxButtons(aux, popup);
        popup.showAsDropDown(binding.btnAux, 0, dp(6), Gravity.END);
    }

    private void wireAuxButtons(PopupAuxToolsBinding aux, PopupWindow popup) {
        aux.btnAuxImage.setOnClickListener(v -> dispatchFromPopup(popup, AuxTool.IMAGE));
        aux.btnAuxTextbox.setOnClickListener(v -> dispatchFromPopup(popup, AuxTool.TEXT_BOX));
        aux.btnAuxShape.setOnClickListener(v -> dispatchFromPopup(popup, AuxTool.SHAPE));
        aux.btnAuxTape.setOnClickListener(v -> dispatchFromPopup(popup, AuxTool.TAPE));
        aux.btnAuxSticker.setOnClickListener(v -> dispatchFromPopup(popup, AuxTool.STICKER));
        aux.btnAuxOcr.setOnClickListener(v -> dispatchFromPopup(popup, AuxTool.OCR));
        aux.btnAuxHandwriting.setOnClickListener(
                v -> dispatchFromPopup(popup, AuxTool.HANDWRITING_TO_TEXT));
        aux.btnAuxRecording.setOnClickListener(v -> dispatchFromPopup(popup, AuxTool.RECORDING));
        // Long-press opens the recording manager (play/delete saved sessions).
        aux.btnAuxRecording.setOnLongClickListener(v -> {
            popup.dismiss();
            showRecordingsDialog();
            return true;
        });
        aux.btnAuxStt.setOnClickListener(v -> dispatchFromPopup(popup, AuxTool.SPEECH_TO_TEXT));
        aux.btnAuxPage.setOnClickListener(v -> dispatchFromPopup(popup, AuxTool.PAGE_TOOLS));
    }

    private void dispatchFromPopup(PopupWindow popup, AuxTool tool) {
        popup.dismiss();
        dispatchAuxTool(tool);
    }

    // ------------------------------------------------------------------
    // Recording control bar / recording manager (spec 20)
    // ------------------------------------------------------------------

    private void wireRecordingBar() {
        binding.btnRecordingPause.setOnClickListener(v -> {
            if (recordingController != null) {
                recordingController.togglePause();
            }
        });
        binding.btnRecordingStop.setOnClickListener(v -> {
            if (recordingController != null) {
                recordingController.stopRecording();
            }
        });
    }

    /**
     * Re-syncs the bar after a view rebuild while a session is still active
     * (the bar view starts out GONE in the fresh layout).
     */
    private void syncRecordingBar() {
        if (recordingController == null || !recordingController.isRecording()) {
            return;
        }
        recordingBar.onRecordingStarted();
        recordingBar.onElapsed(recordingController.elapsedMs());
        if (recordingController.isPaused()) {
            recordingBar.onPaused();
        }
    }

    /** Recording manager entry point (long-press on the aux 录音 item). */
    private void showRecordingsDialog() {
        if (getContext() == null || documentId == null) {
            return;
        }
        dismissRecordingDialog();
        recordingDialog = RecordingListDialog.show(requireContext(),
                services.audioDirFor(documentId));
    }

    private void dismissRecordingDialog() {
        if (recordingDialog != null) {
            recordingDialog.dismiss();
            recordingDialog = null;
        }
    }

    private void dispatchAuxTool(AuxTool tool) {
        switch (tool) {
            case RECORDING:
                if (recordingController == null) {
                    return;
                }
                if (recordingController.isRecording()) {
                    recordingController.toggle();
                    return;
                }
                // The recordings list used to hide behind a long-press, which
                // nobody finds — when the note already has sessions, offer an
                // explicit chooser between starting a new one and the list.
                if (documentId == null) {
                    return;
                }
                AppExecutors.get().io().execute(() -> {
                    int count = RecordingLibrary.count(services.audioDirFor(documentId));
                    AppExecutors.get().mainThread().post(() -> {
                        if (!isAdded() || binding == null || recordingController == null) {
                            return;
                        }
                        if (count == 0) {
                            recordingController.toggle();
                        } else {
                            List<MenuRowPopup.Item> items = new ArrayList<>();
                            items.add(new MenuRowPopup.Item(R.drawable.ic_aux_mic,
                                    getString(R.string.recording_start), true,
                                    () -> recordingController.toggle()));
                            items.add(new MenuRowPopup.Item(R.drawable.ic_rec_list,
                                    getString(R.string.recording_view_list, count), true,
                                    this::showRecordingsDialog));
                            MenuRowPopup.showAsDropDown(binding.btnAux, items);
                        }
                    });
                });
                return;
            case PAGE_TOOLS:
                binding.thumbnailBar.setVisibility(
                        binding.thumbnailBar.getVisibility() == View.VISIBLE
                                ? View.GONE : View.VISIBLE);
                return;
            default:
                break;
        }
        if (auxToolListener != null) {
            auxToolListener.onAuxToolSelected(tool);
        } else {
            toast(getString(R.string.aux_tool_coming_soon));
        }
    }

    // ------------------------------------------------------------------
    // RecordingController.Ui (spec 20)
    // ------------------------------------------------------------------

    @Override
    public void requestMicPermission() {
        micPermission.launch(Manifest.permission.RECORD_AUDIO);
    }

    @Override
    public void onRecordingStateChanged(boolean recording) {
        recordingActive = recording;
        if (recording) {
            recordingBar.onRecordingStarted();
        } else {
            recordingBar.onStopped();
        }
    }

    @Override
    public void onRecordingPauseChanged(boolean paused) {
        if (paused) {
            recordingBar.onPaused();
        } else {
            recordingBar.onResumed();
        }
    }

    @Override
    public void onRecordingFinished(long durationMs, List<File> segments) {
        if (getContext() == null) {
            return;
        }
        dismissRecordingDialog();
        recordingDialog = RecordingStoppedDialog.show(requireContext(), durationMs,
                segments, services.speechTranscription(),
                text -> RecognizedTextInserter.insertBelowContent(this, text));
    }

    @Override
    public void onRecordingElapsed(long elapsedMs) {
        recordingBar.onElapsed(elapsedMs);
    }

    @Override
    public void onRecordingError(String message) {
        toast(getString(R.string.recording_permission_denied));
    }

    // ------------------------------------------------------------------
    // Stroke completion (journal + undo + audio anchor)
    // ------------------------------------------------------------------

    private void onStrokeFinishedShared(@Nullable Page page, Stroke stroke) {
        if (page == null || document == null) {
            return;
        }
        if (recordingController != null) {
            recordingController.anchorStrokeIfRecording(stroke);
        }
        // Journaled via journalMirrorListener (undoStack.execute → onApplied).
        undoStack.execute(new InkCommands.AddStroke(page, stroke));
        onContentEdited();
        maybeShowPressureHint();
    }

    private void maybeShowPressureHint() {
        if (binding == null || !settings.showSimulatedPressureHint) {
            return;
        }
        boolean simulated = !continuousMode
                && binding.inkView.getStrokeBuilder() != null
                && binding.inkView.getStrokeBuilder().getPressureSource().isSimulated();
        if (simulated) {
            binding.pressureHint.setVisibility(View.VISIBLE);
            handler.removeCallbacks(hideHintRunnable);
            handler.postDelayed(hideHintRunnable, HINT_SHOW_MS);
        }
    }

    // ------------------------------------------------------------------
    // Undo / redo (spec 30)
    // ------------------------------------------------------------------

    private void performUndo() {
        if (undoStack.canUndo()) {
            undoStack.undo();
            afterHistoryChanged();
        }
    }

    private void performRedo() {
        if (undoStack.canRedo()) {
            undoStack.redo();
            afterHistoryChanged();
        }
    }

    private void afterHistoryChanged() {
        selectionController.clear();
        if (document != null) {
            if (document.pages.isEmpty()) {
                // Undoing back to zero pages is prevented by deletePage(); be safe.
                return;
            }
            if (currentPageIndex >= document.pages.size()) {
                currentPageIndex = document.pages.size() - 1;
            }
            if (!continuousMode && binding != null) {
                binding.inkView.setPage(currentPage());
            }
        }
        refreshInkAll();
        refreshThumbnails();
        onContentEdited();
    }

    private void updateUndoRedoState() {
        if (binding == null) {
            return;
        }
        binding.btnUndo.setEnabled(undoStack.canUndo());
        binding.btnRedo.setEnabled(undoStack.canRedo());
        binding.btnUndo.setAlpha(undoStack.canUndo() ? 1f : 0.35f);
        binding.btnRedo.setAlpha(undoStack.canRedo() ? 1f : 0.35f);
    }

    private void onContentEdited() {
        markDirty();
        updateUndoRedoState();
    }

    // ------------------------------------------------------------------
    // Journal mirroring (crash recovery, requirement 10)
    // ------------------------------------------------------------------

    private void mirrorToJournal(Command command, boolean applied) {
        if (documentId == null || services == null) {
            return;
        }
        mirrorCommand(services.journalFor(documentId),
                AppExecutors.get().io(), command, applied);
    }

    /**
     * Translates one history transition into journal entries. Only stroke
     * add/remove is mirrored: those are the edits whose loss on crash would
     * resurrect deleted content. In-place edits (transform/color) and page or
     * object structure changes ride the debounced full save, as before.
     */
    private static void mirrorCommand(JournalStore journal, Executor io,
                                      Command command, boolean applied) {
        if (command instanceof InkCommands.AddStroke) {
            InkCommands.AddStroke add = (InkCommands.AddStroke) command;
            if (applied) {
                journal.appendStrokeAsync(io, add.page().id, add.stroke());
            } else {
                journal.appendStrokeDeleteAsync(io, add.page().id, add.stroke().id);
            }
        } else if (command instanceof InkCommands.RemoveStroke) {
            InkCommands.RemoveStroke remove = (InkCommands.RemoveStroke) command;
            for (Stroke stroke : remove.strokes()) {
                if (applied) {
                    journal.appendStrokeDeleteAsync(io, remove.page().id, stroke.id);
                } else {
                    journal.appendStrokeAsync(io, remove.page().id, stroke);
                }
            }
        } else if (command instanceof EditorCommands.ReplaceStrokes) {
            EditorCommands.ReplaceStrokes replace = (EditorCommands.ReplaceStrokes) command;
            List<Stroke> from = applied ? replace.before() : replace.after();
            List<Stroke> to = applied ? replace.after() : replace.before();
            mirrorStrokeListDiff(journal, io, replace.page(), from, to);
        } else if (command instanceof InkCommands.Composite) {
            for (Command child : ((InkCommands.Composite) command).commands()) {
                mirrorCommand(journal, io, child, applied);
            }
        }
    }

    /**
     * Journals the difference between two stroke-list snapshots (erase
     * gestures): ids only in {@code from} get a tombstone, strokes only in
     * {@code to} (e.g. split pieces) are appended. Shared ids are untouched
     * because the eraser never mutates a stroke in place.
     */
    private static void mirrorStrokeListDiff(JournalStore journal, Executor io, Page page,
                                             List<Stroke> from, List<Stroke> to) {
        Set<String> toIds = new HashSet<>();
        for (Stroke stroke : to) {
            toIds.add(stroke.id);
        }
        Set<String> fromIds = new HashSet<>();
        for (Stroke stroke : from) {
            fromIds.add(stroke.id);
        }
        for (Stroke stroke : from) {
            if (!toIds.contains(stroke.id)) {
                journal.appendStrokeDeleteAsync(io, page.id, stroke.id);
            }
        }
        for (Stroke stroke : to) {
            if (!fromIds.contains(stroke.id)) {
                journal.appendStrokeAsync(io, page.id, stroke);
            }
        }
    }

    // ------------------------------------------------------------------
    // EditorGestureController.Host
    // ------------------------------------------------------------------

    @Override
    public EditorSettings settings() {
        return settings;
    }

    @Override
    public EditorTool currentTool() {
        return currentTool;
    }

    @Override
    public LassoEngine.Mode lassoMode() {
        return presetStore.lassoMode();
    }

    @Override
    public EraserConfig eraserConfig() {
        return eraserConfig;
    }

    @Override
    public ViewportTransform viewport() {
        return binding.inkView.getViewport();
    }

    @Override
    public boolean selectionActive() {
        return selectionController.hasSelection();
    }

    @Override
    public boolean selectionContainsPagePoint(float x, float y) {
        return selectionController.containsPagePoint(x, y);
    }

    @Override
    public float[] selectionCenterPage() {
        float[] b = selectionController.boundsPage();
        if (b == null) {
            return null;
        }
        return new float[]{(b[0] + b[2]) / 2f, (b[1] + b[3]) / 2f};
    }

    @Override
    public void clearSelection() {
        selectionController.clear();
    }

    @Override
    public void onWritingStateChanged(boolean writing) {
        // Bars must stay visible at all times (user requirement): no
        // auto-hide while writing, no matter where the pen touches.
    }

    @Override
    public void onEraseBegin() {
        Page page = currentPage();
        if (page == null) {
            return;
        }
        eraseBefore = new ArrayList<>(page.strokes);
        eraseChanged = false;
    }

    @Override
    public void onEraseDelta(float[] pagePath) {
        Page page = currentPage();
        if (page == null || eraseBefore == null) {
            return;
        }
        EraserEngine.EraseResult result = eraserEngine.erase(page.strokes, pagePath,
                eraserConfig.radius, eraserConfig.mode, eraserConfig.filter);
        if (result.changed()) {
            page.strokes.clear();
            page.strokes.addAll(result.remaining);
            eraseChanged = true;
            scheduleContentRefresh();
        }
    }

    @Override
    public void onEraseEnd() {
        if (eraseBefore == null) {
            return;
        }
        Page page = currentPage();
        if (eraseChanged && page != null) {
            // The gesture mutated the list live; ReplaceStrokes.execute()
            // re-applies the same end state, so this is one history entry.
            undoStack.execute(new EditorCommands.ReplaceStrokes(
                    page, eraseBefore, page.strokes));
            onContentEdited();
        }
        eraseBefore = null;
        eraseChanged = false;
        refreshInkNow();
    }

    // -- Continuous-mode stylus long-press erase -----------------------------

    private final PageContinuousAdapter.EraseCallback continuousEraseCallback =
            new PageContinuousAdapter.EraseCallback() {
                @Override
                public void onContinuousEraseBegin(Page page) {
                    eraseTargetPage = page;
                    eraseBefore = new ArrayList<>(page.strokes);
                    eraseChanged = false;
                }

                @Override
                public void onContinuousEraseDelta(Page page, float[] pagePath) {
                    if (eraseBefore == null || page != eraseTargetPage) {
                        return;
                    }
                    EraserEngine.EraseResult result = eraserEngine.erase(page.strokes,
                            pagePath, eraserConfig.radius, eraserConfig.mode,
                            eraserConfig.filter);
                    if (result.changed()) {
                        page.strokes.clear();
                        page.strokes.addAll(result.remaining);
                        eraseChanged = true;
                        scheduleContentRefresh();
                    }
                }

                @Override
                public void onContinuousEraseEnd(Page page) {
                    if (eraseBefore == null || page != eraseTargetPage) {
                        return;
                    }
                    if (eraseChanged) {
                        undoStack.execute(new EditorCommands.ReplaceStrokes(
                                page, eraseBefore, page.strokes));
                        onContentEdited();
                    }
                    eraseBefore = null;
                    eraseChanged = false;
                    refreshInkNow();
                    eraseTargetPage = null;
                }

                @Override
                public float eraserRadiusPage() {
                    return eraserConfig.radius;
                }
            };

    @Override
    public void onAreaErase(float[] pagePolygon) {
        Page page = currentPage();
        if (page == null) {
            return;
        }
        EraserEngine.EraseResult result =
                eraserEngine.eraseArea(page.strokes, pagePolygon, eraserConfig.filter);
        if (result.changed()) {
            undoStack.execute(new EditorCommands.ReplaceStrokes(
                    page, page.strokes, result.remaining));
            refreshInkNow();
            onContentEdited();
        }
    }

    @Override
    public void onLassoFinished(float[] pageRegion, LassoEngine.Mode mode) {
        Page page = currentPage();
        if (page == null) {
            return;
        }
        float[] region = pageRegion;
        if (mode == LassoEngine.Mode.RECT && region.length > 4) {
            // LassoEngine expects {left, top, right, bottom} for RECT: use the
            // drag's first/last sample as opposite corners.
            region = new float[]{pageRegion[0], pageRegion[1],
                    pageRegion[pageRegion.length - 2], pageRegion[pageRegion.length - 1]};
        }
        LassoEngine.Selection selection =
                lassoEngine.select(page, region, mode, LassoEngine.ALL_TYPES);
        if (selection.isEmpty()) {
            selectionController.clear();
            return;
        }
        selectionController.setSelection(page, selection);
    }

    @Override
    public void onLassoTap(float pageX, float pageY) {
        Page page = currentPage();
        if (page == null || binding == null) {
            return;
        }
        // v1.4.6: tap-to-select (GoodNotes-style) — select the topmost
        // unlocked object under the tap; locked objects are skipped by
        // LassoEngine and count as a miss. Same 20px hit slop as the object
        // tools, converted to page units.
        float slop = TAP_HIT_SLOP_PX
                / Math.max(0.01f, binding.inkView.getViewport().getScale());
        LassoEngine.Selection selection = lassoEngine.selectAt(page, pageX, pageY, slop);
        if (selection.isEmpty()) {
            selectionController.clear();
            return;
        }
        selectionController.setSelection(page, selection);
    }

    @Override
    public void onSelectionTransformBegin() {
        if (!undoStack.isInTransaction()) {
            undoStack.beginTransaction();
        }
    }

    @Override
    public void onSelectionTransform(float[] matrix9) {
        selectionController.applyTransformStep(undoStack, matrix9);
        updateSelectionOverlay();
    }

    @Override
    public void onSelectionTransformEnd() {
        if (undoStack.isInTransaction()) {
            undoStack.endTransaction();
        }
        refreshInkNow();
        onContentEdited();
    }

    @Override
    public void onViewportChanged() {
        if (binding != null) {
            binding.inkView.invalidate();
            updateSelectionOverlay();
            updateFitPageButton();
            updateZoomIndicator();
        }
    }

    /**
     * Live zoom indicator (v2.1.3): top-center pill showing the current
     * viewport scale as a percentage of the fitted whole-page view. It appears
     * while zooming and auto-hides {@link #ZOOM_INDICATOR_HIDE_MS} after the
     * last zoom movement (every viewport change resets the timer, so it stays
     * up during an active pinch). Continuous-scroll mode hides it entirely.
     */
    private static final long ZOOM_INDICATOR_HIDE_MS = 1500L;

    private final Runnable zoomIndicatorHider = new Runnable() {
        @Override
        public void run() {
            if (binding != null) {
                binding.zoomIndicator.setVisibility(View.GONE);
            }
        }
    };

    private void updateZoomIndicator() {
        if (binding == null) {
            return;
        }
        handler.removeCallbacks(zoomIndicatorHider);
        if (continuousMode || fitPageScale <= 0f) {
            binding.zoomIndicator.setVisibility(View.GONE);
            return;
        }
        int percent = Math.round(
                binding.inkView.getViewport().getScale() / fitPageScale * 100f);
        if (percent == 100) {
            // No zoom: nothing to report, get the label out of the way.
            binding.zoomIndicator.setVisibility(View.GONE);
            return;
        }
        binding.zoomIndicator.setText(getString(R.string.editor_zoom_format,
                percent));
        binding.zoomIndicator.setVisibility(View.VISIBLE);
        // Auto-hide once the zooming stops (no further viewport changes).
        handler.postDelayed(zoomIndicatorHider, ZOOM_INDICATOR_HIDE_MS);
    }

    /**
     * Fits the current page to the whole viewport (the "whole-page" view).
     * Also used by the floating fit-page button so a zoomed-in user can jump
     * back to the overview in one tap (云记-style).
     */
    private void fitCurrentPage() {
        if (binding == null) {
            return;
        }
        Page page = currentPage();
        if (page == null) {
            return;
        }
        ViewportTransform viewport = binding.inkView.getViewport();
        viewport.fitPage(page.width, page.height,
                binding.inkView.getWidth(), binding.inkView.getHeight(), 24f);
        fitPageScale = viewport.getScale();
        onViewportChanged();
    }

    /**
     * Shows the floating fit-page button whenever the user has zoomed in
     * noticeably beyond the fitted (whole-page) view, so they can quickly
     * return to the overview.
     */
    private void updateFitPageButton() {
        if (binding == null) {
            return;
        }
        boolean zoomedIn = !continuousMode
                && binding.inkView.getViewport().getScale() > fitPageScale * 1.05f;
        binding.btnFitPage.setVisibility(zoomedIn ? View.VISIBLE : View.GONE);
    }

    // ------------------------------------------------------------------
    // Debug-only zoom hook (v1.4.12 on-device verification)
    // ------------------------------------------------------------------

    /**
     * adb cannot inject two-finger pinch gestures, so debuggable builds expose
     * a broadcast hook: {@code am broadcast -a com.leslie.colorosnote.debug.ZOOM
     * --ef factor 1.8}. Never registered in release builds.
     */
    private BroadcastReceiver debugZoomReceiver;

    private void registerDebugZoomHook() {
        // TODO(TEMP-TEST): 临时放开 debug 限制以便在 release 上验证缩放；交付前还原为 debuggable 判断。
        debugZoomReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (binding == null) {
                    return;
                }
                float factor = intent.getFloatExtra("factor", 1f);
                binding.inkView.getViewport().zoomBy(factor,
                        binding.inkView.getWidth() / 2f,
                        binding.inkView.getHeight() / 2f);
                onViewportChanged();
            }
        };
        ContextCompat.registerReceiver(requireContext(), debugZoomReceiver,
                new IntentFilter("com.leslie.colorosnote.debug.ZOOM"),
                ContextCompat.RECEIVER_EXPORTED);
    }

    @Override
    public void onUndoGesture() {
        performUndo();
    }

    @Override
    public void onRedoGesture() {
        performRedo();
    }

    @Override
    public void onLongPressBlank(float viewX, float viewY) {
        showBlankContextMenu(viewX, viewY);
    }

    @Override
    public void onTemporaryToolBegin(EditorTool tool) {
        if (toolBeforeTemporary == null) {
            toolBeforeTemporary = currentTool;
        }
        currentTool = tool;
        updateToolSelection();
    }

    @Override
    public void onTemporaryToolEnd() {
        if (toolBeforeTemporary != null) {
            currentTool = toolBeforeTemporary;
            toolBeforeTemporary = null;
            updateToolSelection();
            if (currentTool.isPen()) {
                applyCurrentBrush();
            }
        }
    }

    // ------------------------------------------------------------------
    // SelectionController.Listener
    // ------------------------------------------------------------------

    @Override
    public void onSelectionChanged() {
        updateSelectionOverlay();
        syncSelectionPopup();
    }

    @Override
    public void onContentMutated() {
        scheduleContentRefresh();
        markDirty();
    }

    private void updateSelectionOverlay() {
        if (binding == null) {
            return;
        }
        float[] b = selectionController.boundsPage();
        if (b == null) {
            binding.overlayView.clearSelection();
            return;
        }
        RectF screen = binding.inkView.getViewport()
                .pageRectToScreen(b[0], b[1], b[2], b[3]);
        binding.overlayView.setSelectionRect(screen);
    }

    private void syncSelectionPopup() {
        if (binding == null) {
            return;
        }
        if (!selectionController.hasSelection() || continuousMode) {
            dismissSelectionPopup();
            return;
        }
        float[] b = selectionController.boundsPage();
        if (b == null) {
            dismissSelectionPopup();
            return;
        }
        RectF screen = binding.inkView.getViewport()
                .pageRectToScreen(b[0], b[1], b[2], b[3]);
        if (selectionPopup == null) {
            selectionPopup = createSelectionPopup();
        }
        selectionPopup.getContentView().measure(
                View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int popupWidth = selectionPopup.getContentView().getMeasuredWidth();
        int x = Math.max(0, (int) screen.centerX() - popupWidth / 2);
        int y = Math.max(0, (int) screen.top - dp(64));
        if (selectionPopup.isShowing()) {
            selectionPopup.update(x, y, -1, -1);
        } else {
            selectionPopup.showAtLocation(binding.inkView, Gravity.TOP | Gravity.START, x, y);
        }
    }

    private PopupWindow createSelectionPopup() {
        PopupLassoActionsBinding b = PopupLassoActionsBinding.inflate(
                LayoutInflater.from(requireContext()));
        PopupWindow popup = new PopupWindow(b.getRoot(),
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, false);
        popup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(
                android.graphics.Color.TRANSPARENT));
        popup.setElevation(12f);
        b.btnSelCopy.setOnClickListener(v -> {
            selectionController.copy();
            toast(getString(R.string.selection_copied));
        });
        b.btnSelCut.setOnClickListener(v -> {
            selectionController.cut(undoStack);
            refreshInkNow();
            onContentEdited();
        });
        b.btnSelPaste.setOnClickListener(v -> pasteFromClipboard());
        b.btnSelColor.setOnClickListener(v -> showSelectionColorPicker(v));
        b.btnSelDelete.setOnClickListener(v -> {
            selectionController.delete(undoStack);
            refreshInkNow();
            onContentEdited();
        });
        return popup;
    }

    private void pasteFromClipboard() {
        Page page = currentPage();
        if (page == null) {
            return;
        }
        if (!selectionController.hasClipboard()) {
            toast(getString(R.string.selection_clipboard_empty));
            return;
        }
        selectionController.paste(undoStack, page);
        refreshInkNow();
        onContentEdited();
    }

    private void showSelectionColorPicker(View anchor) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        int pad = dp(10);
        row.setPadding(pad, pad, pad, pad);
        row.setBackgroundResource(R.drawable.bg_popup);
        PopupWindow popup = new PopupWindow(row,
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setOutsideTouchable(true);
        popup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(
                android.graphics.Color.TRANSPARENT));
        popup.setElevation(16f);
        int size = dp(32);
        int margin = dp(4);
        for (int color : BrushPropertiesPopup.PALETTE) {
            View cell = new View(requireContext());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.setMargins(margin, margin, margin, margin);
            cell.setLayoutParams(lp);
            cell.setBackground(BrushPropertiesPopup.paletteDrawable(color, false));
            cell.setOnClickListener(v -> {
                selectionController.changeColor(undoStack, color);
                refreshInkNow();
                onContentEdited();
                popup.dismiss();
            });
            row.addView(cell);
        }
        popup.showAsDropDown(anchor);
    }

    // ------------------------------------------------------------------
    // Page system (spec 26)
    // ------------------------------------------------------------------

    private void wireThumbnailBar() {
        binding.thumbList.setLayoutManager(
                new LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false));
        binding.btnAddPage.setOnClickListener(v -> addPageAfterCurrent());
    }

    @Nullable
    private Page currentPage() {
        if (document == null || document.pages.isEmpty()) {
            return null;
        }
        int index = Math.max(0, Math.min(currentPageIndex, document.pages.size() - 1));
        return document.pages.get(index);
    }

    private void goToPage(int index) {
        if (document == null || document.pages.isEmpty()) {
            return;
        }
        currentPageIndex = Math.max(0, Math.min(index, document.pages.size() - 1));
        selectionController.clear();
        if (continuousMode) {
            binding.continuousList.scrollToPosition(currentPageIndex);
        } else {
            binding.inkView.setPage(currentPage());
            if (needsFit) {
                needsFit = false;
                binding.inkView.post(() -> {
                    Page page = currentPage();
                    if (binding != null && page != null) {
                        ViewportTransform viewport = binding.inkView.getViewport();
                        viewport.fitPage(page.width, page.height,
                                binding.inkView.getWidth(), binding.inkView.getHeight(), 24f);
                        fitPageScale = viewport.getScale();
                        binding.inkView.invalidate();
                        updateFitPageButton();
                        updateZoomIndicator();
                    }
                });
            }
        }
        if (thumbnailAdapter != null) {
            thumbnailAdapter.setSelectedIndex(currentPageIndex);
        }
    }

    private void addPageAfterCurrent() {
        if (pageController == null) {
            return;
        }
        Page current = currentPage();
        PageBackground.Type template = current != null && current.background != null
                ? current.background.type : PageBackground.Type.BLANK;
        // PDF-derived pages always append blank pages.
        if (template == PageBackground.Type.PDF || template == PageBackground.Type.OFFICE) {
            template = PageBackground.Type.BLANK;
        }
        pageController.addPageAfter(currentPageIndex, template);
        refreshThumbnails();
        goToPage(currentPageIndex + 1);
        onContentEdited();
    }

    private void showPageContextMenu(int index, View anchor) {
        if (pageController == null || document == null) {
            return;
        }
        Page page = document.pages.get(index);
        PopupMenu menu = new PopupMenu(requireContext(), anchor);
        Menu m = menu.getMenu();
        m.add(0, 1, 0, R.string.page_add);
        m.add(0, 2, 1, R.string.page_duplicate);
        m.add(0, 3, 2, R.string.page_move_up).setEnabled(index > 0);
        m.add(0, 4, 3, R.string.page_move_down).setEnabled(index < document.pages.size() - 1);
        m.add(0, 5, 4, page.bookmarked ? R.string.page_bookmark_off : R.string.page_bookmark_on);
        m.add(0, 6, 5, R.string.page_title_edit);
        m.add(0, 9, 6, R.string.page_rotate);
        SubMenu sub = m.addSubMenu(0, 7, 7, R.string.menu_page_template);
        addTemplateItems(sub);
        m.add(0, 8, 8, R.string.page_delete);
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    pageController.addPageAfter(index, PageBackground.Type.BLANK);
                    refreshThumbnails();
                    onContentEdited();
                    return true;
                case 2:
                    pageController.duplicatePage(index);
                    refreshThumbnails();
                    onContentEdited();
                    return true;
                case 3:
                    pageController.movePage(index, index - 1);
                    if (currentPageIndex == index) {
                        currentPageIndex = index - 1;
                    }
                    refreshAfterStructureChange();
                    return true;
                case 4:
                    pageController.movePage(index, index + 1);
                    if (currentPageIndex == index) {
                        currentPageIndex = index + 1;
                    }
                    refreshAfterStructureChange();
                    return true;
                case 5:
                    pageController.toggleBookmark(page);
                    refreshThumbnails();
                    onContentEdited();
                    return true;
                case 6:
                    showPageTitleDialog(page);
                    return true;
                case 9:
                    pageController.rotatePage(page);
                    if (!continuousMode && index == currentPageIndex) {
                        // Re-fit the viewport to the rotated page.
                        needsFit = true;
                        goToPage(currentPageIndex);
                    }
                    refreshInkAll();
                    refreshThumbnails();
                    onContentEdited();
                    return true;
                case 8:
                    if (!pageController.deletePage(index)) {
                        toast(getString(R.string.page_keep_at_least_one));
                    } else {
                        goToPage(Math.min(index, document.pages.size() - 1));
                        refreshAfterStructureChange();
                    }
                    return true;
                default:
                    return handleTemplateItem(item.getItemId(), page);
            }
        });
        menu.show();
    }

    private void refreshAfterStructureChange() {
        if (!continuousMode && binding != null) {
            binding.inkView.setPage(currentPage());
        } else if (continuousAdapter != null) {
            continuousAdapter.notifyDataSetChanged();
        }
        refreshInkAll();
        refreshThumbnails();
        if (thumbnailAdapter != null) {
            thumbnailAdapter.setSelectedIndex(currentPageIndex);
        }
        onContentEdited();
    }

    private void showPageTitleDialog(Page page) {
        EditText input = new EditText(requireContext());
        input.setText(page.title);
        input.setHint(R.string.page_title_edit);
        input.setSingleLine(true);
        FrameLayout container = new FrameLayout(requireContext());
        int pad = dp(20);
        container.setPadding(pad, pad / 2, pad, 0);
        container.addView(input);
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.page_title_edit)
                .setView(container)
                .setPositiveButton(R.string.brush_apply, (d, which) -> {
                    pageController.setPageTitle(page, input.getText().toString().trim());
                    refreshThumbnails();
                    onContentEdited();
                    services.searchIndexer().indexPageTitle(documentId, page.id, page.title);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ------------------------------------------------------------------
    // More menu
    // ------------------------------------------------------------------

    /** Document rename dialog (the title itself lives in the tab strip). */
    private void showRenameDialog() {
        if (document == null) {
            return;
        }
        EditText input = new EditText(requireContext());
        input.setText(document.title);
        input.setHint(R.string.editor_title_hint);
        input.setSingleLine(true);
        input.setSelection(input.getText().length());
        FrameLayout container = new FrameLayout(requireContext());
        int pad = dp(20);
        container.setPadding(pad, pad / 2, pad, 0);
        container.addView(input);
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.menu_rename_document)
                .setView(container)
                .setPositiveButton(R.string.brush_apply, (d, which) -> {
                    renameDocument(input.getText().toString());
                    markDirty();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showMoreMenu() {
        if (document == null) {
            return;
        }
        PopupMoreMenuBinding mb = PopupMoreMenuBinding.inflate(
                LayoutInflater.from(requireContext()));
        // NOTE: explicit width is required — when inflating without a parent
        // the root layout_width is dropped, and WRAP_CONTENT + weighted rows
        // would stretch the panel to full screen width.
        PopupWindow popup = new PopupWindow(mb.getRoot(),
                dp(236), ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setElevation(dp(16));

        // Current state: page mode value.
        mb.txtModeValue.setText(continuousMode
                ? R.string.menu_page_mode_continuous : R.string.menu_page_mode_single);

        mb.rowRename.setOnClickListener(v -> {
            popup.dismiss();
            showRenameDialog();
        });
        mb.rowMode.setOnClickListener(v -> {
            popup.dismiss();
            setContinuousMode(!continuousMode);
        });
        mb.rowTemplate.setOnClickListener(v -> {
            popup.dismiss();
            Page page = currentPage();
            if (page != null) {
                showTemplatePicker(page);
            }
        });
        mb.rowPageTitle.setOnClickListener(v -> {
            popup.dismiss();
            Page page = currentPage();
            if (page != null) {
                showPageTitleDialog(page);
            }
        });
        mb.rowBookmark.setOnClickListener(v -> {
            popup.dismiss();
            Page page = currentPage();
            if (page != null) {
                pageController.toggleBookmark(page);
                refreshThumbnails();
                onContentEdited();
            }
        });
        mb.rowInsertBlank.setOnClickListener(v -> {
            popup.dismiss();
            // Hook for "insert blank page between PDF pages" (spec 26).
            pageController.insertBlankPageAfter(currentPageIndex);
            refreshThumbnails();
            goToPage(currentPageIndex + 1);
            onContentEdited();
        });
        popup.showAsDropDown(binding.btnMore, 0, dp(6), Gravity.END);
    }

    /** Shows the shared template picker and applies the choice to the page. */
    private void showTemplatePicker(Page page) {
        PageBackground.Type current = page.background != null
                ? page.background.type : PageBackground.Type.BLANK;
        TemplatePickerDialog.show(binding.getRoot(), current,
                type -> applyTemplate(page, type));
    }

    private void applyTemplate(Page page, PageBackground.Type type) {
        pageController.setTemplate(page, type);
        refreshInkAll();
        refreshThumbnails();
        onContentEdited();
    }

    /** Adds the template-picker entry to a context-menu submenu. */
    private void addTemplateItems(SubMenu sub) {
        sub.add(0, MENU_TMPL_BASE, 0, R.string.menu_page_template);
    }

    private boolean handleTemplateItem(int itemId, Page page) {
        if (itemId != MENU_TMPL_BASE) {
            return false;
        }
        showTemplatePicker(page);
        return true;
    }

    private void showBlankContextMenu(float viewX, float viewY) {
        List<MenuRowPopup.Item> items = new ArrayList<>();
        items.add(new MenuRowPopup.Item(R.drawable.ic_sel_paste,
                getString(R.string.context_paste), selectionController.hasClipboard(),
                this::pasteFromClipboard));
        items.add(new MenuRowPopup.Item(R.drawable.ic_tool_lasso,
                getString(R.string.context_select_all), true, () -> {
                    Page page = currentPage();
                    if (page != null && !page.strokes.isEmpty()) {
                        LassoEngine.Selection all = new LassoEngine.Selection();
                        all.strokes.addAll(page.strokes);
                        selectionController.setSelection(page, all);
                    }
                }));
        items.add(new MenuRowPopup.Item(R.drawable.ic_aux_image,
                getString(R.string.context_insert_image), true,
                () -> dispatchAuxTool(AuxTool.IMAGE)));
        MenuRowPopup.showAt(binding.inkView, viewX, viewY, items);
    }

    // ------------------------------------------------------------------
    // Page mode switching (single / continuous vertical scroll)
    // ------------------------------------------------------------------

    private boolean continuousScrollListenerAdded;

    private void setContinuousMode(boolean on) {
        if (binding == null || document == null || on == continuousMode) {
            return;
        }
        continuousMode = on;
        selectionController.clear();
        if (on) {
            if (currentTool == EditorTool.ERASER || currentTool == EditorTool.LASSO) {
                selectTool(EditorTool.FOUNTAIN_PEN);
            }
            gestureController.detach();
            binding.inkView.setVisibility(View.GONE);
            binding.continuousList.setVisibility(View.VISIBLE);
            binding.continuousList.setLayoutManager(
                    new LinearLayoutManager(requireContext()));
            continuousAdapter = new PageContinuousAdapter(document.pages,
                    this::onStrokeFinishedShared);
            continuousAdapter.setEraseCallback(continuousEraseCallback);
            BrushPreset preset = presets.get(currentTool);
            if (preset != null) {
                continuousAdapter.setBrush(preset);
            }
            binding.continuousList.setAdapter(continuousAdapter);
            if (!continuousScrollListenerAdded) {
                continuousScrollListenerAdded = true;
                binding.continuousList.addOnScrollListener(new RecyclerView.OnScrollListener() {
                    @Override
                    public void onScrollStateChanged(@NonNull RecyclerView recyclerView,
                                                     int newState) {
                        if (newState == RecyclerView.SCROLL_STATE_IDLE
                                && recyclerView.getLayoutManager() instanceof LinearLayoutManager) {
                            int pos = ((LinearLayoutManager) recyclerView.getLayoutManager())
                                    .findFirstVisibleItemPosition();
                            if (pos >= 0) {
                                currentPageIndex = pos;
                                if (thumbnailAdapter != null) {
                                    thumbnailAdapter.setSelectedIndex(pos);
                                }
                            }
                        }
                    }
                });
            }
            binding.continuousList.scrollToPosition(currentPageIndex);
        } else {
            binding.continuousList.setVisibility(View.GONE);
            binding.inkView.setVisibility(View.VISIBLE);
            if (continuousAdapter != null) {
                continuousAdapter.releaseAll();
                continuousAdapter = null;
            }
            gestureController.attach();
            needsFit = true;
            goToPage(currentPageIndex);
        }
    }

    // ------------------------------------------------------------------
    // Refresh helpers
    // ------------------------------------------------------------------

    private void scheduleContentRefresh() {
        handler.removeCallbacks(contentRefreshRunnable);
        handler.postDelayed(contentRefreshRunnable, CONTENT_REFRESH_MS);
    }

    private void refreshInkNow() {
        handler.removeCallbacks(contentRefreshRunnable);
        if (binding != null && !continuousMode) {
            binding.inkView.refreshCompletedLayer();
        } else if (continuousMode && continuousAdapter != null && eraseTargetPage != null) {
            continuousAdapter.refreshPage(eraseTargetPage.id);
        }
    }

    private void refreshInkAll() {
        if (binding == null) {
            return;
        }
        if (continuousMode && continuousAdapter != null) {
            continuousAdapter.refreshAllPages();
        } else {
            binding.inkView.refreshCompletedLayer();
        }
    }

    private void refreshThumbnails() {
        if (thumbnailAdapter != null) {
            thumbnailAdapter.notifyDataSetChanged();
        }
    }

    // ------------------------------------------------------------------
    // Misc
    // ------------------------------------------------------------------

    private void dismissToolPopup() {
        if (toolPopup != null) {
            toolPopup.dismiss();
            toolPopup = null;
        }
    }

    private void dismissSelectionPopup() {
        if (selectionPopup != null) {
            selectionPopup.dismiss();
            selectionPopup = null;
        }
    }

    private void dismissPopups() {
        dismissToolPopup();
        dismissSelectionPopup();
    }

    private void toast(String message) {
        if (getContext() != null) {
            Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
        }
    }

    private int dp(int value) {
        return BrushPropertiesPopup.dp(requireContext(), value);
    }
}
