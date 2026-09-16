package com.nolimit.music.data;

import android.content.Context;
import android.content.SharedPreferences;

import com.nolimit.music.model.Playlist;
import com.nolimit.music.model.Track;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public final class PlaylistStore {
    public static final String DEFAULT_ID = "downloads";
    private static final String PREFS = "playlists";
    private static final String KEY = "items";

    private final SharedPreferences prefs;
    private final LibraryStore library;

    public PlaylistStore(Context context, LibraryStore library) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.library = library;
        ensureDefault();
    }

    public synchronized List<Playlist> load() {
        List<Playlist> result = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(prefs.getString(KEY, "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.getJSONObject(i);
                JSONArray ids = o.optJSONArray("trackIds");
                List<String> trackIds = new ArrayList<>();
                if (ids != null) for (int j = 0; j < ids.length(); j++) trackIds.add(ids.optString(j));
                result.add(new Playlist(o.optString("id"), o.optString("name"), trackIds));
            }
        } catch (Exception ignored) { }
        if (result.isEmpty()) result.add(new Playlist(DEFAULT_ID, "내 플레이리스트", new ArrayList<>()));
        return result;
    }

    public synchronized Playlist get(String id) {
        for (Playlist p : load()) if (p.id.equals(id)) return p;
        return null;
    }

    public synchronized Playlist create(String name) {
        String safeName = name == null || name.trim().isEmpty() ? "새 플레이리스트" : name.trim();
        List<Playlist> list = load();
        Playlist playlist = new Playlist(UUID.randomUUID().toString(), safeName, new ArrayList<>());
        list.add(playlist);
        save(list);
        return playlist;
    }

    public synchronized void delete(String id) {
        if (DEFAULT_ID.equals(id)) return;
        List<Playlist> list = load();
        list.removeIf(p -> p.id.equals(id));
        save(list);
    }

    public synchronized void rename(String id, String name) {
        if (name == null || name.trim().isEmpty()) return;
        List<Playlist> list = load();
        for (int i = 0; i < list.size(); i++) {
            Playlist p = list.get(i);
            if (p.id.equals(id)) list.set(i, new Playlist(p.id, name.trim(), p.trackIds));
        }
        save(list);
    }

    public synchronized void addTrack(String playlistId, String trackId) {
        List<Playlist> list = load();
        for (int i = 0; i < list.size(); i++) {
            Playlist p = list.get(i);
            if (!p.id.equals(playlistId)) continue;
            List<String> ids = new ArrayList<>(p.trackIds);
            ids.remove(trackId);
            ids.add(0, trackId);
            list.set(i, new Playlist(p.id, p.name, ids));
            save(list);
            return;
        }
    }

    public synchronized void removeTrack(String playlistId, String trackId) {
        List<Playlist> list = load();
        for (int i = 0; i < list.size(); i++) {
            Playlist p = list.get(i);
            if (!p.id.equals(playlistId)) continue;
            List<String> ids = new ArrayList<>(p.trackIds);
            ids.remove(trackId);
            list.set(i, new Playlist(p.id, p.name, ids));
            save(list);
            return;
        }
    }

    public synchronized void removeTrackEverywhere(String trackId) {
        List<Playlist> list = load();
        for (int i = 0; i < list.size(); i++) {
            Playlist p = list.get(i);
            List<String> ids = new ArrayList<>(p.trackIds);
            ids.removeIf(trackId::equals);
            list.set(i, new Playlist(p.id, p.name, ids));
        }
        save(list);
    }

    public synchronized void move(String playlistId, int from, int to) {
        List<Playlist> list = load();
        for (int i = 0; i < list.size(); i++) {
            Playlist p = list.get(i);
            if (!p.id.equals(playlistId)) continue;
            List<String> ids = new ArrayList<>(p.trackIds);
            if (from < 0 || to < 0 || from >= ids.size() || to >= ids.size()) return;
            Collections.swap(ids, from, to);
            list.set(i, new Playlist(p.id, p.name, ids));
            save(list);
            return;
        }
    }

    public synchronized List<Track> tracks(String playlistId) {
        Playlist playlist = get(playlistId);
        List<Track> result = new ArrayList<>();
        if (playlist == null) return result;
        for (String id : playlist.trackIds) {
            Track track = library.find(id);
            if (track != null) result.add(track);
        }
        return result;
    }

    private synchronized void ensureDefault() {
        List<Playlist> list = loadRaw();
        boolean found = false;
        for (Playlist p : list) if (DEFAULT_ID.equals(p.id)) found = true;
        if (!found) {
            List<String> ids = new ArrayList<>();
            for (Track t : library.load()) ids.add(t.id);
            list.add(0, new Playlist(DEFAULT_ID, "내 플레이리스트", ids));
            save(list);
        }
    }

    private List<Playlist> loadRaw() {
        List<Playlist> result = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(prefs.getString(KEY, "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.getJSONObject(i);
                JSONArray ids = o.optJSONArray("trackIds");
                List<String> trackIds = new ArrayList<>();
                if (ids != null) for (int j = 0; j < ids.length(); j++) trackIds.add(ids.optString(j));
                result.add(new Playlist(o.optString("id"), o.optString("name"), trackIds));
            }
        } catch (Exception ignored) { }
        return result;
    }

    private void save(List<Playlist> playlists) {
        JSONArray array = new JSONArray();
        for (Playlist p : playlists) {
            try {
                JSONObject o = new JSONObject();
                o.put("id", p.id);
                o.put("name", p.name);
                JSONArray ids = new JSONArray();
                for (String id : p.trackIds) ids.put(id);
                o.put("trackIds", ids);
                array.put(o);
            } catch (Exception ignored) { }
        }
        prefs.edit().putString(KEY, array.toString()).apply();
    }
}
