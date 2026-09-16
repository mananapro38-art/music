package com.nolimit.music.data;

import android.content.Context;
import android.content.SharedPreferences;

import com.nolimit.music.model.Track;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
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
                result.add(new Track(
                        o.optString("id"),
                        o.optString("title"),
                        o.optString("artist"),
                        o.optString("path"),
                        o.optLong("duration"),
                        o.optLong("addedAt"),
                        o.optBoolean("liked", false),
                        o.optInt("playCount", 0),
                        o.optLong("lastPlayedAt", 0L),
                        o.optString("thumbnail", "")
                ));
            }
        } catch (Exception ignored) { }
        return result;
    }

    public synchronized Track find(String id) {
        for (Track track : load()) if (track.id.equals(id)) return track;
        return null;
    }

    public synchronized void upsert(Track track) {
        List<Track> tracks = load();
        Track previous = null;
        for (Track t : tracks) if (t.id.equals(track.id)) previous = t;
        if (previous != null) {
            tracks.remove(previous);
            String thumbnail = track.thumbnailUrl == null || track.thumbnailUrl.isEmpty()
                    ? previous.thumbnailUrl : track.thumbnailUrl;
            track = new Track(track.id, track.title, track.artist, track.path, track.durationSeconds,
                    previous.addedAt > 0 ? previous.addedAt : track.addedAt,
                    previous.liked, previous.playCount, previous.lastPlayedAt, thumbnail);
        }
        tracks.add(0, track);
        save(tracks);
    }

    public synchronized void setLiked(String id, boolean liked) {
        List<Track> tracks = load();
        for (int i = 0; i < tracks.size(); i++) {
            Track t = tracks.get(i);
            if (t.id.equals(id)) {
                tracks.set(i, t.withLiked(liked));
                save(tracks);
                return;
            }
        }
    }

    public synchronized void incrementPlayCount(String id) {
        List<Track> tracks = load();
        long now = System.currentTimeMillis();
        for (int i = 0; i < tracks.size(); i++) {
            Track t = tracks.get(i);
            if (t.id.equals(id)) {
                tracks.set(i, t.withPlayCount(t.playCount + 1, now));
                save(tracks);
                return;
            }
        }
    }

    public synchronized List<Track> recent(int limit) {
        List<Track> tracks = load();
        tracks.sort((a, b) -> Long.compare(b.addedAt, a.addedAt));
        return take(tracks, limit);
    }

    public synchronized List<Track> liked() {
        List<Track> result = new ArrayList<>();
        for (Track t : load()) if (t.liked) result.add(t);
        result.sort((a, b) -> Long.compare(b.addedAt, a.addedAt));
        return result;
    }

    public synchronized List<Track> mostPlayed(int limit) {
        List<Track> tracks = load();
        tracks.removeIf(t -> t.playCount <= 0);
        tracks.sort(Comparator.comparingInt((Track t) -> t.playCount).reversed()
                .thenComparing(Comparator.comparingLong((Track t) -> t.lastPlayedAt).reversed()));
        return take(tracks, limit);
    }

    public synchronized void remove(String id) {
        List<Track> tracks = load();
        Track target = null;
        for (Track track : tracks) if (track.id.equals(id)) target = track;
        if (target != null) {
            File file = new File(target.path);
            if (file.exists()) file.delete();
            tracks.remove(target);
            save(tracks);
        }
    }

    private static List<Track> take(List<Track> tracks, int limit) {
        if (limit <= 0 || tracks.size() <= limit) return new ArrayList<>(tracks);
        return new ArrayList<>(tracks.subList(0, limit));
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
                o.put("liked", t.liked);
                o.put("playCount", t.playCount);
                o.put("lastPlayedAt", t.lastPlayedAt);
                o.put("thumbnail", t.thumbnailUrl == null ? "" : t.thumbnailUrl);
                array.put(o);
            } catch (Exception ignored) { }
        }
        prefs.edit().putString(KEY_TRACKS, array.toString()).apply();
    }
}
