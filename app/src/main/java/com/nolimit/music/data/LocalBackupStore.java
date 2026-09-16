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

        JSONObject root = new JSONObject();
        root.put("backupVersion", 1);
        root.put("library", new JSONArray(library.getString("tracks", "[]")));
        root.put("playlists", new JSONArray(playlists.getString("items", "[]")));
        JSONObject settingsObject = new JSONObject();
        settingsObject.put("theme", settings.getString("theme", "dark"));
        settingsObject.put("autoplay", settings.getBoolean("autoplay", true));
        root.put("settings", settingsObject);

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

        context.getSharedPreferences("library", Context.MODE_PRIVATE)
                .edit().putString("tracks", library.toString()).commit();
        context.getSharedPreferences("playlists", Context.MODE_PRIVATE)
                .edit().putString("items", playlists.toString()).commit();

        JSONObject settingsObject = root.optJSONObject("settings");
        if (settingsObject != null) {
            SharedPreferences.Editor editor = context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit();
            if (settingsObject.has("theme")) editor.putString("theme", settingsObject.optString("theme", "dark"));
            if (settingsObject.has("autoplay")) editor.putBoolean("autoplay", settingsObject.optBoolean("autoplay", true));
            editor.commit();
        }
    }
}
