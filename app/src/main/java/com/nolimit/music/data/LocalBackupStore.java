package com.nolimit.music.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public final class LocalBackupStore {
    private final Context context;

    public LocalBackupStore(Context context) {
        this.context = context.getApplicationContext();
    }

    public void exportTo(Uri uri) throws Exception {
        SharedPreferences library = context.getSharedPreferences("library", Context.MODE_PRIVATE);
        SharedPreferences playlists = context.getSharedPreferences("playlists", Context.MODE_PRIVATE);
        SharedPreferences settings = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
        SharedPreferences history = context.getSharedPreferences("history", Context.MODE_PRIVATE);
        JSONObject root = new JSONObject();
        root.put("backupVersion", 3);
        root.put("library", new JSONArray(library.getString("tracks", "[]")));
        root.put("playlists", new JSONArray(playlists.getString("items", "[]")));
        root.put("history", new JSONArray(history.getString("items", "[]")));
        JSONObject s = new JSONObject();
        s.put("theme", settings.getString("theme", "dark"));
        s.put("autoplay", settings.getBoolean("autoplay", true));
        s.put("allowMobileData", settings.getBoolean("allow_mobile_download", false));
        s.put("smartContinue", settings.getBoolean("smart_continue", true));
        s.put("smoothTransitionMs", settings.getInt("smooth_transition_ms", 0));
        s.put("autoBackup", settings.getBoolean("auto_backup", true));
        s.put("searchSource", settings.getString("search_source", "music_first"));
        s.put("shuffle", settings.getBoolean("shuffle", false));
        s.put("repeatMode", settings.getInt("repeat_mode", 0));
        root.put("settings", s);
        try (OutputStream out = context.getContentResolver().openOutputStream(uri, "wt")) {
            if (out == null) throw new IllegalStateException("백업 파일을 열 수 없습니다.");
            out.write(root.toString(2).getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }

    public void importFrom(Uri uri) throws Exception {
        StringBuilder raw = new StringBuilder();
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IllegalStateException("백업 파일을 읽을 수 없습니다.");
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) raw.append(line);
            }
        }

        JSONObject root = new JSONObject(raw.toString());
        JSONArray library = root.optJSONArray("library");
        JSONArray playlists = root.optJSONArray("playlists");
        if (library == null || playlists == null) throw new IllegalArgumentException("No Limit Music 백업 파일이 아닙니다.");

        context.getSharedPreferences("library", Context.MODE_PRIVATE).edit()
                .putString("tracks", library.toString()).commit();
        context.getSharedPreferences("playlists", Context.MODE_PRIVATE).edit()
                .putString("items", playlists.toString()).commit();

        JSONArray history = root.optJSONArray("history");
        if (history != null) {
            context.getSharedPreferences("history", Context.MODE_PRIVATE).edit()
                    .putString("items", history.toString()).commit();
        }

        JSONObject s = root.optJSONObject("settings");
        if (s != null) {
            SharedPreferences.Editor e = context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit();
            if (s.has("theme")) e.putString("theme", s.optString("theme", "dark"));
            if (s.has("autoplay")) e.putBoolean("autoplay", s.optBoolean("autoplay", true));
            if (s.has("allowMobileData")) e.putBoolean("allow_mobile_download", s.optBoolean("allowMobileData", false));
            if (s.has("smartContinue")) e.putBoolean("smart_continue", s.optBoolean("smartContinue", true));
            if (s.has("smoothTransitionMs")) e.putInt("smooth_transition_ms", s.optInt("smoothTransitionMs", 0));
            if (s.has("autoBackup")) e.putBoolean("auto_backup", s.optBoolean("autoBackup", true));
            if (s.has("searchSource")) e.putString("search_source", s.optString("searchSource", "music_first"));
            if (s.has("shuffle")) e.putBoolean("shuffle", s.optBoolean("shuffle", false));
            if (s.has("repeatMode")) e.putInt("repeat_mode", s.optInt("repeatMode", 0));
            e.commit();
        }

        // If the app was reinstalled, private playback paths from the backup no longer exist.
        // Rehydrate them from the durable Music/No Limit Music mirror when permission is available.
        try { new LibraryStore(context).importSharedMusic(); }
        catch (Exception ignored) { }
    }
}
