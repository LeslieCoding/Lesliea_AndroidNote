package com.leslie.colorosnote.feature.library;

import android.content.Context;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.leslie.colorosnote.core.common.AppExecutors;
import com.leslie.colorosnote.core.common.AppLogger;
import com.leslie.colorosnote.core.common.IdGenerator;
import com.leslie.colorosnote.core.model.NoteDocument;
import com.leslie.colorosnote.core.model.PageBackground;
import com.leslie.colorosnote.core.render.TemplatePickerDialog;
import com.leslie.colorosnote.core.storage.db.InkNoteDatabase;
import com.leslie.colorosnote.core.storage.db.entity.DocumentEntity;
import com.leslie.colorosnote.core.storage.db.entity.FavoriteEntity;
import com.leslie.colorosnote.core.storage.db.entity.FolderEntity;
import com.leslie.colorosnote.core.storage.db.entity.TagEntity;
import com.leslie.colorosnote.core.storage.db.entity.TrashEntity;
import com.leslie.colorosnote.core.storage.repository.DocumentRepository;
import com.leslie.colorosnote.core.storage.trash.TrashManager;
import com.leslie.colorosnote.feature.library.databinding.FragmentLibraryBinding;
import com.leslie.colorosnote.feature.search.SearchIndexScheduler;
import com.leslie.colorosnote.feature.settings.SettingsStore;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Library home screen (specs 23/25/28.3): sidebar sections + folder tree on
 * large landscape screens (top-bar popup in portrait), grid/list document
 * listing with persisted view mode and sort, long-press multi-select with
 * batch move/copy/export/delete/tag, breadcrumb folder navigation and the
 * trash view with remaining-days display.
 *
 * <p>All DB/IO work runs on AppExecutors.io(); results are posted to the main
 * thread. The host Activity supplies storage and consumes open/import/search/
 * export intents via {@link LibraryHost}.
 */
public final class LibraryFragment extends Fragment
        implements NewItemBottomSheet.Callback, DocumentAdapter.Callback {

    private static final String TAG = "LibraryFragment";

    private enum Section {ALL, RECENT, FAVORITES, TAGS, FOLDER, TRASH, TAG_FILTER}

    private FragmentLibraryBinding binding;
    private LibraryHost host;
    private DocumentRepository repository;
    private InkNoteDatabase db;
    private SearchIndexScheduler searchIndexer;
    private SettingsStore settings;
    private ThumbnailCache thumbCache;
    private DocumentAdapter adapter;
    private FolderTreeAdapter folderTreeAdapter;

    private Section section = Section.ALL;
    private String currentFolderId;
    private String currentTagId;
    /** Folder path from library root to {@link #currentFolderId}. */
    private final ArrayDeque<FolderEntity> folderPath = new ArrayDeque<>();
    private boolean gridMode = true;

    private SimpleDateFormat timeFormat;

    public static LibraryFragment newInstance() {
        return new LibraryFragment();
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if (!(context instanceof LibraryHost)) {
            throw new IllegalStateException("Host activity must implement LibraryHost");
        }
        host = (LibraryHost) context;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        binding = FragmentLibraryBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        repository = host.documentRepository();
        db = host.database();
        searchIndexer = new SearchIndexScheduler(db.indexDao());
        settings = new SettingsStore(requireContext());
        thumbCache = new ThumbnailCache();
        gridMode = settings.isLibraryGridMode();
        timeFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());

        adapter = new DocumentAdapter(gridMode, thumbCache, this);
        binding.content.recyclerView.setAdapter(adapter);
        applyLayoutManager();

        // Top bar
        binding.content.searchBar.setOnClickListener(v -> host.openSearch());
        binding.content.btnAdd.setOnClickListener(v -> showNewItemSheet());
        binding.content.btnViewMode.setOnClickListener(v -> toggleViewMode());
        binding.content.btnSort.setOnClickListener(this::showSortMenu);
        updateViewModeIcon();

        // Sidebar (large landscape screens) or top-bar nav popup (portrait)
        if (binding.sidebar != null) {
            // Landscape: the hamburger in the content top bar toggles the
            // sidebar — collapse it for full-width content, tap again to
            // bring it back.
            binding.content.btnNav.setVisibility(View.VISIBLE);
            binding.content.btnNav.setOnClickListener(v -> toggleSidebar());
            wireSidebar();
        } else {
            binding.content.btnNav.setOnClickListener(this::showNavPopup);
        }

        wireSelectionBar();
        wireBackNavigation();
        loadFolderTree();
        switchSection(Section.ALL);
    }

    @Override
    public void onResume() {
        super.onResume();
        // Returning from the editor may have changed titles/recents/trash.
        loadFolderTree();
        reload();
    }

    @Override
    public void onDestroyView() {
        if (thumbCache != null) {
            thumbCache.close();
        }
        binding = null;
        super.onDestroyView();
    }

    // ------------------------------------------------------------------
    // Top bar
    // ------------------------------------------------------------------

    private void showNewItemSheet() {
        NewItemBottomSheet sheet = NewItemBottomSheet.newInstance();
        sheet.setCallback(this);
        sheet.show(getChildFragmentManager(), "new_item");
    }

    private void toggleViewMode() {
        gridMode = !gridMode;
        settings.setLibraryGridMode(gridMode);
        adapter.setGridMode(gridMode);
        applyLayoutManager();
        updateViewModeIcon();
    }

    private void applyLayoutManager() {
        if (gridMode) {
            int span = getResources().getConfiguration().screenWidthDp >= 600 ? 4 : 3;
            binding.content.recyclerView.setLayoutManager(new GridLayoutManager(requireContext(), span));
        } else {
            binding.content.recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        }
    }

    private void updateViewModeIcon() {
        binding.content.btnViewMode.setImageResource(
                gridMode ? R.drawable.ic_list_view : R.drawable.ic_grid_view);
    }

    private void showSortMenu(View anchor) {
        // Milky-white custom popup (replaces system-gray PopupMenu).
        com.leslie.colorosnote.feature.library.databinding.PopupLibrarySortBinding b =
                com.leslie.colorosnote.feature.library.databinding.PopupLibrarySortBinding
                        .inflate(LayoutInflater.from(requireContext()));
        String current = settings.getLibrarySort();
        ("name".equals(current) ? b.checkName
                : "created".equals(current) ? b.checkCreated
                : "type".equals(current) ? b.checkType
                : b.checkModified).setVisibility(View.VISIBLE);
        final PopupWindow popup = new PopupWindow(b.getRoot(), dp(200),
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setElevation(dp(16));
        b.sortModified.setOnClickListener(v -> applySort(popup, "modified"));
        b.sortName.setOnClickListener(v -> applySort(popup, "name"));
        b.sortCreated.setOnClickListener(v -> applySort(popup, "created"));
        b.sortType.setOnClickListener(v -> applySort(popup, "type"));
        popup.showAsDropDown(anchor, 0, dp(6));
    }

    private void applySort(PopupWindow popup, String sort) {
        settings.setLibrarySort(sort);
        reload();
        popup.dismiss();
    }

    // ------------------------------------------------------------------
    // Navigation
    // ------------------------------------------------------------------

    private void wireSidebar() {
        binding.sidebar.navAll.setOnClickListener(v -> switchSection(Section.ALL));
        binding.sidebar.navRecent.setOnClickListener(v -> switchSection(Section.RECENT));
        binding.sidebar.navFavorites.setOnClickListener(v -> switchSection(Section.FAVORITES));
        binding.sidebar.navTags.setOnClickListener(v -> switchSection(Section.TAGS));
        binding.sidebar.navTrash.setOnClickListener(v -> switchSection(Section.TRASH));
        folderTreeAdapter = new FolderTreeAdapter(this::enterFolder);
        binding.sidebar.folderTree.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.sidebar.folderTree.setAdapter(folderTreeAdapter);
    }

    /** Hamburger toggle: collapse/expand the landscape sidebar (v2.1.3). */
    private void toggleSidebar() {
        sidebarCollapsed = !sidebarCollapsed;
        if (binding == null || binding.sidebar == null) {
            return;
        }
        binding.sidebar.getRoot().setVisibility(
                sidebarCollapsed ? View.GONE : View.VISIBLE);
        if (binding.sidebarDivider != null) {
            binding.sidebarDivider.setVisibility(
                    sidebarCollapsed ? View.GONE : View.VISIBLE);
        }
    }

    private boolean sidebarCollapsed;

    private void showNavPopup(View anchor) {
        // Milky-white custom popup (replaces system-gray PopupMenu).
        com.leslie.colorosnote.feature.library.databinding.PopupLibraryNavBinding b =
                com.leslie.colorosnote.feature.library.databinding.PopupLibraryNavBinding
                        .inflate(LayoutInflater.from(requireContext()));
        final PopupWindow popup = new PopupWindow(b.getRoot(), dp(232),
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setElevation(dp(16));
        b.navAll.setOnClickListener(v -> {
            switchSection(Section.ALL);
            popup.dismiss();
        });
        b.navRecent.setOnClickListener(v -> {
            switchSection(Section.RECENT);
            popup.dismiss();
        });
        b.navFavorites.setOnClickListener(v -> {
            switchSection(Section.FAVORITES);
            popup.dismiss();
        });
        b.navTags.setOnClickListener(v -> {
            switchSection(Section.TAGS);
            popup.dismiss();
        });
        b.navTrash.setOnClickListener(v -> {
            switchSection(Section.TRASH);
            popup.dismiss();
        });
        popup.showAsDropDown(anchor, 0, dp(6));
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void switchSection(Section target) {
        exitSelectionMode();
        section = target;
        currentTagId = null;
        if (target != Section.FOLDER) {
            currentFolderId = null;
            folderPath.clear();
        }
        updateNavSelection();
        rebuildBreadcrumb();
        reload();
    }

    private void enterFolder(FolderEntity folder) {
        exitSelectionMode();
        section = Section.FOLDER;
        boolean drillDown = folder.parentId == null
                ? currentFolderId == null
                : folder.parentId.equals(currentFolderId);
        if (drillDown) {
            folderPath.addLast(folder);
        } else {
            // Jump via the tree to a non-child: rebuild the path from the root.
            rebuildFolderPath(folder);
        }
        currentFolderId = folder.id;
        if (folderTreeAdapter != null) {
            folderTreeAdapter.setSelectedFolderId(folder.id);
        }
        updateNavSelection();
        rebuildBreadcrumb();
        reload();
    }

    /** Rebuilds folderPath from the root down to (and including) the folder. */
    private void rebuildFolderPath(FolderEntity folder) {
        // Synchronous-ish: runs on io, then swaps state on main.
        String folderId = folder.id;
        AppExecutors.get().io().execute(() -> {
            ArrayDeque<FolderEntity> path = new ArrayDeque<>();
            FolderEntity cursor = folder;
            while (cursor != null) {
                path.addFirst(cursor);
                cursor = cursor.parentId != null ? db.folderDao().getById(cursor.parentId) : null;
            }
            AppExecutors.get().mainThread().post(() -> {
                if (!isAdded() || binding == null || !folderId.equals(currentFolderId)) {
                    return;
                }
                folderPath.clear();
                folderPath.addAll(path);
                rebuildBreadcrumb();
            });
        });
    }

    private void navigateToFolderDepth(int depth) {
        // depth 0 = folder section root, depth N = folderPath element N-1.
        exitSelectionMode();
        section = Section.FOLDER;
        if (depth <= 0) {
            currentFolderId = null;
            folderPath.clear();
        } else {
            FolderEntity target = null;
            int i = 1;
            for (FolderEntity f : new ArrayList<>(folderPath)) {
                if (i == depth) {
                    target = f;
                }
                i++;
            }
            while (folderPath.size() > depth) {
                folderPath.removeLast();
            }
            currentFolderId = target != null ? target.id : null;
        }
        if (folderTreeAdapter != null) {
            folderTreeAdapter.setSelectedFolderId(currentFolderId);
        }
        updateNavSelection();
        rebuildBreadcrumb();
        reload();
    }

    private void updateNavSelection() {
        if (binding == null || binding.sidebar == null) {
            return;
        }
        applyNavState(binding.sidebar.navAll, section == Section.ALL);
        applyNavState(binding.sidebar.navRecent, section == Section.RECENT);
        applyNavState(binding.sidebar.navFavorites, section == Section.FAVORITES);
        applyNavState(binding.sidebar.navTags,
                section == Section.TAGS || section == Section.TAG_FILTER);
        applyNavState(binding.sidebar.navTrash, section == Section.TRASH);
    }

    private void applyNavState(TextView view, boolean active) {
        view.setBackground(ContextCompat.getDrawable(requireContext(), active
                ? R.drawable.bg_nav_item_selected : R.drawable.bg_lib_row_ripple));
        view.setTextColor(ContextCompat.getColor(requireContext(),
                active ? R.color.primary_blue : R.color.text_primary));
    }

    private void rebuildBreadcrumb() {
        boolean show = section == Section.FOLDER && !folderPath.isEmpty()
                || section == Section.TAG_FILTER;
        binding.content.breadcrumbScroll.setVisibility(show ? View.VISIBLE : View.GONE);
        LinearLayout bar = binding.content.breadcrumb;
        bar.removeAllViews();
        if (!show) {
            return;
        }
        if (section == Section.TAG_FILTER) {
            addBreadcrumbLabel(getString(R.string.library_nav_tags), false, null);
            return;
        }
        addBreadcrumbLabel(getString(R.string.library_nav_folders), false,
                v -> navigateToFolderDepth(0));
        int depth = 1;
        for (FolderEntity f : folderPath) {
            addBreadcrumbChevron();
            boolean last = depth == folderPath.size();
            final int targetDepth = depth;
            addBreadcrumbLabel(f.name, last, last ? null : v -> navigateToFolderDepth(targetDepth));
            depth++;
        }
    }

    private void addBreadcrumbChevron() {
        ImageView chevron = new ImageView(requireContext());
        int size = (int) (16 * getResources().getDisplayMetrics().density);
        chevron.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        chevron.setImageResource(R.drawable.ic_chevron_right);
        binding.content.breadcrumb.addView(chevron);
    }

    private void addBreadcrumbLabel(String text, boolean current, View.OnClickListener onClick) {
        TextView label = new TextView(requireContext());
        label.setText(text);
        label.setTextSize(13f);
        label.setTextColor(ContextCompat.getColor(requireContext(),
                current ? R.color.text_primary : R.color.primary_blue));
        int pad = (int) (6 * getResources().getDisplayMetrics().density);
        label.setPadding(pad, pad, pad, pad);
        if (onClick != null) {
            label.setOnClickListener(onClick);
        }
        binding.content.breadcrumb.addView(label);
    }

    // ------------------------------------------------------------------
    // Content loading (all on AppExecutors.io, UI updates on mainThread)
    // ------------------------------------------------------------------

    private void reload() {
        if (binding == null) {
            return;
        }
        final Section snapshotSection = section;
        final String snapshotFolderId = currentFolderId;
        final String snapshotTagId = currentTagId;
        final String sort = settings.getLibrarySort();
        AppExecutors.get().io().execute(() -> {
            List<LibraryEntry> entries = buildEntries(snapshotSection, snapshotFolderId,
                    snapshotTagId, sort);
            AppExecutors.get().mainThread().post(() -> {
                if (!isAdded() || binding == null || section != snapshotSection
                        || !java.util.Objects.equals(currentFolderId, snapshotFolderId)
                        || !java.util.Objects.equals(currentTagId, snapshotTagId)) {
                    return;
                }
                adapter.submitList(entries);
                updateEmptyState(entries.isEmpty());
            });
        });
    }

    /** Runs on the io executor; touches Room DAOs only. */
    private List<LibraryEntry> buildEntries(Section sec, String folderId, String tagId,
            String sort) {
        List<LibraryEntry> entries = new ArrayList<>();
        switch (sec) {
            case ALL:
                addDocuments(entries, db.documentDao().listAllActive(), sort, false);
                break;
            case RECENT:
                addDocuments(entries, db.libraryExtrasDao().listRecent(200), "modified", false);
                break;
            case FAVORITES:
                addDocuments(entries, db.libraryExtrasDao().listFavorites(), sort, false);
                break;
            case TAGS:
                addTags(entries);
                break;
            case TAG_FILTER:
                addDocuments(entries, documentsWithTag(tagId), sort, false);
                break;
            case TRASH:
                addTrash(entries);
                break;
            case FOLDER:
            default:
                for (FolderEntity folder : db.folderDao().listChildren(folderId)) {
                    LibraryEntry e = new LibraryEntry(LibraryEntry.TYPE_FOLDER, folder.id);
                    e.title = folder.name;
                    e.subtitle = formatTime(folder.createdAt);
                    e.createdAt = folder.createdAt;
                    entries.add(e);
                }
                addDocuments(entries, repository.listDocuments(folderId), sort, false);
                break;
        }
        return entries;
    }

    private void addDocuments(List<LibraryEntry> entries, List<DocumentEntity> docs, String sort,
            boolean trash) {
        if (docs == null) {
            return;
        }
        Set<String> favoriteIds = new HashSet<>();
        for (DocumentEntity f : db.libraryExtrasDao().listFavorites()) {
            favoriteIds.add(f.id);
        }
        sortDocuments(docs, sort);
        for (DocumentEntity doc : docs) {
            LibraryEntry e = new LibraryEntry(
                    trash ? LibraryEntry.TYPE_TRASH : LibraryEntry.TYPE_DOCUMENT, doc.id);
            e.title = doc.title;
            e.docType = doc.docType;
            e.updatedAt = doc.updatedAt;
            e.createdAt = doc.createdAt;
            e.favorite = favoriteIds.contains(doc.id);
            e.subtitle = formatTime(doc.updatedAt);
            entries.add(e);
        }
    }

    private void addTags(List<LibraryEntry> entries) {
        List<TagEntity> tags = db.tagDao().listAll();
        // Count documents per tag (no dedicated DAO query yet; acceptable on io).
        Map<String, Integer> counts = new HashMap<>();
        List<DocumentEntity> active = db.documentDao().listAllActive();
        for (DocumentEntity doc : active) {
            for (TagEntity t : db.tagDao().tagsForDocument(doc.id)) {
                counts.merge(t.id, 1, Integer::sum);
            }
        }
        for (TagEntity tag : tags) {
            LibraryEntry e = new LibraryEntry(LibraryEntry.TYPE_TAG, tag.id);
            e.title = tag.name;
            e.subtitle = getString(R.string.library_tag_doc_count,
                    counts.getOrDefault(tag.id, 0));
            e.createdAt = tag.createdAt;
            entries.add(e);
        }
    }

    private List<DocumentEntity> documentsWithTag(String tagId) {
        List<DocumentEntity> out = new ArrayList<>();
        if (tagId == null) {
            return out;
        }
        for (DocumentEntity doc : db.documentDao().listAllActive()) {
            for (TagEntity t : db.tagDao().tagsForDocument(doc.id)) {
                if (t.id.equals(tagId)) {
                    out.add(doc);
                    break;
                }
            }
        }
        return out;
    }

    private void addTrash(List<LibraryEntry> entries) {
        TrashManager trashManager = repository.trashManager();
        for (TrashEntity trashEntry : trashManager.listTrash()) {
            DocumentEntity doc = db.documentDao().getById(trashEntry.documentId);
            if (doc == null) {
                continue;
            }
            LibraryEntry e = new LibraryEntry(LibraryEntry.TYPE_TRASH, doc.id);
            e.title = doc.title;
            e.docType = doc.docType;
            e.updatedAt = doc.updatedAt;
            e.trashDays = trashManager.remainingDays(trashEntry);
            e.subtitle = getString(R.string.library_trash_days, e.trashDays);
            entries.add(e);
        }
    }

    private void sortDocuments(List<DocumentEntity> docs, String sort) {
        Comparator<DocumentEntity> comparator;
        switch (sort) {
            case "name":
                comparator = (a, b) -> a.title.compareToIgnoreCase(b.title);
                break;
            case "created":
                comparator = (a, b) -> Long.compare(b.createdAt, a.createdAt);
                break;
            case "type":
                comparator = Comparator.comparing((DocumentEntity d) -> d.docType)
                        .thenComparing(d -> d.title, String.CASE_INSENSITIVE_ORDER);
                break;
            case "modified":
            default:
                comparator = (a, b) -> Long.compare(b.updatedAt, a.updatedAt);
                break;
        }
        docs.sort(comparator);
    }

    /** SimpleDateFormat is not thread-safe; io tasks may overlap. */
    private synchronized String formatTime(long epochMs) {
        return timeFormat.format(epochMs);
    }

    private void updateEmptyState(boolean empty) {
        binding.content.emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        int text;
        int icon = R.drawable.ic_note;
        switch (section) {
            case RECENT:
                text = R.string.library_empty_recent;
                icon = R.drawable.ic_recent;
                break;
            case FAVORITES:
                text = R.string.library_empty_favorites;
                icon = R.drawable.ic_favorite;
                break;
            case TAGS:
            case TAG_FILTER:
                text = R.string.library_empty_tags;
                icon = R.drawable.ic_tag;
                break;
            case TRASH:
                text = R.string.library_empty_trash;
                icon = R.drawable.ic_trash;
                break;
            case FOLDER:
                text = R.string.library_empty_folder;
                icon = R.drawable.ic_folder;
                break;
            case ALL:
            default:
                text = R.string.library_empty_all;
                break;
        }
        binding.content.emptyText.setText(text);
        binding.content.emptyIcon.setImageResource(icon);
    }

    // ------------------------------------------------------------------
    // Folder tree
    // ------------------------------------------------------------------

    private void loadFolderTree() {
        AppExecutors.get().io().execute(() -> {
            List<FolderEntity> all = listAllFolders();
            AppExecutors.get().mainThread().post(() -> {
                if (!isAdded() || binding == null || folderTreeAdapter == null) {
                    return;
                }
                folderTreeAdapter.setFolders(all);
            });
        });
    }

    /** Depth-first list of every folder, parents before children (io thread). */
    private List<FolderEntity> listAllFolders() {
        List<FolderEntity> out = new ArrayList<>();
        collectFolders(null, out);
        return out;
    }

    private void collectFolders(String parentId, List<FolderEntity> out) {
        for (FolderEntity child : db.folderDao().listChildren(parentId)) {
            out.add(child);
            collectFolders(child.id, out);
        }
    }

    // ------------------------------------------------------------------
    // DocumentAdapter.Callback
    // ------------------------------------------------------------------

    @Override
    public void onEntryClick(LibraryEntry entry, View anchor) {
        if (adapter.isSelectionMode()) {
            if (entry.type != LibraryEntry.TYPE_TAG) {
                adapter.toggleSelected(entry.id);
            }
            return;
        }
        switch (entry.type) {
            case LibraryEntry.TYPE_DOCUMENT:
                host.openDocument(entry.id);
                break;
            case LibraryEntry.TYPE_FOLDER:
                openFolderById(entry.id);
                break;
            case LibraryEntry.TYPE_TAG:
                section = Section.TAG_FILTER;
                currentTagId = entry.id;
                updateNavSelection();
                rebuildBreadcrumb();
                reload();
                break;
            case LibraryEntry.TYPE_TRASH:
                showTrashPopup(entry, anchor);
                break;
            default:
                break;
        }
    }

    private void openFolderById(String folderId) {
        AppExecutors.get().io().execute(() -> {
            FolderEntity folder = db.folderDao().getById(folderId);
            if (folder != null) {
                AppExecutors.get().mainThread().post(() -> {
                    if (isAdded() && binding != null) {
                        enterFolder(folder);
                    }
                });
            }
        });
    }

    @Override
    public void onEntryLongClick(LibraryEntry entry, View anchor) {
        if (adapter.isSelectionMode() || section == Section.TAGS
                || entry.type == LibraryEntry.TYPE_TAG) {
            return;
        }
        adapter.setSelectionMode(true);
        updateSelectionBarVisibility();
        adapter.toggleSelected(entry.id);
    }

    @Override
    public void onMoreClick(LibraryEntry entry, View anchor) {
        switch (entry.type) {
            case LibraryEntry.TYPE_DOCUMENT:
                showDocumentPopup(entry, anchor);
                break;
            case LibraryEntry.TYPE_FOLDER:
                showFolderPopup(entry, anchor);
                break;
            case LibraryEntry.TYPE_TRASH:
                showTrashPopup(entry, anchor);
                break;
            default:
                break;
        }
    }

    @Override
    public void onSelectionChanged(int count) {
        if (binding == null) {
            return;
        }
        if (count == 0 && adapter.isSelectionMode()) {
            exitSelectionMode();
            return;
        }
        binding.content.selectedCount.setText(getString(R.string.library_selected_count, count));
    }

    @Override
    public void onThumbnailNeeded(LibraryEntry entry) {
        thumbCache.load(entry.id, repository.fileFor(entry.id), documentId -> {
            if (binding != null) {
                int index = adapter.indexOf(documentId);
                if (index >= 0) {
                    adapter.notifyItemChanged(index);
                }
            }
        });
    }

    // ------------------------------------------------------------------
    // Selection mode + batch operations
    // ------------------------------------------------------------------

    private void wireSelectionBar() {
        binding.content.btnSelectAll.setOnClickListener(v -> adapter.selectAll());
        binding.content.btnCancelSelection.setOnClickListener(v -> exitSelectionMode());
        binding.content.btnMove.setOnClickListener(v ->
                showMoveDialog(selectedOfType(LibraryEntry.TYPE_DOCUMENT),
                        selectedOfType(LibraryEntry.TYPE_FOLDER)));
        binding.content.btnCopy.setOnClickListener(v ->
                duplicateDocuments(selectedOfType(LibraryEntry.TYPE_DOCUMENT)));
        binding.content.btnExport.setOnClickListener(v -> {
            List<String> docIds = idsOf(selectedOfType(LibraryEntry.TYPE_DOCUMENT));
            if (!docIds.isEmpty()) {
                host.exportDocuments(docIds);
            }
            exitSelectionMode();
        });
        binding.content.btnTag.setOnClickListener(v ->
                showTagDialog(idsOf(selectedOfType(LibraryEntry.TYPE_DOCUMENT))));
        binding.content.btnDelete.setOnClickListener(v -> {
            if (section == Section.TRASH) {
                confirmDeleteForever(idsOf(adapter.getSelectedEntries()));
            } else {
                confirmMoveToTrash();
            }
        });
        binding.content.btnRestore.setOnClickListener(v ->
                restoreDocuments(idsOf(adapter.getSelectedEntries())));
    }

    private List<LibraryEntry> selectedOfType(int type) {
        List<LibraryEntry> out = new ArrayList<>();
        for (LibraryEntry e : adapter.getSelectedEntries()) {
            if (e.type == type) {
                out.add(e);
            }
        }
        return out;
    }

    private static List<String> idsOf(List<LibraryEntry> entries) {
        List<String> ids = new ArrayList<>(entries.size());
        for (LibraryEntry e : entries) {
            ids.add(e.id);
        }
        return ids;
    }

    private void exitSelectionMode() {
        if (adapter != null && adapter.isSelectionMode()) {
            adapter.setSelectionMode(false);
        }
        updateSelectionBarVisibility();
    }

    private void updateSelectionBarVisibility() {
        if (binding == null) {
            return;
        }
        boolean visible = adapter.isSelectionMode();
        binding.content.selectionBar.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (visible) {
            boolean trash = section == Section.TRASH;
            binding.content.btnMove.setVisibility(trash ? View.GONE : View.VISIBLE);
            binding.content.btnCopy.setVisibility(trash ? View.GONE : View.VISIBLE);
            binding.content.btnExport.setVisibility(trash ? View.GONE : View.VISIBLE);
            binding.content.btnTag.setVisibility(trash ? View.GONE : View.VISIBLE);
            binding.content.btnRestore.setVisibility(trash ? View.VISIBLE : View.GONE);
            binding.content.btnDelete.setText(trash
                    ? R.string.library_action_delete_forever : R.string.library_action_delete);
        }
    }

    private void confirmMoveToTrash() {
        List<String> docIds = idsOf(selectedOfType(LibraryEntry.TYPE_DOCUMENT));
        List<String> folderIds = idsOf(selectedOfType(LibraryEntry.TYPE_FOLDER));
        if (docIds.isEmpty() && folderIds.isEmpty()) {
            return;
        }
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.library_dialog_delete_title)
                .setMessage(R.string.library_dialog_delete_message)
                .setPositiveButton(R.string.library_dialog_delete_title, (d, w) -> {
                    AppExecutors.get().io().execute(() -> {
                        for (String id : docIds) {
                            repository.moveToTrash(id);
                        }
                        for (String id : folderIds) {
                            db.folderDao().deleteById(id);
                        }
                        postReload();
                        loadFolderTree();
                    });
                    exitSelectionMode();
                })
                .setNegativeButton(R.string.library_cancel, null)
                .show();
    }

    private void restoreDocuments(List<String> docIds) {
        AppExecutors.get().io().execute(() -> {
            for (String id : docIds) {
                repository.restore(id);
            }
            postReload();
        });
        exitSelectionMode();
    }

    private void confirmDeleteForever(List<String> docIds) {
        if (docIds.isEmpty()) {
            return;
        }
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.library_action_delete_forever)
                .setMessage(R.string.library_dialog_delete_forever_message)
                .setPositiveButton(R.string.library_action_delete_forever, (d, w) -> {
                    AppExecutors.get().io().execute(() -> {
                        for (String id : docIds) {
                            repository.deleteForever(id);
                        }
                        postReload();
                    });
                    exitSelectionMode();
                })
                .setNegativeButton(R.string.library_cancel, null)
                .show();
    }

    private void duplicateDocuments(List<LibraryEntry> docs) {
        if (docs.isEmpty()) {
            return;
        }
        AppExecutors.get().io().execute(() -> {
            for (LibraryEntry e : docs) {
                try {
                    repository.duplicateDocument(e.id);
                } catch (IOException ex) {
                    AppLogger.e(TAG, "duplicate failed for " + e.id, ex);
                }
            }
            postReload();
        });
        exitSelectionMode();
    }

    private void postReload() {
        AppExecutors.get().mainThread().post(() -> {
            if (isAdded() && binding != null) {
                reload();
            }
        });
    }

    // ------------------------------------------------------------------
    // Popup menus
    // ------------------------------------------------------------------

    private void showDocumentPopup(LibraryEntry entry, View anchor) {
        PopupMenu menu = new PopupMenu(requireContext(), anchor);
        menu.inflate(R.menu.library_doc_popup);
        menu.getMenu().findItem(R.id.action_favorite).setTitle(entry.favorite
                ? R.string.library_action_unfavorite : R.string.library_action_favorite);
        menu.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.action_open) {
                host.openDocument(entry.id);
            } else if (id == R.id.action_rename) {
                showRenameDialog(entry);
            } else if (id == R.id.action_move) {
                List<LibraryEntry> single = new ArrayList<>();
                single.add(entry);
                showMoveDialog(single, new ArrayList<>());
            } else if (id == R.id.action_copy) {
                List<LibraryEntry> single = new ArrayList<>();
                single.add(entry);
                duplicateDocuments(single);
            } else if (id == R.id.action_favorite) {
                toggleFavorite(entry);
            } else if (id == R.id.action_tag) {
                List<String> single = new ArrayList<>();
                single.add(entry.id);
                showTagDialog(single);
            } else if (id == R.id.action_export) {
                List<String> single = new ArrayList<>();
                single.add(entry.id);
                host.exportDocuments(single);
            } else if (id == R.id.action_delete) {
                List<String> single = new ArrayList<>();
                single.add(entry.id);
                new AlertDialog.Builder(requireContext())
                        .setTitle(R.string.library_dialog_delete_title)
                        .setMessage(R.string.library_dialog_delete_message)
                        .setPositiveButton(R.string.library_dialog_delete_title, (d, w) ->
                                AppExecutors.get().io().execute(() -> {
                                    repository.moveToTrash(entry.id);
                                    postReload();
                                }))
                        .setNegativeButton(R.string.library_cancel, null)
                        .show();
            }
            return true;
        });
        menu.show();
    }

    private void toggleFavorite(LibraryEntry entry) {
        AppExecutors.get().io().execute(() -> {
            if (db.libraryExtrasDao().isFavorite(entry.id)) {
                db.libraryExtrasDao().removeFavorite(entry.id);
            } else {
                FavoriteEntity favorite = new FavoriteEntity(entry.id);
                favorite.addedAt = System.currentTimeMillis();
                db.libraryExtrasDao().addFavorite(favorite);
            }
            postReload();
        });
    }

    private void showFolderPopup(LibraryEntry entry, View anchor) {
        PopupMenu menu = new PopupMenu(requireContext(), anchor);
        menu.inflate(R.menu.library_folder_popup);
        menu.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.action_open) {
                openFolderById(entry.id);
            } else if (id == R.id.action_rename) {
                showRenameDialog(entry);
            } else if (id == R.id.action_delete) {
                new AlertDialog.Builder(requireContext())
                        .setTitle(R.string.library_dialog_delete_title)
                        .setMessage(R.string.library_dialog_delete_folder_message)
                        .setPositiveButton(R.string.library_dialog_delete_title, (d, w) ->
                                AppExecutors.get().io().execute(() -> {
                                    db.folderDao().deleteById(entry.id);
                                    postReload();
                                    loadFolderTree();
                                }))
                        .setNegativeButton(R.string.library_cancel, null)
                        .show();
            }
            return true;
        });
        menu.show();
    }

    private void showTrashPopup(LibraryEntry entry, View anchor) {
        PopupMenu menu = new PopupMenu(requireContext(), anchor);
        menu.inflate(R.menu.library_trash_popup);
        menu.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.action_restore) {
                List<String> single = new ArrayList<>();
                single.add(entry.id);
                restoreDocuments(single);
            } else if (id == R.id.action_delete_forever) {
                List<String> single = new ArrayList<>();
                single.add(entry.id);
                confirmDeleteForever(single);
            }
            return true;
        });
        menu.show();
    }

    // ------------------------------------------------------------------
    // Dialogs
    // ------------------------------------------------------------------

    private void showRenameDialog(LibraryEntry entry) {
        EditText input = new EditText(requireContext());
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setText(entry.title);
        input.setSelection(entry.title.length());
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        input.setPadding(pad, pad / 2, pad, 0);
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.library_dialog_rename_title)
                .setView(input)
                .setPositiveButton(R.string.library_confirm, (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) {
                        return;
                    }
                    AppExecutors.get().io().execute(() -> {
                        if (entry.type == LibraryEntry.TYPE_FOLDER) {
                            FolderEntity folder = db.folderDao().getById(entry.id);
                            if (folder != null) {
                                folder.name = name;
                                db.folderDao().upsert(folder);
                            }
                            loadFolderTree();
                        } else {
                            db.documentDao().rename(entry.id, name, System.currentTimeMillis());
                        }
                        postReload();
                    });
                })
                .setNegativeButton(R.string.library_cancel, null)
                .show();
    }

    private void showMoveDialog(List<LibraryEntry> docs, List<LibraryEntry> folders) {
        if (docs.isEmpty() && folders.isEmpty()) {
            return;
        }
        Set<String> movingFolderIds = new HashSet<>(idsOf(folders));
        AppExecutors.get().io().execute(() -> {
            List<FolderEntity> all = listAllFolders();
            AppExecutors.get().mainThread().post(() -> {
                if (!isAdded()) {
                    return;
                }
                List<String> labels = new ArrayList<>();
                List<String> targetIds = new ArrayList<>();
                labels.add(getString(R.string.library_dialog_root_folder));
                targetIds.add(null);
                Map<String, Integer> depths = new HashMap<>();
                for (FolderEntity f : all) {
                    if (movingFolderIds.contains(f.id)) {
                        continue; // cannot move a folder into itself
                    }
                    int depth = f.parentId == null ? 0
                            : depths.getOrDefault(f.parentId, 0) + 1;
                    depths.put(f.id, depth);
                    StringBuilder indent = new StringBuilder();
                    for (int i = 0; i < depth; i++) {
                        indent.append("　");
                    }
                    labels.add(indent + f.name);
                    targetIds.add(f.id);
                }
                new AlertDialog.Builder(requireContext())
                        .setTitle(R.string.library_dialog_move_title)
                        .setItems(labels.toArray(new CharSequence[0]), (d, which) -> {
                            String targetId = targetIds.get(which);
                            AppExecutors.get().io().execute(() -> {
                                long now = System.currentTimeMillis();
                                for (LibraryEntry doc : docs) {
                                    db.documentDao().moveToFolder(doc.id, targetId, now);
                                }
                                for (LibraryEntry folder : folders) {
                                    FolderEntity entity = db.folderDao().getById(folder.id);
                                    if (entity != null) {
                                        entity.parentId = targetId;
                                        db.folderDao().upsert(entity);
                                    }
                                }
                                postReload();
                                loadFolderTree();
                            });
                            exitSelectionMode();
                        })
                        .setNegativeButton(R.string.library_cancel, null)
                        .show();
            });
        });
    }

    private void showTagDialog(List<String> documentIds) {
        if (documentIds.isEmpty()) {
            return;
        }
        AppExecutors.get().io().execute(() -> {
            List<TagEntity> tags = db.tagDao().listAll();
            AppExecutors.get().mainThread().post(() -> {
                if (!isAdded()) {
                    return;
                }
                String[] names = new String[tags.size()];
                boolean[] checked = new boolean[tags.size()];
                for (int i = 0; i < tags.size(); i++) {
                    names[i] = tags.get(i).name;
                }
                Set<Integer> chosen = new HashSet<>();
                AlertDialog dialog = new AlertDialog.Builder(requireContext())
                        .setTitle(R.string.library_dialog_tag_title)
                        .setMultiChoiceItems(names, checked, (d, which, isChecked) -> {
                            if (isChecked) {
                                chosen.add(which);
                            } else {
                                chosen.remove(which);
                            }
                        })
                        .setPositiveButton(R.string.library_confirm, (d, w) -> {
                            AppExecutors.get().io().execute(() -> {
                                List<String> tagIds = new ArrayList<>();
                                for (int index : chosen) {
                                    tagIds.add(tags.get(index).id);
                                }
                                for (String docId : documentIds) {
                                    List<String> existing = new ArrayList<>();
                                    for (TagEntity t : db.tagDao().tagsForDocument(docId)) {
                                        existing.add(t.id);
                                    }
                                    for (String tagId : tagIds) {
                                        if (!existing.contains(tagId)) {
                                            existing.add(tagId);
                                        }
                                    }
                                    db.tagDao().setTagsForDocument(docId, existing);
                                }
                                postReload();
                            });
                        })
                        .setNeutralButton(R.string.library_dialog_new_tag, null)
                        .setNegativeButton(R.string.library_cancel, null)
                        .show();
                // Neutral button without auto-dismiss: create tag, then re-open.
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                    dialog.dismiss();
                    showNewTagDialog(documentIds);
                });
            });
        });
    }

    private void showNewTagDialog(List<String> documentIds) {
        EditText input = new EditText(requireContext());
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint(R.string.library_dialog_name_hint);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        input.setPadding(pad, pad / 2, pad, 0);
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.library_dialog_new_tag)
                .setView(input)
                .setPositiveButton(R.string.library_confirm, (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) {
                        return;
                    }
                    AppExecutors.get().io().execute(() -> {
                        TagEntity tag = new TagEntity(IdGenerator.newId());
                        tag.name = name;
                        tag.createdAt = System.currentTimeMillis();
                        db.tagDao().upsert(tag);
                        List<String> tagId = new ArrayList<>();
                        tagId.add(tag.id);
                        for (String docId : documentIds) {
                            List<String> existing = new ArrayList<>();
                            for (TagEntity t : db.tagDao().tagsForDocument(docId)) {
                                existing.add(t.id);
                            }
                            if (!existing.contains(tag.id)) {
                                existing.add(tag.id);
                            }
                            db.tagDao().setTagsForDocument(docId, existing);
                        }
                        postReload();
                    });
                })
                .setNegativeButton(R.string.library_cancel, null)
                .show();
    }

    // ------------------------------------------------------------------
    // NewItemBottomSheet.Callback (spec 24)
    // ------------------------------------------------------------------

    /** Folder receiving newly created notes; root outside the folder section. */
    private String targetFolderId() {
        return section == Section.FOLDER ? currentFolderId : null;
    }

    @Override
    public void onCreateBlankNote() {
        repository.createDocumentAsync(getString(R.string.library_default_note_title),
                targetFolderId(), result -> AppExecutors.get().mainThread().post(() -> {
                    if (!isAdded() || binding == null) {
                        return;
                    }
                    NoteDocument doc = result.getOrNull();
                    if (doc != null) {
                        searchIndexer.indexFileName(doc.id, doc.title);
                        reload();
                        host.openDocument(doc.id);
                    } else {
                        showError(result.errorOrNull());
                    }
                }));
    }

    @Override
    public void onCreateTemplateNote() {
        if (binding == null) {
            return;
        }
        // Shared picker (core-render): live previews of all page templates.
        TemplatePickerDialog.show(binding.getRoot(), PageBackground.Type.BLANK,
                type -> createNoteWithBackground(type, false));
    }

    private void createNoteWithBackground(PageBackground.Type type, boolean infiniteCanvas) {
        AppExecutors.get().io().execute(() -> {
            try {
                String title = getString(infiniteCanvas
                        ? R.string.library_default_canvas_title
                        : R.string.library_default_note_title);
                NoteDocument doc = repository.createDocument(title, targetFolderId());
                if (infiniteCanvas) {
                    doc.docType = NoteDocument.DocType.INFINITE_CANVAS;
                } else if (type != PageBackground.Type.BLANK && !doc.pages.isEmpty()) {
                    doc.pages.get(0).background = PageBackground.ofType(type);
                }
                repository.saveDocument(doc);
                searchIndexer.indexFileName(doc.id, doc.title);
                AppExecutors.get().mainThread().post(() -> {
                    if (isAdded() && binding != null) {
                        reload();
                        host.openDocument(doc.id);
                    }
                });
            } catch (IOException | RuntimeException e) {
                showError(e);
            }
        });
    }

    @Override
    public void onCreateFolder() {
        EditText input = new EditText(requireContext());
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setText(R.string.library_default_folder_name);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        input.setPadding(pad, pad / 2, pad, 0);
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.library_dialog_new_folder_title)
                .setView(input)
                .setPositiveButton(R.string.library_confirm, (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) {
                        return;
                    }
                    AppExecutors.get().io().execute(() -> {
                        FolderEntity folder = new FolderEntity(IdGenerator.newId());
                        folder.name = name;
                        folder.parentId = targetFolderId();
                        folder.createdAt = System.currentTimeMillis();
                        db.folderDao().upsert(folder);
                        postReload();
                        loadFolderTree();
                    });
                })
                .setNegativeButton(R.string.library_cancel, null)
                .show();
    }

    @Override
    public void onCreateInfiniteCanvas() {
        createNoteWithBackground(PageBackground.Type.BLANK, true);
    }

    @Override
    public void onImport(String importType) {
        host.startImport(importType);
    }

    // ------------------------------------------------------------------
    // Misc
    // ------------------------------------------------------------------

    private void wireBackNavigation() {
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(),
                new OnBackPressedCallback(true) {
                    @Override
                    public void handleOnBackPressed() {
                        if (adapter.isSelectionMode()) {
                            exitSelectionMode();
                        } else if (section == Section.FOLDER && !folderPath.isEmpty()) {
                            navigateToFolderDepth(folderPath.size() - 1);
                        } else if (section != Section.ALL) {
                            switchSection(Section.ALL);
                        } else {
                            setEnabled(false);
                            requireActivity().getOnBackPressedDispatcher().onBackPressed();
                        }
                    }
                });
    }

    private void showError(Throwable error) {
        AppLogger.e(TAG, "operation failed", error);
        AppExecutors.get().mainThread().post(() -> {
            if (isAdded()) {
                Toast.makeText(requireContext(),
                        error != null ? error.getMessage() : "error", Toast.LENGTH_SHORT).show();
            }
        });
    }
}
