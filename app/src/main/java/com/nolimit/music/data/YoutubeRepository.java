package com.nolimit.music.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;

import com.nolimit.music.model.SearchResult;
import com.nolimit.music.util.ArtworkLoader;
import com.yausername.ffmpeg.FFmpeg;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class YoutubeRepository {
    public interface ProgressListener {
        void onProgress(float percent, String line);
    }

    public enum SearchSource {
        MUSIC_FIRST,
        YOUTUBE_MUSIC,
        YOUTUBE
    }

    private static final long UPDATE_INTERVAL_MS = 6L * 60L * 60L * 1000L;
    private static final String PREFS = "engine_prefs";
    private static final String KEY_LAST_UPDATE = "last_nightly_update";
    public static final String SETTINGS_PREFS = "settings";
    public static final String KEY_SEARCH_SOURCE = "search_source";
    public static final String KEY_RECENT_SEARCHES = "recent_searches";

    private final Context appContext;

    public YoutubeRepository(Context context) {
        this.appContext = context.getApplicationContext();
    }

    public void init() throws Exception {
        YoutubeDL.getInstance().init(appContext);
        FFmpeg.getInstance().init(appContext);
        updateNightlyBestEffort();
    }

    private void updateNightlyBestEffort() {
        SharedPreferences prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        long last = prefs.getLong(KEY_LAST_UPDATE, 0L);
        if (now - last < UPDATE_INTERVAL_MS) return;
        try {
            YoutubeDL.getInstance().updateYoutubeDL(appContext, YoutubeDL.UpdateChannel._NIGHTLY);
            prefs.edit().putLong(KEY_LAST_UPDATE, now).apply();
        } catch (Exception ignored) { }
    }

    public List<SearchResult> search(String query) throws Exception {
        String normalized = query == null ? "" : query.trim();
        if (normalized.isEmpty()) return new ArrayList<>();
        saveRecentSearch(normalized);
        SharedPreferences settings = appContext.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE);
        String source = settings.getString(KEY_SEARCH_SOURCE, "music_first");
        if ("youtube_music".equals(source)) return searchYoutubeMusicSongs(normalized, 30);
        if ("youtube".equals(source)) return searchYoutube(normalized, 30);
        return searchMusicFirst(normalized, 30);
    }

    private List<SearchResult> searchMusicFirst(String query, int limit) throws Exception {
        List<SearchResult> merged = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Exception musicError = null;
        try { appendUnique(merged, seen, searchYoutubeMusicSongs(query, limit), limit); }
        catch (Exception e) { musicError = e; }
        if (merged.size() < limit) {
            try { appendUnique(merged, seen, searchYoutube(query, limit), limit); }
            catch (Exception e) {
                if (merged.isEmpty() && musicError != null) throw musicError;
                if (merged.isEmpty()) throw e;
            }
        }
        if (merged.isEmpty() && musicError != null) throw musicError;
        return merged;
    }

    public List<SearchResult> searchYoutubeMusicSongs(String query, int limit) throws Exception {
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String url = "https://music.youtube.com/search?q=" + encoded + "#songs";
        return MusicRanker.rank(executeFlatSearch(url, limit), query);
    }

    public List<SearchResult> searchYoutube(String query, int limit) throws Exception {
        return MusicRanker.rank(executeFlatSearch("ytsearch" + limit + ":" + query, limit), query);
    }

    private List<SearchResult> executeFlatSearch(String target, int limit) throws Exception {
        YoutubeDLRequest request = new YoutubeDLRequest(target);
        request.addOption("--flat-playlist");
        request.addOption("--dump-json");
        request.addOption("--skip-download");
        request.addOption("--no-warnings");
        request.addOption("--ignore-errors");
        request.addOption("--playlist-items", "1:" + Math.max(1, limit));
        request.addOption("--remote-components", "ejs:github");
        String out = YoutubeDL.getInstance().execute(request).getOut();
        List<SearchResult> results = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String rawLine : out.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (!line.startsWith("{") || !line.endsWith("}")) continue;
            try {
                JSONObject o = new JSONObject(line);
                String id = o.optString("id");
                if (id.isEmpty() || seen.contains(id)) continue;
                String webpage = firstNonEmpty(o.optString("webpage_url"), o.optString("url"));
                if (webpage.isEmpty() || !webpage.startsWith("http")) webpage = "https://www.youtube.com/watch?v=" + id;
                if (!webpage.contains("watch?v=") && id.length() != 11) continue;
                seen.add(id);
                String title = o.optString("title", "제목 없음");
                String channel = firstNonEmpty(o.optString("channel"), o.optString("uploader"), o.optString("artist"), "YouTube");
                String album = firstNonEmpty(o.optString("album"), o.optString("playlist_title"));
                long duration = Math.round(o.optDouble("duration", 0));
                String thumbnail = o.optString("thumbnail");
                if (thumbnail == null || thumbnail.trim().isEmpty()) thumbnail = ArtworkLoader.fallbackUrl(id);
                results.add(new SearchResult(id, title, channel, webpage, duration, thumbnail, 0, "", album));
                if (results.size() >= limit) break;
            } catch (Exception ignored) { }
        }
        return results;
    }

    private static void appendUnique(List<SearchResult> out, Set<String> seen, List<SearchResult> items, int limit) {
        for (SearchResult item : items) {
            if (item == null || item.id == null || !seen.add(item.id)) continue;
            out.add(item);
            if (out.size() >= limit) break;
        }
    }

    private void saveRecentSearch(String query) {
        try {
            SharedPreferences settings = appContext.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE);
            JSONArray old = new JSONArray(settings.getString(KEY_RECENT_SEARCHES, "[]"));
            JSONArray next = new JSONArray();
            next.put(query);
            for (int i = 0; i < old.length() && next.length() < 8; i++) {
                String value = old.optString(i).trim();
                if (value.isEmpty() || value.equalsIgnoreCase(query)) continue;
                next.put(value);
            }
            settings.edit().putString(KEY_RECENT_SEARCHES, next.toString()).apply();
        } catch (Exception ignored) { }
    }

    public File downloadAudio(SearchResult item, ProgressListener listener) throws Exception {
        DownloadTaskStore tasks = new DownloadTaskStore(appContext);
        tasks.enqueue(item);
        tasks.setRunning(item.id);
        ProgressListener tracked = (percent, line) -> {
            tasks.setProgress(item.id, Math.max(0, Math.min(100, Math.round(percent))));
            if (listener != null) listener.onProgress(percent, line);
        };
        try {
            File result = downloadAudioInternal(item, tracked);
            tasks.setDone(item.id);
            return result;
        } catch (Exception e) {
            tasks.setFailed(item.id, compactError(e));
            throw e;
        }
    }

    private File downloadAudioInternal(SearchResult item, ProgressListener listener) throws Exception {
        File base = appContext.getExternalFilesDir(Environment.DIRECTORY_MUSIC);
        if (base == null) base = appContext.getFilesDir();
        File dir = new File(base, "NoLimitMusic");
        if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("저장 폴더를 만들 수 없습니다.");
        File existing = findDownloadedFile(dir, item.id);
        if (existing != null) {
            downloadSubtitlesBestEffort(item, dir);
            return existing;
        }
        Exception lastError = null;
        try {
            File result = downloadAttempt(item, dir, listener, null,
                    "bestaudio[ext=m4a]/bestaudio[ext=webm]/bestaudio", false, "default");
            downloadSubtitlesBestEffort(item, dir);
            return result;
        } catch (Exception e) { lastError = e; deleteAudioForId(dir, item.id); }
        try {
            File result = downloadAttempt(item, dir, listener, "web_embedded",
                    "bestaudio[ext=m4a]/bestaudio[ext=webm]/bestaudio", false, "embedded");
            downloadSubtitlesBestEffort(item, dir);
            return result;
        } catch (Exception e) { lastError = e; deleteAudioForId(dir, item.id); }
        try {
            File result = downloadAttempt(item, dir, listener, "android_vr", "18", true, "format18");
            downloadSubtitlesBestEffort(item, dir);
            return result;
        } catch (Exception e) { lastError = e; deleteAudioForId(dir, item.id); }
        if (lastError != null) throw lastError;
        throw new IllegalStateException("사용 가능한 YouTube 오디오 경로를 찾지 못했습니다.");
    }

    private File downloadAttempt(SearchResult item, File dir, ProgressListener listener,
                                 String playerClient, String format, boolean extractAudio,
                                 String attemptName) throws Exception {
        deleteOldPartial(dir, item.id);
        YoutubeDLRequest request = new YoutubeDLRequest(item.url);
        request.addOption("--no-playlist");
        request.addOption("--no-mtime");
        request.addOption("--no-warnings");
        request.addOption("--remote-components", "ejs:github");
        if (playerClient != null && !playerClient.isEmpty()) request.addOption("--extractor-args", "youtube:player_client=" + playerClient);
        request.addOption("--retries", "5");
        request.addOption("--fragment-retries", "5");
        request.addOption("--socket-timeout", "20");
        request.addOption("-f", format);
        if (extractAudio) {
            request.addOption("--extract-audio");
            request.addOption("--audio-format", "m4a");
        }
        request.addOption("-o", new File(dir, item.id + ".%(ext)s").getAbsolutePath());
        String processId = "audio-" + item.id + "-" + attemptName;
        YoutubeDL.getInstance().execute(request, processId, (progress, eta, line) -> {
            if (listener != null) {
                String message = line == null ? "" : "[" + attemptName + "] " + line;
                listener.onProgress(progress, message);
            }
            return kotlin.Unit.INSTANCE;
        });
        File result = findDownloadedFile(dir, item.id);
        if (result == null) throw new IllegalStateException("다운로드는 끝났지만 저장 파일을 찾지 못했습니다.");
        return result;
    }

    private void downloadSubtitlesBestEffort(SearchResult item, File dir) {
        if (hasSubtitle(dir, item.id)) return;
        try {
            YoutubeDLRequest manual = subtitleRequest(item, dir);
            manual.addOption("--write-subs");
            manual.addOption("--sub-langs", "all,-live_chat");
            YoutubeDL.getInstance().execute(manual);
        } catch (Exception ignored) { }
        if (hasSubtitle(dir, item.id)) return;
        try {
            YoutubeDLRequest automatic = subtitleRequest(item, dir);
            automatic.addOption("--write-auto-subs");
            automatic.addOption("--sub-langs", "ko.*,en.*,ja.*");
            YoutubeDL.getInstance().execute(automatic);
        } catch (Exception ignored) { }
    }

    private static YoutubeDLRequest subtitleRequest(SearchResult item, File dir) {
        YoutubeDLRequest request = new YoutubeDLRequest(item.url);
        request.addOption("--skip-download");
        request.addOption("--no-playlist");
        request.addOption("--no-warnings");
        request.addOption("--ignore-errors");
        request.addOption("--remote-components", "ejs:github");
        request.addOption("--sub-format", "vtt");
        request.addOption("-o", new File(dir, item.id + ".%(ext)s").getAbsolutePath());
        return request;
    }

    private static boolean hasSubtitle(File dir, String id) {
        File[] files = dir.listFiles((d, name) -> {
            String n = name.toLowerCase(Locale.ROOT);
            return name.startsWith(id + ".") && (n.endsWith(".vtt") || n.endsWith(".srt"));
        });
        return files != null && files.length > 0;
    }

    private static File findDownloadedFile(File dir, String id) {
        File[] files = dir.listFiles((d, name) -> {
            String n = name.toLowerCase(Locale.ROOT);
            if (!name.startsWith(id + ".")) return false;
            return !n.endsWith(".part") && !n.endsWith(".ytdl") && !n.endsWith(".vtt")
                    && !n.endsWith(".srt") && !n.endsWith(".ass") && !n.endsWith(".lrc")
                    && !n.endsWith(".json") && !n.endsWith(".jpg") && !n.endsWith(".jpeg")
                    && !n.endsWith(".png") && !n.endsWith(".webp");
        });
        if (files == null || files.length == 0) return null;
        File newest = files[0];
        for (File f : files) if (f.lastModified() > newest.lastModified()) newest = f;
        return newest;
    }

    private static void deleteOldPartial(File dir, String id) {
        File[] files = dir.listFiles((d, name) -> name.startsWith(id + ".") && (name.endsWith(".part") || name.endsWith(".ytdl")));
        if (files != null) for (File f : files) f.delete();
    }

    private static void deleteAudioForId(File dir, String id) {
        File[] files = dir.listFiles((d, name) -> {
            String n = name.toLowerCase(Locale.ROOT);
            return name.startsWith(id + ".") && !n.endsWith(".vtt") && !n.endsWith(".srt") && !n.endsWith(".ass") && !n.endsWith(".lrc");
        });
        if (files != null) for (File f : files) f.delete();
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) if (value != null && !value.trim().isEmpty()) return value;
        return "";
    }

    private static String compactError(Throwable e) {
        if (e == null) return "알 수 없는 오류";
        String message = e.getMessage();
        if (message == null || message.trim().isEmpty()) return e.getClass().getSimpleName();
        message = message.replace('\n', ' ').replace('\r', ' ').trim();
        return message.length() > 160 ? message.substring(0, 160) : message;
    }
}
