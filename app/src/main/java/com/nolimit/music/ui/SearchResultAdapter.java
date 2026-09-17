package com.nolimit.music.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.nolimit.music.R;
import com.nolimit.music.model.SearchResult;
import com.nolimit.music.util.ArtworkLoader;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class SearchResultAdapter extends RecyclerView.Adapter<SearchResultAdapter.Holder> {
    public interface Listener { void onDownload(SearchResult item, int position); }
    private final Listener listener;
    private final List<SearchResult> items = new ArrayList<>();
    private final Map<String, Integer> progressById = new HashMap<>();

    public SearchResultAdapter(Listener listener) { this.listener = listener; }

    public void submit(List<SearchResult> values) {
        items.clear(); items.addAll(values); notifyDataSetChanged();
    }

    public void setProgress(String id, int progress) {
        if (id == null) return;
        if (progress < 0) progressById.remove(id); else progressById.put(id, Math.max(0, Math.min(100, progress)));
        notifyDataSetChanged();
    }

    public void clearProgress(String id) { if (id != null) progressById.remove(id); notifyDataSetChanged(); }
    public void clearProgress() { progressById.clear(); notifyDataSetChanged(); }

    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_search_result, parent, false); return new Holder(v);
    }

    @Override public void onBindViewHolder(@NonNull Holder h, int position) {
        SearchResult item = items.get(position);
        h.title.setText(item.title);
        h.meta.setText(item.channel + " · " + formatDuration(item.durationSeconds));
        h.badge.setText(item.badge);
        ArtworkLoader.load(h.cover, h.itemView.getContext(), item.id, item.thumbnail);
        Integer p = progressById.get(item.id);
        boolean downloading = p != null;
        h.progress.setVisibility(downloading ? View.VISIBLE : View.GONE);
        h.progress.setProgress(downloading ? p : 0);
        h.action.setVisibility(downloading ? View.GONE : View.VISIBLE);
        h.action.setText("저장");
        h.itemView.setOnClickListener(v -> {
            int pos = h.getBindingAdapterPosition();
            if (!downloading && pos != RecyclerView.NO_POSITION) listener.onDownload(item, pos);
        });
    }

    @Override public int getItemCount() { return items.size(); }

    private static String formatDuration(long seconds) {
        if (seconds <= 0) return "길이 미상";
        long m = seconds / 60; long s = seconds % 60; return String.format(Locale.ROOT, "%d:%02d", m, s);
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final ImageView cover; final TextView title, meta, badge, action; final ProgressBar progress;
        Holder(View v) {
            super(v); cover = v.findViewById(R.id.ivCover); title = v.findViewById(R.id.tvTitle); meta = v.findViewById(R.id.tvMeta);
            badge = v.findViewById(R.id.tvBadge); action = v.findViewById(R.id.tvAction); progress = v.findViewById(R.id.progressDownload);
        }
    }
}
