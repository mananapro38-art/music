package com.nolimit.music.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.nolimit.music.R;
import com.nolimit.music.model.Track;

import java.util.ArrayList;
import java.util.List;

public final class PlaylistAdapter extends RecyclerView.Adapter<PlaylistAdapter.Holder> {
    public interface Listener {
        void onPlay(Track track);
        void onRemove(Track track);
    }

    private final Listener listener;
    private final List<Track> items = new ArrayList<>();

    public PlaylistAdapter(Listener listener) { this.listener = listener; }

    public void submit(List<Track> tracks) {
        items.clear();
        items.addAll(tracks);
        notifyDataSetChanged();
    }

    @NonNull @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_track, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        Track track = items.get(position);
        h.title.setText(track.title);
        h.artist.setText(track.artist);
        h.itemView.setOnClickListener(v -> listener.onPlay(track));
        h.remove.setOnClickListener(v -> listener.onRemove(track));
    }

    @Override public int getItemCount() { return items.size(); }

    static final class Holder extends RecyclerView.ViewHolder {
        final TextView title, artist, remove;
        Holder(View v) {
            super(v);
            title = v.findViewById(R.id.tvTrackTitle);
            artist = v.findViewById(R.id.tvTrackArtist);
            remove = v.findViewById(R.id.tvRemove);
        }
    }
}
