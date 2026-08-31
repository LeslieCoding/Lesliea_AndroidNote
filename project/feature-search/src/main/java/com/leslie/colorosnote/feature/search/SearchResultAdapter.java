package com.leslie.colorosnote.feature.search;

import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.leslie.colorosnote.core.search.SearchDocument;
import com.leslie.colorosnote.feature.search.databinding.ItemSearchResultBinding;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Result list adapter: type icon + title + highlighted snippet + folder path
 * + update time (spec 22.3).
 */
public final class SearchResultAdapter extends RecyclerView.Adapter<SearchResultAdapter.ViewHolder> {

    public interface OnResultClickListener {
        void onResultClick(SearchResultItem item);
    }

    private final List<SearchResultItem> items = new ArrayList<>();
    private final OnResultClickListener listener;
    private final SimpleDateFormat timeFormat =
            new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
    private int highlightColor = 0x553F6BFF;

    public SearchResultAdapter(OnResultClickListener listener) {
        this.listener = listener;
    }

    public void setHighlightColor(int color) {
        highlightColor = color;
    }

    public void submit(List<SearchResultItem> newItems) {
        items.clear();
        if (newItems != null) {
            items.addAll(newItems);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemSearchResultBinding binding = ItemSearchResultBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new ViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        SearchResultItem item = items.get(position);
        holder.binding.resultIcon.setImageResource(iconFor(item.sourceType));
        String title = item.title.isEmpty() ? item.snippet : item.title;
        holder.binding.resultTitle.setText(title);

        if (item.snippet.isEmpty()) {
            holder.binding.resultSnippet.setVisibility(View.GONE);
        } else {
            holder.binding.resultSnippet.setVisibility(View.VISIBLE);
            SpannableString spannable = new SpannableString(item.snippet);
            for (int[] range : item.highlightRanges) {
                if (range != null && range.length == 2
                        && range[0] >= 0 && range[1] <= spannable.length() && range[0] < range[1]) {
                    spannable.setSpan(new BackgroundColorSpan(highlightColor),
                            range[0], range[1], Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
            holder.binding.resultSnippet.setText(spannable);
        }

        String path = item.folderPath.isEmpty()
                ? holder.itemView.getContext().getString(R.string.search_root_folder)
                : item.folderPath;
        holder.binding.resultPath.setText(path);
        holder.binding.resultTime.setText(
                item.updatedAt > 0 ? timeFormat.format(new Date(item.updatedAt)) : "");
        holder.itemView.setOnClickListener(v -> listener.onResultClick(item));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static int iconFor(SearchDocument.SourceType type) {
        if (type == null) {
            return R.drawable.ic_type_note;
        }
        switch (type) {
            case PDF_TEXT:
                return R.drawable.ic_type_pdf;
            case OFFICE_TEXT:
                return R.drawable.ic_type_office;
            case OCR:
                return R.drawable.ic_type_ocr;
            case HANDWRITING:
                return R.drawable.ic_type_handwriting;
            case TRANSCRIPT:
                return R.drawable.ic_type_transcript;
            case FILE_NAME:
            case PAGE_TITLE:
            case TEXT_BOX:
            case FOLDER:
            case TAG:
            default:
                return R.drawable.ic_type_note;
        }
    }

    static final class ViewHolder extends RecyclerView.ViewHolder {
        final ItemSearchResultBinding binding;

        ViewHolder(ItemSearchResultBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
