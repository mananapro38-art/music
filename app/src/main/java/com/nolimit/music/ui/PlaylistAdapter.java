package com.nolimit.music.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.nolimit.music.R;
import com.nolimit.music.model.Track;
import com.nolimit.music.util.ArtworkLoader;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class PlaylistAdapter extends RecyclerView.Adapter<PlaylistAdapter.Holder> {
    public interface Listener {
        void onPlay(Track track);
        void onMore(Track track);
        void onLike(Track track, boolean liked);
        void onMove(int from, int to);
    }

    private final Listener listener;
    private final List<Track> items = new ArrayList<>();

    public PlaylistAdapter(Listener listener) { this.listener = listener; }

    public void submit(List<Track> tracks) {
        items.clear();
        items.addAll(tracks);
        notifyDataSetChanged();
    }

    public List<Track> snapshot() { return new ArrayList<>(items); }

    public boolean moveItem(int from, int to) {
        if (from < 0 || to < 0 || from >= items.size() || to >= items.size()) return false;
        Collections.swap(items, from, to);
        notifyItemMoved(from, to);
        listener.onMove(from, to);
        return true;
    }

    @NonNull @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_track, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        Track track = items.get(position);
        h.title.setText(track.title);
        h.artist.setText(track.artist + (track.playCount > 0 ? " · " + track.playCount + "회 재생" : ""));
        h.like.setText(track.liked ? "♥" : "♡");
        ArtworkLoader.load(h.cover, h.itemView.getContext(), track.id, track.thumbnailUrl);
        h.itemView.setOnClickListener(v -> listener.onPlay(track));
        h.more.setOnClickListener(v -> listener.onMore(track));
        h.like.setOnClickListener(v -> listener.onLike(track, !track.liked));
    }

    @Override public int getItemCount() { return items.size(); }

    static final class Holder extends RecyclerView.ViewHolder {
        final ImageView cover;
        final TextView title, artist, more, like;
        Holder(View v) {
            super(v);
            cover = v.findViewById(R.id.ivTrackArtwork);
            title = v.findViewById(R.id.tvTrackTitle);
            artist = v.findViewById(R.id.tvTrackArtist);
            more = v.findViewById(R.id.tvRemove);
            like = v.findViewById(R.id.tvLike);
        }
    }
}
