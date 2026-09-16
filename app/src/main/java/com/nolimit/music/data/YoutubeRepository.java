package com.nolimit.music.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;

import com.nolimit.music.model.SearchResult;
import com.yausername.ffmpeg.FFmpeg;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class YoutubeRepository {
    public interface ProgressListener {
        void onProgress(float percent, String line);
    }

    private static final long UPDATE_INTERVAL_MS = 6L * 60L * 60L * 1000L;
    private static final String PREFS = "engine_prefs";
    private static final String KEY_LAST_UPDATE = "last_nightly_update";

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
        } catch (Exception ignored) {
            // Keep the bundled engine if the device is offline or the updater is temporarily unavailable.
        }
    }

    public List<SearchResult> search(String query) throws Exception {
        YoutubeDLRequest request = new YoutubeDLRequest("ytsearch25:" + query);
        request.addOption("--flat-playlist");
        request.addOption("--dump-json");
        request.addOption("--skip-download");
        request.addOption("--no-warnings");
        request.addOption("--ignore-errors");
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
                seen.add(id);

                String title = o.optString("title", "제목 없음");
                String channel = firstNonEmpty(o.optString("channel"), o.optString("uploader"), "YouTube");
                String webpage = o.optString("webpage_url");
                if (webpage.isEmpty()) webpage = "https://www.youtube.com/watch?v=" + id;
                long duration = Math.round(o.optDouble("duration", 0));
                String thumbnail = o.optString("thumbnail");
                results.add(new SearchResult(id, title, channel, webpage, duration, thumbnail, 0, ""));
            } catch (Exception ignored) { }
        }
        return MusicRanker.rank(results, query);
    }

    public File downloadAudio(SearchResult item, ProgressListener listener) throws Exception {
        File base = appContext.getExternalFilesDir(Environment.DIRECTORY_MUSIC);
        if (base == null) base = appContext.getFilesDir();
        File dir = new File(base, "NoLimitMusic");
        if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("저장 폴더를 만들 수 없습니다.");

        File existing = findDownloadedFile(dir, item.id);
        if (existing != null) return existing;

        Exception lastError = null;

        // 1) Current yt-dlp defaults (currently visionOS/web family). This handles videos
        // which explicitly disallow embedded playback.
        try {
            return downloadAttempt(item, dir, listener, "default", "bestaudio[ext=m4a]/bestaudio[ext=webm]/bestaudio", false, "default");
        } catch (Exception e) {
            lastError = e;
            deleteAllForId(dir, item.id);
        }

        // 2) Embedded web client avoids the android_vr GVS 403 on many public videos.
        // Keep it as a completely separate extraction attempt to avoid cross-client URL mixing.
        try {
            return downloadAttempt(item, dir, listener, "web_embedded", "bestaudio[ext=m4a]/bestaudio[ext=webm]/bestaudio", false, "embedded");
        } catch (Exception e) {
            lastError = e;
            deleteAllForId(dir, item.id);
        }

        // 3) Last-resort path: android_vr format 18 is often still available without a GVS
        // PO token. Download the progressive MP4 then let FFmpeg extract only its AAC audio.
        try {
            return downloadAttempt(item, dir, listener, "android_vr", "18", true, "format18");
        } catch (Exception e) {
            lastError = e;
            deleteAllForId(dir, item.id);
        }

        if (lastError != null) throw lastError;
        throw new IllegalStateException("사용 가능한 YouTube 오디오 경로를 찾지 못했습니다.");
    }

    private File downloadAttempt(
            SearchResult item,
            File dir,
            ProgressListener listener,
            String playerClient,
            String format,
            boolean extractAudio,
            String attemptName
    ) throws Exception {
        deleteOldPartial(dir, item.id);

        YoutubeDLRequest request = new YoutubeDLRequest(item.url);
        request.addOption("--no-playlist");
        request.addOption("--no-mtime");
        request.addOption("--no-warnings");
        request.addOption("--remote-components", "ejs:github");
        request.addOption("--extractor-args", "youtube:player_client=" + playerClient);
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

    private static File findDownloadedFile(File dir, String id) {
        File[] files = dir.listFiles((d, name) -> name.startsWith(id + ".") && !name.endsWith(".part") && !name.endsWith(".ytdl"));
        if (files == null || files.length == 0) return null;
        File newest = files[0];
        for (File f : files) if (f.lastModified() > newest.lastModified()) newest = f;
        return newest;
    }

    private static void deleteOldPartial(File dir, String id) {
        File[] files = dir.listFiles((d, name) -> name.startsWith(id + ".") && (name.endsWith(".part") || name.endsWith(".ytdl")));
        if (files != null) for (File f : files) f.delete();
    }

    private static void deleteAllForId(File dir, String id) {
        File[] files = dir.listFiles((d, name) -> name.startsWith(id + "."));
        if (files != null) for (File f : files) f.delete();
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) if (value != null && !value.trim().isEmpty()) return value;
        return "YouTube";
    }
}
