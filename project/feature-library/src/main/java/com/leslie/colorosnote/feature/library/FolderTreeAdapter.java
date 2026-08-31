package com.leslie.colorosnote.feature.library;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.leslie.colorosnote.core.storage.db.entity.FolderEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Sidebar folder tree: multi-level expand/collapse. Holds the full folder list
 * in memory (grouped by parent) and rebuilds the flattened visible rows on
 * each toggle — folder counts are small, so plain notifyDataSetChanged is fine.
 */
final class FolderTreeAdapter extends RecyclerView.Adapter<FolderTreeAdapter.VH> {

    interface Listener {
        void onFolderClick(FolderEntity folder);
    }

    private static final class Node {
        final FolderEntity folder;
        final int depth;
        final boolean hasChildren;

        Node(FolderEntity folder, int depth, boolean hasChildren) {
            this.folder = folder;
            this.depth = depth;
            this.hasChildren = hasChildren;
        }
    }

    private final Listener listener;
    private final Map<String, List<FolderEntity>> childrenByParent = new HashMap<>();
    private final List<FolderEntity> roots = new ArrayList<>();
    private final List<Node> visible = new ArrayList<>();
    private final Set<String> expanded = new HashSet<>();
    private String selectedFolderId;

    FolderTreeAdapter(Listener listener) {
        this.listener = listener;
    }

    /** Full folder list in any order; regrouped internally. */
    void setFolders(List<FolderEntity> folders) {
        childrenByParent.clear();
        roots.clear();
        if (folders != null) {
            for (FolderEntity f : folders) {
                if (f.parentId == null) {
                    roots.add(f);
                } else {
                    childrenByParent.computeIfAbsent(f.parentId, k -> new ArrayList<>()).add(f);
                }
            }
        }
        rebuildVisible();
    }

    void setSelectedFolderId(String folderId) {
        selectedFolderId = folderId;
        notifyDataSetChanged();
    }

    private void rebuildVisible() {
        visible.clear();
        for (FolderEntity root : roots) {
            append(root, 0);
        }
        notifyDataSetChanged();
    }

    private void append(FolderEntity folder, int depth) {
        List<FolderEntity> children = childrenByParent.get(folder.id);
        boolean hasChildren = children != null && !children.isEmpty();
        visible.add(new Node(folder, depth, hasChildren));
        if (hasChildren && expanded.contains(folder.id)) {
            for (FolderEntity child : children) {
                append(child, depth + 1);
            }
        }
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_folder_tree, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        Node node = visible.get(position);
        float density = holder.itemView.getResources().getDisplayMetrics().density;
        holder.itemView.setPadding((int) ((8 + node.depth * 18) * density),
                holder.itemView.getPaddingTop(),
                holder.itemView.getPaddingRight(),
                holder.itemView.getPaddingBottom());

        holder.expand.setVisibility(node.hasChildren ? View.VISIBLE : View.INVISIBLE);
        holder.expand.setImageResource(expanded.contains(node.folder.id)
                ? R.drawable.ic_expand_more : R.drawable.ic_chevron_right);
        holder.name.setText(node.folder.name);

        boolean selected = node.folder.id.equals(selectedFolderId);
        holder.name.setTextColor(ContextCompat.getColor(holder.itemView.getContext(),
                selected ? R.color.primary_blue : R.color.text_primary));

        holder.itemView.setOnClickListener(v -> listener.onFolderClick(node.folder));
        holder.expand.setOnClickListener(v -> {
            if (!expanded.remove(node.folder.id)) {
                expanded.add(node.folder.id);
            }
            rebuildVisible();
        });
    }

    @Override
    public int getItemCount() {
        return visible.size();
    }

    static final class VH extends RecyclerView.ViewHolder {
        final ImageView expand;
        final TextView name;

        VH(View itemView) {
            super(itemView);
            expand = itemView.findViewById(R.id.ivExpand);
            name = itemView.findViewById(R.id.tvName);
        }
    }
}
