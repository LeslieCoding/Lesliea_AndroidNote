package com.leslie.colorosnote.feature.library;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Grid/list adapter for folders, documents, trash entries and tags. Both item
 * layouts expose the same view ids, so a single ViewHolder serves both modes;
 * switching modes swaps the layout manager and rebinds. List updates go through
 * DiffUtil; selection changes rebind directly (they are user-driven, low rate).
 */
final class DocumentAdapter extends ListAdapter<LibraryEntry, DocumentAdapter.VH> {

    interface Callback {
        void onEntryClick(LibraryEntry entry, View anchor);

        void onEntryLongClick(LibraryEntry entry, View anchor);

        void onMoreClick(LibraryEntry entry, View anchor);

        void onSelectionChanged(int count);

        void onThumbnailNeeded(LibraryEntry entry);
    }

    private static final DiffUtil.ItemCallback<LibraryEntry> DIFF =
            new DiffUtil.ItemCallback<LibraryEntry>() {
                @Override
                public boolean areItemsTheSame(@NonNull LibraryEntry a, @NonNull LibraryEntry b) {
                    return a.type == b.type && a.id.equals(b.id);
                }

                @Override
                public boolean areContentsTheSame(@NonNull LibraryEntry a, @NonNull LibraryEntry b) {
                    return a.sameContent(b);
                }
            };

    private final ThumbnailCache thumbCache;
    private final Callback callback;
    private boolean gridMode;
    private boolean selectionMode;
    private final Set<String> selected = new LinkedHashSet<>();

    DocumentAdapter(boolean gridMode, ThumbnailCache thumbCache, Callback callback) {
        super(DIFF);
        this.gridMode = gridMode;
        this.thumbCache = thumbCache;
        this.callback = callback;
        setHasStableIds(false);
    }

    boolean isGridMode() {
        return gridMode;
    }

    void setGridMode(boolean gridMode) {
        if (this.gridMode != gridMode) {
            this.gridMode = gridMode;
            notifyDataSetChanged();
        }
    }

    // ------------------------------------------------------------ selection

    boolean isSelectionMode() {
        return selectionMode;
    }

    void setSelectionMode(boolean on) {
        if (selectionMode == on) {
            return;
        }
        selectionMode = on;
        if (!on) {
            selected.clear();
        }
        notifyDataSetChanged();
    }

    void toggleSelected(String id) {
        if (!selected.remove(id)) {
            selected.add(id);
        }
        notifyItemChanged(indexOf(id));
        callback.onSelectionChanged(selected.size());
    }

    void selectAll() {
        for (LibraryEntry e : getCurrentList()) {
            selected.add(e.id);
        }
        notifyDataSetChanged();
        callback.onSelectionChanged(selected.size());
    }

    List<LibraryEntry> getSelectedEntries() {
        List<LibraryEntry> out = new ArrayList<>();
        for (LibraryEntry e : getCurrentList()) {
            if (selected.contains(e.id)) {
                out.add(e);
            }
        }
        return out;
    }

    int indexOf(String id) {
        List<LibraryEntry> list = getCurrentList();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(id)) {
                return i;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------ binding

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        int layout = gridMode ? R.layout.item_library_grid : R.layout.item_library_list;
        return new VH(LayoutInflater.from(parent.getContext()).inflate(layout, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        LibraryEntry entry = getItem(position);
        Context context = holder.itemView.getContext();

        holder.title.setText(entry.title);
        holder.subtitle.setText(entry.subtitle);

        // Thumbnail or placeholder color block + type icon.
        Bitmap thumb = entry.type == LibraryEntry.TYPE_DOCUMENT
                || entry.type == LibraryEntry.TYPE_TRASH ? thumbCache.get(entry.id) : null;
        if (thumb != null) {
            holder.thumb.setImageBitmap(thumb);
            holder.typeIcon.setVisibility(View.GONE);
        } else {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(placeholderColor(context, entry.id));
            bg.setCornerRadius(8f * context.getResources().getDisplayMetrics().density);
            holder.thumb.setImageDrawable(null);
            holder.thumb.setBackground(bg);
            holder.typeIcon.setVisibility(View.VISIBLE);
            holder.typeIcon.setImageResource(iconFor(entry));
            if (entry.type == LibraryEntry.TYPE_DOCUMENT
                    || entry.type == LibraryEntry.TYPE_TRASH) {
                callback.onThumbnailNeeded(entry);
            }
        }

        // Selection badge.
        if (selectionMode && entry.type != LibraryEntry.TYPE_TAG) {
            holder.check.setVisibility(View.VISIBLE);
            holder.check.setImageResource(selected.contains(entry.id)
                    ? R.drawable.ic_check_circle : R.drawable.ic_circle_outline);
        } else {
            holder.check.setVisibility(View.GONE);
        }

        holder.more.setVisibility(
                selectionMode || entry.type == LibraryEntry.TYPE_TAG ? View.GONE : View.VISIBLE);

        holder.itemView.setOnClickListener(v -> callback.onEntryClick(entry, v));
        holder.itemView.setOnLongClickListener(v -> {
            callback.onEntryLongClick(entry, v);
            return true;
        });
        holder.more.setOnClickListener(v -> callback.onMoreClick(entry, v));
    }

    private static int iconFor(LibraryEntry entry) {
        switch (entry.type) {
            case LibraryEntry.TYPE_FOLDER:
                return R.drawable.ic_folder;
            case LibraryEntry.TYPE_TAG:
                return R.drawable.ic_tag;
            default:
                return iconForDocType(entry.docType);
        }
    }

    static int iconForDocType(String docType) {
        if (docType == null) {
            return R.drawable.ic_note;
        }
        switch (docType) {
            case "INFINITE_CANVAS":
                return R.drawable.ic_infinite;
            case "PDF_IMPORT":
                return R.drawable.ic_pdf;
            case "OFFICE_IMPORT":
                return R.drawable.ic_word;
            case "IMAGE_IMPORT":
                return R.drawable.ic_image;
            case "NOTE":
            default:
                return R.drawable.ic_note;
        }
    }

    private static int placeholderColor(Context context, String id) {
        int[] palette = {
                ContextCompat.getColor(context, R.color.thumb_placeholder_1),
                ContextCompat.getColor(context, R.color.thumb_placeholder_2),
                ContextCompat.getColor(context, R.color.thumb_placeholder_3),
                ContextCompat.getColor(context, R.color.thumb_placeholder_4)};
        return palette[Math.floorMod(id != null ? id.hashCode() : 0, palette.length)];
    }

    static final class VH extends RecyclerView.ViewHolder {
        final ImageView thumb;
        final ImageView typeIcon;
        final ImageView check;
        final TextView title;
        final TextView subtitle;
        final ImageButton more;

        VH(View itemView) {
            super(itemView);
            thumb = itemView.findViewById(R.id.ivThumb);
            typeIcon = itemView.findViewById(R.id.ivTypeIcon);
            check = itemView.findViewById(R.id.ivCheck);
            title = itemView.findViewById(R.id.tvTitle);
            subtitle = itemView.findViewById(R.id.tvSubtitle);
            more = itemView.findViewById(R.id.btnMore);
        }
    }
}
