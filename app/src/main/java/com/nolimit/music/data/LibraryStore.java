package com.nolimit.music.data;

import android.content.Context;
import android.content.SharedPreferences;

import com.nolimit.music.model.Track;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public final class LibraryStore {
    private static final String PREFS = "library";
    private static final String KEY_TRACKS = "tracks";
    private final SharedPreferences prefs;

    public LibraryStore(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized List<Track> load() {
        List<Track> result = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(prefs.getString(KEY_TRACKS, "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.getJSONObject(i);
                Track track = new Track(
                        o.optString("id"),
                        o.optString("title"),
                        o.optString("artist"),
                        o.optString("path"),
                        o.optLong("duration"),
                        o.optLong("addedAt")
                );
                if (new File(track.path).exists()) result.add(track);
            }
        } catch (Exception ignored) { }
        return result;
    }

    public synchronized void upsert(Track track) {
        List<Track> tracks = load();
        tracks.removeIf(t -> t.id.equals(track.id));
        tracks.add(0, track);
        save(tracks);
    }

    public synchronized void remove(String id) {
        List<Track> tracks = load();
        Track target = null;
        for (Track track : tracks) if (track.id.equals(id)) target = track;
        if (target != null) {
            new File(target.path).delete();
            tracks.remove(target);
            save(tracks);
        }
    }

    private void save(List<Track> tracks) {
        JSONArray array = new JSONArray();
        for (Track t : tracks) {
            JSONObject o = new JSONObject();
            try {
                o.put("id", t.id);
                o.put("title", t.title);
                o.put("artist", t.artist);
                o.put("path", t.path);
                o.put("duration", t.durationSeconds);
                o.put("addedAt", t.addedAt);
                array.put(o);
            } catch (Exception ignored) { }
        }
        prefs.edit().putString(KEY_TRACKS, array.toString()).apply();
    }
}
