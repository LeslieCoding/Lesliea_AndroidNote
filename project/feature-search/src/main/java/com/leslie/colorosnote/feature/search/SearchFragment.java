package com.leslie.colorosnote.feature.search;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.leslie.colorosnote.core.common.AppExecutors;
import com.leslie.colorosnote.core.common.AppLogger;
import com.leslie.colorosnote.core.search.SearchDocument;
import com.leslie.colorosnote.core.storage.db.InkNoteDatabase;
import com.leslie.colorosnote.core.storage.db.dao.IndexDao;
import com.leslie.colorosnote.core.storage.db.entity.DocumentEntity;
import com.leslie.colorosnote.core.storage.db.entity.FolderEntity;
import com.leslie.colorosnote.feature.search.databinding.FragmentSearchBinding;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Global search UI (spec 22.3): debounced (300ms) cancellable query box,
 * source-type filter chips and a result list. Tap delegates to
 * {@link SearchHost#openResult} — navigation and the in-editor hit highlight
 * are implemented by the editor, not here.
 */
public class SearchFragment extends Fragment {

    private static final String TAG = "Search";
    private static final long DEBOUNCE_MS = 300L;
    private static final int FTS_LIMIT = 200;
    private static final int RESULT_LIMIT = 100;

    private FragmentSearchBinding binding;
    private SearchResultAdapter adapter;
    private InkNoteDatabase database;
    private SearchHost searchHost;

    private final AtomicLong generation = new AtomicLong();
    private Future<?> pendingQuery;
    private Runnable debounceRunnable;
    private final List<SearchResultItem> allResults = new ArrayList<>();
    private String currentQuery = "";

    public static SearchFragment newInstance() {
        return new SearchFragment();
    }

    /** Optional: inject a shared database instance; otherwise one is built lazily. */
    public void setDatabase(InkNoteDatabase database) {
        this.database = database;
    }

    /** Optional explicit host; otherwise the parent fragment / activity is used
     *  when it implements {@link SearchHost}. */
    public void setSearchHost(SearchHost host) {
        this.searchHost = host;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentSearchBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        adapter = new SearchResultAdapter(this::onResultClicked);
        adapter.setHighlightColor(
                ContextCompat.getColor(requireContext(), R.color.search_highlight));
        binding.resultList.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.resultList.setAdapter(adapter);

        binding.searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                onQueryChanged(s == null ? "" : s.toString());
            }
        });
        binding.searchClear.setOnClickListener(v -> binding.searchInput.setText(""));
        binding.filterChips.setOnCheckedStateChangeListener((group, checkedIds) -> applyFilter());
        binding.chipAll.setChecked(true);
        showIdle();
    }

    @Override
    public void onDestroyView() {
        cancelPending();
        if (debounceRunnable != null) {
            AppExecutors.get().mainThread().removeCallbacks(debounceRunnable);
            debounceRunnable = null;
        }
        binding = null;
        super.onDestroyView();
    }

    // -- query pipeline ------------------------------------------------------

    private void onQueryChanged(String text) {
        currentQuery = text;
        binding.searchClear.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
        if (debounceRunnable != null) {
            AppExecutors.get().mainThread().removeCallbacks(debounceRunnable);
        }
        debounceRunnable = () -> runSearch(text);
        AppExecutors.get().mainThread().postDelayed(debounceRunnable, DEBOUNCE_MS);
    }

    /**
     * Debounced entry. Cancellation is two-layered: the previous Future is
     * cancelled and a generation counter discards stale results that were
     * already running (spec: new input cancels the old query).
     */
    private void runSearch(String raw) {
        cancelPending();
        if (SearchQueryBuilder.isBlank(raw)) {
            showIdle();
            return;
        }
        final long gen = generation.incrementAndGet();
        final List<String> terms = SearchQueryBuilder.terms(raw);
        final String ftsQuery = SearchQueryBuilder.buildFtsQuery(raw);
        final InkNoteDatabase db = resolveDatabase();
        if (db == null) {
            showEmpty(raw);
            return;
        }
        pendingQuery = AppExecutors.get().io().submit(() -> {
            List<SearchResultItem> items;
            try {
                List<IndexDao.SearchMatch> matches = db.indexDao().search(ftsQuery, FTS_LIMIT);
                items = SearchResultMerger.merge(matches, null, terms, this::metaFor, RESULT_LIMIT);
            } catch (RuntimeException e) {
                AppLogger.w(TAG, "query failed", e);
                items = new ArrayList<>();
            }
            List<SearchResultItem> finalItems = items;
            AppExecutors.get().mainThread().post(() -> {
                if (gen == generation.get() && binding != null) {
                    allResults.clear();
                    allResults.addAll(finalItems);
                    applyFilter();
                }
            });
        });
    }

    private void cancelPending() {
        generation.incrementAndGet();
        if (pendingQuery != null) {
            pendingQuery.cancel(false);
            pendingQuery = null;
        }
    }

    // -- filtering / presentation --------------------------------------------

    private void applyFilter() {
        if (binding == null) {
            return;
        }
        int chipId = binding.filterChips.getCheckedChipId();
        List<SearchResultItem> filtered = new ArrayList<>();
        for (SearchResultItem item : allResults) {
            if (matchesFilter(chipId, item.sourceType)) {
                filtered.add(item);
            }
        }
        adapter.submit(filtered);
        boolean empty = filtered.isEmpty();
        binding.resultList.setVisibility(empty ? View.GONE : View.VISIBLE);
        binding.emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (empty) {
            binding.emptyText.setText(
                    getString(R.string.search_empty_result, currentQuery));
        }
    }

    /**
     * Chip -> source-type mapping. "笔记" covers document-centric sources
     * (file name, page title, text box, folder, tag); other chips match their
     * single content source.
     */
    private boolean matchesFilter(int chipId, SearchDocument.SourceType type) {
        if (chipId == R.id.chip_notes) {
            return type == SearchDocument.SourceType.FILE_NAME
                    || type == SearchDocument.SourceType.PAGE_TITLE
                    || type == SearchDocument.SourceType.TEXT_BOX
                    || type == SearchDocument.SourceType.FOLDER
                    || type == SearchDocument.SourceType.TAG;
        }
        if (chipId == R.id.chip_pdf) {
            return type == SearchDocument.SourceType.PDF_TEXT;
        }
        if (chipId == R.id.chip_office) {
            return type == SearchDocument.SourceType.OFFICE_TEXT;
        }
        if (chipId == R.id.chip_ocr) {
            return type == SearchDocument.SourceType.OCR;
        }
        if (chipId == R.id.chip_handwriting) {
            return type == SearchDocument.SourceType.HANDWRITING;
        }
        if (chipId == R.id.chip_transcript) {
            return type == SearchDocument.SourceType.TRANSCRIPT;
        }
        return true; // chip_all or no selection
    }

    private void showIdle() {
        allResults.clear();
        adapter.submit(null);
        binding.resultList.setVisibility(View.GONE);
        binding.emptyView.setVisibility(View.VISIBLE);
        binding.emptyText.setText(R.string.search_empty_idle);
    }

    private void showEmpty(String query) {
        allResults.clear();
        adapter.submit(null);
        binding.resultList.setVisibility(View.GONE);
        binding.emptyView.setVisibility(View.VISIBLE);
        binding.emptyText.setText(getString(R.string.search_empty_result, query));
    }

    private void onResultClicked(SearchResultItem item) {
        SearchHost host = resolveHost();
        if (host != null) {
            host.openResult(item.documentId, item.pageId, item.boundingBox);
        } else {
            AppLogger.w(TAG, "no SearchHost; result ignored: doc=" + AppLogger.redact(item.documentId));
        }
    }

    private SearchHost resolveHost() {
        if (searchHost != null) {
            return searchHost;
        }
        if (getParentFragment() instanceof SearchHost) {
            return (SearchHost) getParentFragment();
        }
        if (getActivity() instanceof SearchHost) {
            return (SearchHost) getActivity();
        }
        return null;
    }

    // -- metadata --------------------------------------------------------------

    private SearchResultMerger.DocMeta metaFor(String documentId) {
        try {
            InkNoteDatabase db = resolveDatabase();
            if (db == null) {
                return null;
            }
            DocumentEntity doc = db.documentDao().getById(documentId);
            if (doc == null) {
                return null;
            }
            return new SearchResultMerger.DocMeta(doc.title, folderPath(db, doc.folderId), doc.updatedAt);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Walks the parent chain to build "上级/下级"; cycle-guarded. */
    private String folderPath(InkNoteDatabase db, String folderId) {
        StringBuilder sb = new StringBuilder();
        Set<String> seen = new HashSet<>();
        String id = folderId;
        while (id != null && seen.add(id)) {
            FolderEntity folder = db.folderDao().getById(id);
            if (folder == null) {
                break;
            }
            sb.insert(0, sb.length() == 0 ? folder.name : folder.name + "/");
            id = folder.parentId;
        }
        return sb.toString();
    }

    private InkNoteDatabase resolveDatabase() {
        if (database == null && getContext() != null) {
            database = InkNoteDatabase.build(getContext().getApplicationContext());
        }
        return database;
    }
}
