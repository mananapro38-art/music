package com.nolimit.music.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.nolimit.music.R;
import com.nolimit.music.model.SearchResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class SearchResultAdapter extends RecyclerView.Adapter<SearchResultAdapter.Holder> {
    public interface Listener { void onDownload(SearchResult item, int position); }
    private final Listener listener;
    private final List<SearchResult> items = new ArrayList<>();
    private String activeId;
    private int activeProgress;

    public SearchResultAdapter(Listener listener) { this.listener = listener; }

    public void submit(List<SearchResult> values) {
        items.clear();
        items.addAll(values);
        notifyDataSetChanged();
    }

    public void setProgress(String id, int progress) {
        activeId = id;
        activeProgress = progress;
        notifyDataSetChanged();
    }

    public void clearProgress() {
        activeId = null;
        activeProgress = 0;
        notifyDataSetChanged();
    }

    @NonNull @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_search_result, parent, false);
        return new Holder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        SearchResult item = items.get(position);
        h.title.setText(item.title);
        h.meta.setText(item.channel + " · " + formatDuration(item.durationSeconds));
        h.badge.setText(item.badge);
        boolean downloading = item.id.equals(activeId);
        h.progress.setVisibility(downloading ? View.VISIBLE : View.GONE);
        h.progress.setProgress(activeProgress);
        h.action.setVisibility(downloading ? View.GONE : View.VISIBLE);
        h.itemView.setOnClickListener(v -> {
            if (activeId == null) listener.onDownload(item, h.getBindingAdapterPosition());
        });
    }

    @Override public int getItemCount() { return items.size(); }

    private static String formatDuration(long seconds) {
        if (seconds <= 0) return "길이 미상";
        long m = seconds / 60;
        long s = seconds % 60;
        return String.format(Locale.ROOT, "%d:%02d", m, s);
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final TextView title, meta, badge, action;
        final ProgressBar progress;
        Holder(View v) {
            super(v);
            title = v.findViewById(R.id.tvTitle);
            meta = v.findViewById(R.id.tvMeta);
            badge = v.findViewById(R.id.tvBadge);
            action = v.findViewById(R.id.tvAction);
            progress = v.findViewById(R.id.progressDownload);
        }
    }
}
