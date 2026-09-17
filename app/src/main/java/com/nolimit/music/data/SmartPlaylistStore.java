package com.nolimit.music.data;

import android.content.Context;
import android.content.SharedPreferences;

import com.nolimit.music.model.Track;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class SmartPlaylistStore {
    private static final String PREFS = "smart_playlists";
    private static final String KEY = "rules";
    private final SharedPreferences prefs;
    private final LibraryStore library;

    public static final class Rule {
        public final String id;
        public final String name;
        public final boolean likedOnly;
        public final int minPlays;
        public final String artistContains;
        public final String tagContains;
        public final int notPlayedDays;

        public Rule(String id, String name, boolean likedOnly, int minPlays, String artistContains, String tagContains, int notPlayedDays) {
            this.id = id;
            this.name = name == null || name.trim().isEmpty() ? "스마트 플레이리스트" : name.trim();
            this.likedOnly = likedOnly;
            this.minPlays = Math.max(0, minPlays);
            this.artistContains = artistContains == null ? "" : artistContains.trim();
            this.tagContains = tagContains == null ? "" : tagContains.trim();
            this.notPlayedDays = Math.max(0, notPlayedDays);
        }
    }

    public SmartPlaylistStore(Context context, LibraryStore library) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.library = library;
    }

    public synchronized List<Rule> load() {
        List<Rule> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs.getString(KEY, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new Rule(o.optString("id"), o.optString("name"), o.optBoolean("likedOnly"),
                        o.optInt("minPlays"), o.optString("artistContains"), o.optString("tagContains"), o.optInt("notPlayedDays")));
            }
        } catch (Exception ignored) { }
        return out;
    }

    public synchronized Rule save(String name, boolean likedOnly, int minPlays, String artist, String tag, int notPlayedDays) {
        List<Rule> all = load();
        Rule rule = new Rule(UUID.randomUUID().toString(), name, likedOnly, minPlays, artist, tag, notPlayedDays);
        all.add(0, rule);
        persist(all);
        return rule;
    }

    public synchronized void delete(String id) {
        List<Rule> all = load();
        all.removeIf(r -> r.id.equals(id));
        persist(all);
    }

    public List<Track> evaluate(Rule rule) {
        List<Track> out = new ArrayList<>();
        if (rule == null) return out;
        long cutoff = rule.notPlayedDays > 0 ? System.currentTimeMillis() - rule.notPlayedDays * 86400000L : 0L;
        String artist = rule.artistContains.toLowerCase(Locale.ROOT);
        String tag = rule.tagContains.toLowerCase(Locale.ROOT);
        for (Track t : library.load()) {
            if (rule.likedOnly && !t.liked) continue;
            if (t.playCount < rule.minPlays) continue;
            if (!artist.isEmpty() && !safe(t.artist).contains(artist)) continue;
            if (!tag.isEmpty() && !safe(t.tags).contains(tag)) continue;
            if (rule.notPlayedDays > 0 && t.lastPlayedAt >= cutoff) continue;
            out.add(t);
        }
        return out;
    }

    private void persist(List<Rule> rules) {
        JSONArray a = new JSONArray();
        for (Rule r : rules) {
            try {
                JSONObject o = new JSONObject();
                o.put("id", r.id); o.put("name", r.name); o.put("likedOnly", r.likedOnly); o.put("minPlays", r.minPlays);
                o.put("artistContains", r.artistContains); o.put("tagContains", r.tagContains); o.put("notPlayedDays", r.notPlayedDays);
                a.put(o);
            } catch (Exception ignored) { }
        }
        prefs.edit().putString(KEY, a.toString()).apply();
    }

    private static String safe(String s) { return s == null ? "" : s.toLowerCase(Locale.ROOT); }
}
