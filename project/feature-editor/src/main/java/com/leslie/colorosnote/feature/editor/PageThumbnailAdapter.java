package com.leslie.colorosnote.feature.editor;

import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.leslie.colorosnote.core.model.Page;
import com.leslie.colorosnote.core.render.ThumbnailRenderer;
import com.leslie.colorosnote.feature.editor.databinding.ItemPageThumbnailBinding;

import java.util.List;

/**
 * Bottom page-thumbnail navigation bar (spec 26). Thumbnails render
 * asynchronously on the low-priority background executor via
 * {@link ThumbnailRenderer}; tap switches page, long-press opens the page
 * context menu.
 */
public final class PageThumbnailAdapter
        extends RecyclerView.Adapter<PageThumbnailAdapter.VH> {

    public interface Listener {
        void onPageClick(int index);

        void onPageLongClick(int index, View anchor);
    }

    private final List<Page> pages;
    private final ThumbnailRenderer renderer;
    private final int thumbWidthPx;
    private final Listener listener;
    private int selectedIndex;

    public PageThumbnailAdapter(List<Page> pages, ThumbnailRenderer renderer,
                                int thumbWidthPx, Listener listener) {
        this.pages = pages;
        this.renderer = renderer;
        this.thumbWidthPx = thumbWidthPx;
        this.listener = listener;
    }

    public void setSelectedIndex(int index) {
        int old = selectedIndex;
        selectedIndex = index;
        if (old >= 0 && old < pages.size()) {
            notifyItemChanged(old);
        }
        if (index >= 0 && index < pages.size()) {
            notifyItemChanged(index);
        }
    }

    public int getSelectedIndex() {
        return selectedIndex;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return VH.create(parent);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        Page page = pages.get(position);
        holder.binding.thumbIndex.setText(
                page.title == null || page.title.isEmpty()
                        ? String.valueOf(position + 1) : page.title);
        holder.binding.thumbBookmark.setVisibility(page.bookmarked ? View.VISIBLE : View.GONE);
        holder.binding.thumbSelected.setVisibility(
                position == selectedIndex ? View.VISIBLE : View.GONE);

        Bitmap cached = renderer.getCached(page.id, thumbWidthPx);
        if (cached != null) {
            holder.binding.thumbImage.setImageBitmap(cached);
        } else {
            holder.binding.thumbImage.setImageDrawable(null);
            holder.binding.thumbImage.setTag(page.id);
            renderer.request(page, thumbWidthPx, (pageId, bitmap) -> {
                if (pageId.equals(holder.binding.thumbImage.getTag())) {
                    holder.binding.thumbImage.setImageBitmap(bitmap);
                }
            });
        }

        holder.itemView.setOnClickListener(v -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos != RecyclerView.NO_POSITION) {
                listener.onPageClick(pos);
            }
        });
        holder.itemView.setOnLongClickListener(v -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos != RecyclerView.NO_POSITION) {
                listener.onPageLongClick(pos, v);
                return true;
            }
            return false;
        });
    }

    @Override
    public int getItemCount() {
        return pages.size();
    }

    static final class VH extends RecyclerView.ViewHolder {
        final ItemPageThumbnailBinding binding;

        private VH(ItemPageThumbnailBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        static VH create(ViewGroup parent) {
            return new VH(ItemPageThumbnailBinding.inflate(
                    LayoutInflater.from(parent.getContext()), parent, false));
        }
    }
}
