package com.example.mediacollector;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class LibraryRepository {
    private static final String PREF = "media_collector_library";
    private static final String KEY = "items";
    private final SharedPreferences prefs;

    public LibraryRepository(Context context) {
        prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public synchronized List<LibraryItem> all() {
        ArrayList<LibraryItem> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs.getString(KEY, "[]"));
            for (int n = 0; n < arr.length(); n++) {
                JSONObject o = arr.optJSONObject(n);
                if (o != null) out.add(LibraryItem.fromJson(o));
            }
        } catch (Exception ignored) {}
        Collections.sort(out, (a, b) -> Long.compare(b.savedAt, a.savedAt));
        return out;
    }

    public synchronized void upsert(LibraryItem item) {
        List<LibraryItem> items = all();
        boolean replaced = false;
        for (int n = 0; n < items.size(); n++) {
            if (items.get(n).id.equals(item.id)) {
                items.set(n, item);
                replaced = true;
                break;
            }
        }
        if (!replaced) items.add(item);
        JSONArray arr = new JSONArray();
        for (LibraryItem i : items) {
            try { arr.put(i.toJson()); } catch (Exception ignored) {}
        }
        prefs.edit().putString(KEY, arr.toString()).apply();
    }

    public synchronized void remove(String id) {
        List<LibraryItem> items = all();
        JSONArray arr = new JSONArray();
        for (LibraryItem i : items) {
            if (i.id.equals(id)) continue;
            try { arr.put(i.toJson()); } catch (Exception ignored) {}
        }
        prefs.edit().putString(KEY, arr.toString()).apply();
    }
}
