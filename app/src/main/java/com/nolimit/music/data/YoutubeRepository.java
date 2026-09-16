package com.nolimit.music.data;

import android.content.Context;
import android.os.Environment;

import com.nolimit.music.model.SearchResult;
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

    private final Context appContext;

    public YoutubeRepository(Context context) {
        this.appContext = context.getApplicationContext();
    }

    public void init() throws Exception {
        YoutubeDL.getInstance().init(appContext);
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

        deleteOldPartial(dir, item.id);

        YoutubeDLRequest request = new YoutubeDLRequest(item.url);
        request.addOption("--no-playlist");
        request.addOption("--no-mtime");
        request.addOption("--no-warnings");
        request.addOption("--remote-components", "ejs:github");
        request.addOption("-f", "bestaudio[ext=m4a]/bestaudio[ext=webm]/bestaudio");
        request.addOption("-o", new File(dir, item.id + ".%(ext)s").getAbsolutePath());

        String processId = "audio-" + item.id;
        YoutubeDL.getInstance().execute(request, processId, (progress, eta, line) -> {
            if (listener != null) listener.onProgress(progress, line == null ? "" : line);
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

    private static String firstNonEmpty(String... values) {
        for (String value : values) if (value != null && !value.trim().isEmpty()) return value;
        return "YouTube";
    }
}
