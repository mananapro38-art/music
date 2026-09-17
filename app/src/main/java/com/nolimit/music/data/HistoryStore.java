package com.nolimit.music.data;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class HistoryStore {
    private static final String PREFS = "history";
    private static final String KEY_ITEMS = "items";
    private static final int MAX_ITEMS = 1000;
    private final SharedPreferences prefs;

    public static final class Entry {
        public final String trackId;
        public final String title;
        public final String artist;
        public final long playedAt;
        public final long listenedMs;

        public Entry(String trackId, String title, String artist, long playedAt, long listenedMs) {
            this.trackId = trackId;
            this.title = title;
            this.artist = artist;
            this.playedAt = playedAt;
            this.listenedMs = listenedMs;
        }
    }

    public HistoryStore(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized void record(String trackId, String title, String artist) {
        List<Entry> items = load();
        items.add(0, new Entry(trackId, title, artist, System.currentTimeMillis(), 0L));
        if (items.size() > MAX_ITEMS) items = new ArrayList<>(items.subList(0, MAX_ITEMS));
        save(items);
    }

    public synchronized void addListeningTime(String trackId, long listenedMs) {
        if (trackId == null || trackId.isEmpty() || listenedMs <= 0) return;
        List<Entry> items = load();
        for (int i = 0; i < items.size(); i++) {
            Entry e = items.get(i);
            if (trackId.equals(e.trackId)) {
                items.set(i, new Entry(e.trackId, e.title, e.artist, e.playedAt, e.listenedMs + listenedMs));
                save(items);
                return;
            }
        }
    }

    public synchronized List<Entry> load() {
        List<Entry> out = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(prefs.getString(KEY_ITEMS, "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.getJSONObject(i);
                out.add(new Entry(o.optString("trackId"), o.optString("title"), o.optString("artist"),
                        o.optLong("playedAt"), o.optLong("listenedMs")));
            }
        } catch (Exception ignored) { }
        return out;
    }

    public synchronized long totalListeningMs() {
        long total = 0L;
        for (Entry e : load()) total += Math.max(0L, e.listenedMs);
        return total;
    }

    public synchronized void clear() {
        prefs.edit().remove(KEY_ITEMS).apply();
    }

    private void save(List<Entry> items) {
        JSONArray array = new JSONArray();
        for (Entry e : items) {
            JSONObject o = new JSONObject();
            try {
                o.put("trackId", e.trackId);
                o.put("title", e.title);
                o.put("artist", e.artist);
                o.put("playedAt", e.playedAt);
                o.put("listenedMs", e.listenedMs);
                array.put(o);
            } catch (Exception ignored) { }
        }
        prefs.edit().putString(KEY_ITEMS, array.toString()).apply();
    }
}
