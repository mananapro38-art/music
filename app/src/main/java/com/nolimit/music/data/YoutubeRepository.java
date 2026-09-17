package com.nolimit.music.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;
import android.text.Html;

import com.nolimit.music.model.SearchResult;
import com.nolimit.music.util.ArtworkLoader;
import com.yausername.ffmpeg.FFmpeg;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class YoutubeRepository {
    public interface ProgressListener { void onProgress(float percent, String line); }
    public enum SearchSource { MUSIC_FIRST, ALL, YOUTUBE_MUSIC, YOUTUBE, SOUNDCLOUD, AUDIUS, BANDCAMP }

    private static final long UPDATE_INTERVAL_MS = 6L * 60L * 60L * 1000L;
    private static final String PREFS = "engine_prefs";
    private static final String KEY_LAST_UPDATE = "last_nightly_update";
    public static final String SETTINGS_PREFS = "settings";
    public static final String KEY_SEARCH_SOURCE = "search_source";
    public static final String KEY_RECENT_SEARCHES = "recent_searches";
    public static final String KEY_FILTER_OFFICIAL = "filter_official_only";
    public static final String KEY_FILTER_EXCLUDE_LIVE = "filter_exclude_live";
    public static final String KEY_FILTER_EXCLUDE_COVER = "filter_exclude_cover";
    public static final String KEY_FILTER_INCLUDE_REMIX = "filter_include_remix";

    private static final Pattern BC_ITEM = Pattern.compile("(?is)<li[^>]*class=\\\"[^\\\"]*searchresult[^\\\"]*\\\"[^>]*>(.*?)</li>");
    private static final Pattern BC_HEADING = Pattern.compile("(?is)<div[^>]*class=\\\"heading\\\"[^>]*>.*?<a[^>]*href=\\\"([^\\\"]+)\\\"[^>]*>(.*?)</a>");
    private static final Pattern BC_SUBHEAD = Pattern.compile("(?is)<div[^>]*class=\\\"subhead\\\"[^>]*>(.*?)</div>");
    private static final Pattern BC_IMAGE = Pattern.compile("(?is)<img[^>]+src=\\\"([^\\\"]+)\\\"");

    private final Context appContext;
    public YoutubeRepository(Context context) { this.appContext = context.getApplicationContext(); }

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
        List<SearchResult> raw;
        if ("all".equals(source)) raw = searchAll(normalized, 42);
        else if ("youtube_music".equals(source)) raw = searchYoutubeMusicSongs(normalized, 40);
        else if ("youtube".equals(source)) raw = searchYoutube(normalized, 40);
        else if ("soundcloud".equals(source)) raw = searchSoundCloud(normalized, 40);
        else if ("audius".equals(source)) raw = searchAudius(normalized, 40);
        else if ("bandcamp".equals(source)) raw = searchBandcamp(normalized, 40);
        else raw = searchMusicFirst(normalized, 40);
        return applyFilters(raw, normalized, settings, 30);
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

    private List<SearchResult> searchAll(String query, int limit) throws Exception {
        List<List<SearchResult>> providers = new ArrayList<>();
        try { providers.add(searchYoutubeMusicSongs(query, 14)); } catch (Exception ignored) { providers.add(new ArrayList<>()); }
        try { providers.add(searchSoundCloud(query, 12)); } catch (Exception ignored) { providers.add(new ArrayList<>()); }
        try { providers.add(searchAudius(query, 12)); } catch (Exception ignored) { providers.add(new ArrayList<>()); }
        try { providers.add(searchBandcamp(query, 12)); } catch (Exception ignored) { providers.add(new ArrayList<>()); }
        try { providers.add(searchYoutube(query, 14)); } catch (Exception ignored) { providers.add(new ArrayList<>()); }

        List<SearchResult> out = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        Set<String> songKeys = new LinkedHashSet<>();
        for (int row = 0; out.size() < limit; row++) {
            boolean addedAny = false;
            for (List<SearchResult> list : providers) {
                if (row >= list.size()) continue;
                SearchResult item = list.get(row);
                String key = lower(item.title).replaceAll("[^\\p{L}\\p{N}]", "") + "|" + lower(item.channel).replaceAll("[^\\p{L}\\p{N}]", "");
                if (ids.add(item.id) && (key.length() < 4 || songKeys.add(key))) {
                    out.add(item); addedAny = true;
                    if (out.size() >= limit) break;
                }
            }
            if (!addedAny) break;
        }
        if (out.isEmpty()) throw new IllegalStateException("통합 검색원에서 결과를 찾지 못했습니다.");
        return out;
    }

    public List<SearchResult> searchYoutubeMusicSongs(String query, int limit) throws Exception {
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String url = "https://music.youtube.com/search?q=" + encoded + "#songs";
        return tagResults(MusicRanker.rank(executeFlatSearch(url, limit), query), "", "YouTube Music");
    }

    public List<SearchResult> searchYoutube(String query, int limit) throws Exception {
        return tagResults(MusicRanker.rank(executeFlatSearch("ytsearch" + limit + ":" + query, limit), query), "", "YouTube");
    }

    public List<SearchResult> searchSoundCloud(String query, int limit) throws Exception {
        List<SearchResult> raw = executeFlatSearch("scsearch" + Math.max(1, limit) + ":" + query, limit);
        return tagResults(raw, "sc_", "SoundCloud");
    }

    public List<SearchResult> searchAudius(String query, int limit) throws Exception {
        String url = "https://api.audius.co/v1/tracks/search?query=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&limit=" + Math.max(1, Math.min(50, limit));
        JSONObject root = new JSONObject(readUrl(url));
        Object data = root.opt("data");
        JSONArray tracks = data instanceof JSONArray ? (JSONArray) data
                : data instanceof JSONObject ? ((JSONObject) data).optJSONArray("tracks") : null;
        List<SearchResult> out = new ArrayList<>();
        if (tracks == null) return out;
        for (int i = 0; i < tracks.length() && out.size() < limit; i++) {
            JSONObject t = tracks.optJSONObject(i); if (t == null) continue;
            String rawId = t.optString("id"); if (rawId.isEmpty()) continue;
            String title = t.optString("title", "제목 없음");
            JSONObject user = t.optJSONObject("user");
            String artist = user == null ? "Audius" : firstNonEmpty(user.optString("name"), user.optString("handle"), "Audius");
            JSONObject art = t.optJSONObject("artwork");
            String thumb = art == null ? "" : firstNonEmpty(art.optString("480x480"), art.optString("1000x1000"), art.optString("150x150"));
            long duration = t.optLong("duration", 0L);
            String genre = t.optString("genre", "");
            out.add(new SearchResult("au_" + rawId, title, artist, "audius:" + rawId, duration, thumb, 0,
                    "Audius · 직접 스트림", genre));
        }
        return out;
    }

    public List<SearchResult> searchBandcamp(String query, int limit) throws Exception {
        String page = readUrl("https://bandcamp.com/search?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8) + "&item_type=t");
        List<SearchResult> out = new ArrayList<>();
        Matcher itemMatcher = BC_ITEM.matcher(page);
        while (itemMatcher.find() && out.size() < limit) {
            String block = itemMatcher.group(1);
            Matcher heading = BC_HEADING.matcher(block);
            if (!heading.find()) continue;
            String trackUrl = decodeHtml(heading.group(1)).trim();
            if (!trackUrl.startsWith("http") || !trackUrl.contains("bandcamp.com/track/")) continue;
            String title = stripHtml(heading.group(2));
            Matcher sub = BC_SUBHEAD.matcher(block);
            String artist = "Bandcamp";
            if (sub.find()) {
                String text = stripHtml(sub.group(1));
                text = text.replaceFirst("(?i)^\\s*track\\s+by\\s+", "").trim();
                if (!text.isEmpty()) artist = text;
            }
            Matcher image = BC_IMAGE.matcher(block);
            String thumb = image.find() ? decodeHtml(image.group(1)).trim() : "";
            String key = UUID.nameUUIDFromBytes(trackUrl.getBytes(StandardCharsets.UTF_8)).toString().replace("-", "");
            out.add(new SearchResult("bc_" + key.substring(0, 16), title, artist, trackUrl, 0L, thumb, 0,
                    "Bandcamp · 아티스트 페이지", ""));
        }
        return out;
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
                String webpage = firstNonEmpty(o.optString("webpage_url"), o.optString("original_url"));
                String rawUrl = o.optString("url");
                if (webpage.isEmpty() && rawUrl.startsWith("http")) webpage = rawUrl;
                if (webpage.isEmpty() && id.length() == 11) webpage = "https://www.youtube.com/watch?v=" + id;
                if (webpage.isEmpty()) continue;
                seen.add(id);
                String title = o.optString("title", "제목 없음");
                String channel = firstNonEmpty(o.optString("channel"), o.optString("uploader"), o.optString("artist"), "음악");
                String album = firstNonEmpty(o.optString("album"), o.optString("playlist_title"));
                long duration = Math.round(o.optDouble("duration", 0));
                String thumbnail = o.optString("thumbnail");
                if ((thumbnail == null || thumbnail.trim().isEmpty()) && id.length() == 11) thumbnail = ArtworkLoader.fallbackUrl(id);
                results.add(new SearchResult(id, title, channel, webpage, duration, thumbnail, 0, "", album));
                if (results.size() >= limit) break;
            } catch (Exception ignored) { }
        }
        return results;
    }

    private static List<SearchResult> tagResults(List<SearchResult> input, String idPrefix, String source) {
        List<SearchResult> out = new ArrayList<>();
        for (SearchResult item : input) {
            String badge = item.badge == null || item.badge.trim().isEmpty() ? source : source + " · " + item.badge;
            out.add(new SearchResult(idPrefix + item.id, item.title, item.channel, item.url, item.durationSeconds,
                    item.thumbnail, item.score, badge, item.album));
        }
        return out;
    }

    private static List<SearchResult> applyFilters(List<SearchResult> input, String query, SharedPreferences settings, int limit) {
        boolean officialOnly = settings.getBoolean(KEY_FILTER_OFFICIAL, false);
        boolean excludeLive = settings.getBoolean(KEY_FILTER_EXCLUDE_LIVE, true);
        boolean excludeCover = settings.getBoolean(KEY_FILTER_EXCLUDE_COVER, true);
        boolean includeRemix = settings.getBoolean(KEY_FILTER_INCLUDE_REMIX, false);
        String q = lower(query);
        boolean queryWantsRemix = containsAny(q, "remix", "리믹스", "sped up", "slowed", "nightcore");
        List<SearchResult> out = new ArrayList<>();
        for (SearchResult item : input) {
            String title = lower(item.title);
            String channel = lower(item.channel);
            boolean directCreatorPlatform = item.id.startsWith("sc_") || item.id.startsWith("au_") || item.id.startsWith("bc_");
            if (officialOnly && !directCreatorPlatform && !isOfficialCandidate(title, channel)) continue;
            if (excludeLive && containsAny(title, " live", "live ", "concert", "performance", "직캠", "fancam")) continue;
            if (excludeCover && containsAny(title, "cover", "커버", "reaction", "리액션")) continue;
            if (!includeRemix && !queryWantsRemix && containsAny(title, "remix", "리믹스", "sped up", "slowed", "nightcore")) continue;
            out.add(item);
            if (out.size() >= limit) break;
        }
        return out;
    }

    private static boolean isOfficialCandidate(String title, String channel) {
        return channel.endsWith(" - topic") || channel.endsWith("- topic") || channel.contains("official")
                || title.contains("official audio") || title.contains("provided to youtube");
    }
    private static boolean containsAny(String text, String... needles) { for (String needle : needles) if (text.contains(needle)) return true; return false; }
    private static String lower(String value) { return value == null ? "" : value.toLowerCase(Locale.ROOT); }

    private static void appendUnique(List<SearchResult> out, Set<String> seen, List<SearchResult> items, int limit) {
        for (SearchResult item : items) {
            if (item == null || item.id == null || !seen.add(item.id)) continue;
            out.add(item); if (out.size() >= limit) break;
        }
    }

    private void saveRecentSearch(String query) {
        try {
            SharedPreferences settings = appContext.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE);
            JSONArray old = new JSONArray(settings.getString(KEY_RECENT_SEARCHES, "[]"));
            JSONArray next = new JSONArray(); next.put(query);
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
        tasks.enqueue(item); tasks.setRunning(item.id);
        ProgressListener tracked = (percent, line) -> {
            tasks.setProgress(item.id, Math.max(0, Math.min(100, Math.round(percent))));
            if (listener != null) listener.onProgress(percent, line);
        };
        try {
            File result = downloadAudioInternal(item, tracked);
            tasks.setDone(item.id); return result;
        } catch (Exception e) {
            tasks.setFailed(item.id, compactError(e)); throw e;
        }
    }

    private File downloadAudioInternal(SearchResult item, ProgressListener listener) throws Exception {
        File base = appContext.getExternalFilesDir(Environment.DIRECTORY_MUSIC);
        if (base == null) base = appContext.getFilesDir();
        File dir = new File(base, "NoLimitMusic");
        if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("저장 폴더를 만들 수 없습니다.");
        File existing = findDownloadedFile(dir, item.id);
        if (existing != null) { if (isYoutubeUrl(item.url)) downloadSubtitlesBestEffort(item, dir); return existing; }

        Exception lastError = null;
        if (!isYoutubeUrl(item.url)) {
            try { return downloadAttempt(item, dir, listener, null, "bestaudio/best", false, "source"); }
            catch (Exception e) { lastError = e; deleteAudioForId(dir, item.id); }
            try { return downloadAttempt(item, dir, listener, null, "best", false, "source-fallback"); }
            catch (Exception e) { lastError = e; deleteAudioForId(dir, item.id); }
            if (lastError != null) throw lastError;
        }

        try {
            File result = downloadAttempt(item, dir, listener, null, "bestaudio[ext=m4a]/bestaudio[ext=webm]/bestaudio", false, "default");
            downloadSubtitlesBestEffort(item, dir); return result;
        } catch (Exception e) { lastError = e; deleteAudioForId(dir, item.id); }
        try {
            File result = downloadAttempt(item, dir, listener, "web_embedded", "bestaudio[ext=m4a]/bestaudio[ext=webm]/bestaudio", false, "embedded");
            downloadSubtitlesBestEffort(item, dir); return result;
        } catch (Exception e) { lastError = e; deleteAudioForId(dir, item.id); }
        try {
            File result = downloadAttempt(item, dir, listener, "android_vr", "18", true, "format18");
            downloadSubtitlesBestEffort(item, dir); return result;
        } catch (Exception e) { lastError = e; deleteAudioForId(dir, item.id); }
        if (lastError != null) throw lastError;
        throw new IllegalStateException("사용 가능한 오디오 경로를 찾지 못했습니다.");
    }

    private File downloadAttempt(SearchResult item, File dir, ProgressListener listener, String playerClient, String format, boolean extractAudio, String attemptName) throws Exception {
        deleteOldPartial(dir, item.id);
        YoutubeDLRequest request = new YoutubeDLRequest(item.url);
        request.addOption("--no-playlist"); request.addOption("--no-mtime"); request.addOption("--no-warnings"); request.addOption("--remote-components", "ejs:github");
        if (playerClient != null && !playerClient.isEmpty()) request.addOption("--extractor-args", "youtube:player_client=" + playerClient);
        request.addOption("--retries", "5"); request.addOption("--fragment-retries", "5"); request.addOption("--socket-timeout", "20"); request.addOption("-f", format);
        if (extractAudio) { request.addOption("--extract-audio"); request.addOption("--audio-format", "m4a"); }
        request.addOption("-o", new File(dir, item.id + ".%(ext)s").getAbsolutePath());
        String processId = "audio-" + item.id + "-" + attemptName;
        YoutubeDL.getInstance().execute(request, processId, (progress, eta, line) -> {
            if (listener != null) listener.onProgress(progress, line == null ? "" : "[" + attemptName + "] " + line);
            return kotlin.Unit.INSTANCE;
        });
        File result = findDownloadedFile(dir, item.id);
        if (result == null) throw new IllegalStateException("다운로드는 끝났지만 저장 파일을 찾지 못했습니다.");
        return result;
    }

    private void downloadSubtitlesBestEffort(SearchResult item, File dir) {
        if (!isYoutubeUrl(item.url) || hasSubtitle(dir, item.id)) return;
        try { YoutubeDLRequest manual = subtitleRequest(item, dir); manual.addOption("--write-subs"); manual.addOption("--sub-langs", "all,-live_chat"); YoutubeDL.getInstance().execute(manual); }
        catch (Exception ignored) { }
        if (hasSubtitle(dir, item.id)) return;
        try { YoutubeDLRequest automatic = subtitleRequest(item, dir); automatic.addOption("--write-auto-subs"); automatic.addOption("--sub-langs", "ko.*,en.*,ja.*"); YoutubeDL.getInstance().execute(automatic); }
        catch (Exception ignored) { }
    }

    private static YoutubeDLRequest subtitleRequest(SearchResult item, File dir) {
        YoutubeDLRequest request = new YoutubeDLRequest(item.url);
        request.addOption("--skip-download"); request.addOption("--no-playlist"); request.addOption("--no-warnings"); request.addOption("--ignore-errors");
        request.addOption("--remote-components", "ejs:github"); request.addOption("--sub-format", "vtt"); request.addOption("-o", new File(dir, item.id + ".%(ext)s").getAbsolutePath()); return request;
    }

    private static boolean isYoutubeUrl(String url) {
        if (url == null) return false;
        String u = url.toLowerCase(Locale.ROOT);
        return u.contains("youtube.com/") || u.contains("youtu.be/") || u.contains("music.youtube.com/");
    }

    private static String readUrl(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000); c.setReadTimeout(20000); c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) NoLimitMusic/1.2");
        c.setRequestProperty("Accept", "application/json,text/html,*/*");
        try {
            int code = c.getResponseCode();
            InputStream in = code >= 200 && code < 400 ? c.getInputStream() : c.getErrorStream();
            if (in == null) throw new IllegalStateException("HTTP " + code);
            StringBuilder b = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line; while ((line = r.readLine()) != null) b.append(line).append('\n');
            }
            if (code < 200 || code >= 400) throw new IllegalStateException("HTTP " + code);
            return b.toString();
        } finally { c.disconnect(); }
    }

    private static String stripHtml(String value) {
        return decodeHtml(value == null ? "" : value.replaceAll("<[^>]+>", " ")).replaceAll("\\s+", " ").trim();
    }
    private static String decodeHtml(String value) {
        return Html.fromHtml(value == null ? "" : value, Html.FROM_HTML_MODE_LEGACY).toString();
    }

    private static boolean hasSubtitle(File dir, String id) { File[] files = dir.listFiles((d, name) -> { String n = name.toLowerCase(Locale.ROOT); return name.startsWith(id + ".") && (n.endsWith(".vtt") || n.endsWith(".srt") || n.endsWith(".lrc")); }); return files != null && files.length > 0; }
    private static File findDownloadedFile(File dir, String id) {
        File[] files = dir.listFiles((d, name) -> { String n = name.toLowerCase(Locale.ROOT); if (!name.startsWith(id + ".")) return false; return !n.endsWith(".part") && !n.endsWith(".ytdl") && !n.endsWith(".vtt") && !n.endsWith(".srt") && !n.endsWith(".ass") && !n.endsWith(".lrc") && !n.endsWith(".json") && !n.endsWith(".jpg") && !n.endsWith(".jpeg") && !n.endsWith(".png") && !n.endsWith(".webp"); });
        if (files == null || files.length == 0) return null; File newest = files[0]; for (File f : files) if (f.lastModified() > newest.lastModified()) newest = f; return newest;
    }
    private static void deleteOldPartial(File dir, String id) { File[] files = dir.listFiles((d, name) -> name.startsWith(id + ".") && (name.endsWith(".part") || name.endsWith(".ytdl"))); if (files != null) for (File f : files) f.delete(); }
    private static void deleteAudioForId(File dir, String id) { File[] files = dir.listFiles((d, name) -> { String n = name.toLowerCase(Locale.ROOT); return name.startsWith(id + ".") && !n.endsWith(".vtt") && !n.endsWith(".srt") && !n.endsWith(".ass") && !n.endsWith(".lrc"); }); if (files != null) for (File f : files) f.delete(); }
    private static String firstNonEmpty(String... values) { for (String value : values) if (value != null && !value.trim().isEmpty()) return value; return ""; }
    private static String compactError(Throwable e) { if (e == null) return "알 수 없는 오류"; String message = e.getMessage(); if (message == null || message.trim().isEmpty()) return e.getClass().getSimpleName(); message = message.replace('\n', ' ').replace('\r', ' ').trim(); return message.length() > 160 ? message.substring(0, 160) : message; }
}
