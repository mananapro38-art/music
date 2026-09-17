package com.nolimit.music.data;

import android.content.Context;
import android.content.SharedPreferences;

import com.nolimit.music.model.SearchResult;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class DownloadTaskStore {
    private static final String PREFS = "download_tasks";
    private static final String KEY_ITEMS = "items";
    private final SharedPreferences prefs;

    public static final class Task {
        public final SearchResult item;
        public final String state;
        public final int progress;
        public final String error;
        public final long createdAt;

        public Task(SearchResult item, String state, int progress, String error, long createdAt) {
            this.item = item;
            this.state = state;
            this.progress = progress;
            this.error = error == null ? "" : error;
            this.createdAt = createdAt;
        }
    }

    public DownloadTaskStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        recoverInterrupted();
    }

    public synchronized boolean enqueue(SearchResult item) {
        List<Task> tasks = load();
        for (Task t : tasks) {
            if (t.item.id.equals(item.id) && ("pending".equals(t.state) || "running".equals(t.state))) return false;
        }
        tasks.add(new Task(item, "pending", 0, "", System.currentTimeMillis()));
        save(tasks);
        return true;
    }

    public synchronized Task nextPending() {
        for (Task t : load()) if ("pending".equals(t.state)) return t;
        return null;
    }

    public synchronized List<Task> load() {
        List<Task> out = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(prefs.getString(KEY_ITEMS, "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.getJSONObject(i);
                SearchResult item = new SearchResult(
                        o.optString("id"), o.optString("title"), o.optString("artist"),
                        o.optString("url"), o.optLong("duration"), o.optString("thumbnail"),
                        0, "", o.optString("album", ""));
                out.add(new Task(item, o.optString("state", "pending"), o.optInt("progress", 0),
                        o.optString("error", ""), o.optLong("createdAt", 0L)));
            }
        } catch (Exception ignored) { }
        return out;
    }

    public synchronized void setRunning(String id) { update(id, "running", 0, ""); }
    public synchronized void setProgress(String id, int progress) { update(id, "running", progress, ""); }
    public synchronized void setDone(String id) { update(id, "done", 100, ""); }
    public synchronized void setFailed(String id, String error) { update(id, "failed", 0, error); }

    public synchronized void retry(String id) {
        update(id, "pending", 0, "");
    }

    public synchronized void remove(String id) {
        List<Task> tasks = load();
        tasks.removeIf(t -> t.item.id.equals(id) && !"running".equals(t.state));
        save(tasks);
    }

    public synchronized void clearFinished() {
        List<Task> tasks = load();
        tasks.removeIf(t -> "done".equals(t.state));
        save(tasks);
    }

    private void update(String id, String state, int progress, String error) {
        List<Task> tasks = load();
        for (int i = 0; i < tasks.size(); i++) {
            Task t = tasks.get(i);
            if (t.item.id.equals(id)) {
                tasks.set(i, new Task(t.item, state, progress, error, t.createdAt));
                save(tasks);
                return;
            }
        }
    }

    private void recoverInterrupted() {
        List<Task> tasks = load();
        boolean changed = false;
        for (int i = 0; i < tasks.size(); i++) {
            Task t = tasks.get(i);
            if ("running".equals(t.state)) {
                tasks.set(i, new Task(t.item, "pending", 0, "앱이 종료되어 다시 대기합니다.", t.createdAt));
                changed = true;
            }
        }
        if (changed) save(tasks);
    }

    private void save(List<Task> tasks) {
        JSONArray array = new JSONArray();
        for (Task t : tasks) {
            JSONObject o = new JSONObject();
            try {
                o.put("id", t.item.id);
                o.put("title", t.item.title);
                o.put("artist", t.item.channel);
                o.put("url", t.item.url);
                o.put("duration", t.item.durationSeconds);
                o.put("thumbnail", t.item.thumbnail);
                o.put("album", t.item.album);
                o.put("state", t.state);
                o.put("progress", t.progress);
                o.put("error", t.error);
                o.put("createdAt", t.createdAt);
                array.put(o);
            } catch (Exception ignored) { }
        }
        prefs.edit().putString(KEY_ITEMS, array.toString()).apply();
    }
}
