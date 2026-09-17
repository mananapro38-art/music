package com.nolimit.music.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import com.nolimit.music.model.SearchResult;
import com.nolimit.music.model.Track;
import com.nolimit.music.util.ArtworkLoader;
import com.nolimit.music.util.NetworkUtil;

import java.io.File;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DownloadQueueManager {
    public interface Listener {
        void onQueueChanged();
        void onProgress(String id, int progress, String title);
        void onCompleted(Track track);
        void onFailed(SearchResult item, String error);
    }

    private static volatile DownloadQueueManager instance;
    public static DownloadQueueManager get(Context context) {
        if (instance == null) synchronized (DownloadQueueManager.class) {
            if (instance == null) instance = new DownloadQueueManager(context.getApplicationContext());
        }
        return instance;
    }

    private final Context context;
    private final DownloadTaskStore tasks;
    private final LibraryStore library;
    private final PlaylistStore playlists;
    private final YoutubeRepository youtube;
    private final SharedPreferences settings;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private volatile boolean engineReady = false;

    private DownloadQueueManager(Context context) {
        this.context = context;
        tasks = new DownloadTaskStore(context);
        library = new LibraryStore(context);
        playlists = new PlaylistStore(context, library);
        youtube = new YoutubeRepository(context);
        settings = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    public void addListener(Listener listener) { if (listener != null) listeners.addIfAbsent(listener); }
    public void removeListener(Listener listener) { listeners.remove(listener); }

    public boolean enqueue(SearchResult item) {
        if (item == null || item.id == null || item.id.isEmpty()) return false;
        Track existing = library.find(item.id);
        if (existing != null && TrackStorage.exists(context, existing.path)) {
            notifyCompleted(existing);
            return false;
        }
        boolean added = tasks.enqueue(item);
        notifyQueue();
        kick();
        return added;
    }

    public int enqueueMissingLibrary() {
        int count = 0;
        for (Track track : library.load()) {
            if (TrackStorage.exists(context, track.path)) continue;
            String thumb = track.thumbnailUrl == null || track.thumbnailUrl.isEmpty() ? ArtworkLoader.fallbackUrl(track.id) : track.thumbnailUrl;
            SearchResult item = new SearchResult(track.id, track.title, track.artist,
                    "https://www.youtube.com/watch?v=" + track.id, track.durationSeconds, thumb, 0, "복원", track.album);
            if (tasks.enqueue(item)) count++;
        }
        notifyQueue(); kick(); return count;
    }

    public void retry(String id) { tasks.retry(id); notifyQueue(); kick(); }
    public void kick() {
        if (!running.compareAndSet(false, true)) return;
        worker.execute(this::runLoop);
    }

    private void runLoop() {
        try {
            if (!engineReady) { youtube.init(); engineReady = true; }
            while (true) {
                DownloadTaskStore.Task task = tasks.nextPending();
                if (task == null) break;
                boolean allowMobile = settings.getBoolean("allow_mobile_download", false);
                if (!NetworkUtil.canDownload(context, allowMobile)) break;
                SearchResult item = task.item;
                try {
                    tasks.setRunning(item.id); notifyQueue();
                    File file = youtube.downloadAudio(item, (percent, line) -> {
                        int p = Math.max(0, Math.min(100, Math.round(percent)));
                        tasks.setProgress(item.id, p); notifyProgress(item.id, p, item.title);
                    });
                    ArtworkLoader.cacheToDisk(context, item.id, item.thumbnail);
                    Track track = new Track(item.id, item.title, item.channel, file.getAbsolutePath(), item.durationSeconds,
                            System.currentTimeMillis(), false, 0, 0L, item.thumbnail, item.album, AutoTagger.infer(item.title, item.channel));
                    library.upsert(track);
                    Track saved = library.find(item.id); if (saved != null) track = saved;
                    playlists.addTrack(PlaylistStore.DEFAULT_ID, track.id);
                    tasks.setDone(item.id); notifyCompleted(track); notifyQueue();
                } catch (Exception e) {
                    String error = compact(e); tasks.setFailed(item.id, error); notifyFailed(item, error); notifyQueue();
                }
            }
        } catch (Exception ignored) {
        } finally {
            running.set(false);
            if (tasks.nextPending() != null && NetworkUtil.canDownload(context, settings.getBoolean("allow_mobile_download", false))) kick();
        }
    }

    private void notifyQueue() { main.post(() -> { for (Listener l : listeners) l.onQueueChanged(); }); }
    private void notifyProgress(String id, int progress, String title) { main.post(() -> { for (Listener l : listeners) l.onProgress(id, progress, title); }); }
    private void notifyCompleted(Track track) { main.post(() -> { for (Listener l : listeners) l.onCompleted(track); }); }
    private void notifyFailed(SearchResult item, String error) { main.post(() -> { for (Listener l : listeners) l.onFailed(item, error); }); }
    private static String compact(Throwable e) { String m = e == null ? "" : e.getMessage(); if (m == null || m.trim().isEmpty()) return e == null ? "알 수 없는 오류" : e.getClass().getSimpleName(); m = m.replace('\n',' ').replace('\r',' ').trim(); return m.length() > 160 ? m.substring(0,160) : m; }
}
