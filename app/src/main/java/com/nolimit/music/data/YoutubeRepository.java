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
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
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
    private static final String KEY_LAST_YTM_ERROR = "last_ytm_error";
    // Current ytmusicapi "songs" search params (SearchMixin.get_search_params("songs")).
    private static final String YTM_SONGS_PARAMS = "EgWKAQIIAWoMEA4QChADEAQQCRAF";
    // Alternate Songs filter used by current Android YTM clients. Some regions/devices
    // return an empty 200 response for the web filter but populate this one.
    private static final String YTM_SONGS_PARAMS_ALT = "EgWKAQIIAWoKEAkQBRAKEAMQBA==";
    private static final String YTM_FALLBACK_API_KEY = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30";
    // Anonymous ytmusicapi uses a daily WEB_REMIX client version. A stale fixed
    // version can be soft-blocked, so generate today's UTC identity at runtime.
    private static final String YTM_CLIENT_VERSION_SUFFIX = ".01.00";
    // Keep this aligned with ytmusicapi's anonymous browser transport.
    private static final String YTM_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:88.0) Gecko/20100101 Firefox/88.0";
    private static final String YTM_CONSENT_COOKIE = "SOCS=CAI";
    private static final Pattern YTCFG_SET = Pattern.compile(
            "(?s)ytcfg\\.set\\s*\\(\\s*(\\{.+?\\})\\s*\\)\\s*;");

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
        raw = MusicRanker.preferAudioResults(raw, normalized);
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
        List<String> errors = new ArrayList<>();
        List<SearchResult> merged = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int target = Math.max(12, Math.min(limit, 30));

        // 1) Direct keyed WEB_REMIX catalogue search. Keep going when the first
        // response is small: artist-name queries often need results from more than
        // one YTM response shape/filter to expose the full song catalogue.
        try {
            List<SearchResult> direct = searchYoutubeMusicInnertube(query, limit);
            appendUnique(merged, seen, direct, limit);
            if (direct.isEmpty()) errors.add("WEB_REMIX: 결과 없음");
        } catch (Exception e) {
            errors.add("WEB_REMIX: " + compactError(e));
        }

        // 2) Page parse only when the API batch is still sparse.
        if (merged.size() < target) {
            try {
                List<SearchResult> page = searchYoutubeMusicPage(query, limit);
                appendUnique(merged, seen, page, limit);
                if (page.isEmpty()) errors.add("YTM page: 결과 없음");
            } catch (Exception e) {
                errors.add("YTM page: " + compactError(e));
            }
        }

        // 3) yt-dlp compatibility path, again only to fill a sparse catalogue.
        if (merged.size() < target) {
            try {
                String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
                String filteredUrl = "https://music.youtube.com/search?q=" + encoded
                        + "&sp=" + URLEncoder.encode(YTM_SONGS_PARAMS, StandardCharsets.UTF_8);
                List<SearchResult> extracted = executeFlatSearch(filteredUrl, limit);
                appendUnique(merged, seen, extracted, limit);
                if (extracted.isEmpty()) errors.add("yt-dlp: 결과 없음");
            } catch (Exception e) {
                errors.add("yt-dlp: " + compactError(e));
            }
        }

        if (!merged.isEmpty()) {
            rememberYtmError(String.join(" | ", errors));
            return tagResults(MusicRanker.rank(merged, query), "", "YouTube Music");
        }

        // 4) Last resort only: ordinary YouTube, clearly labelled as fallback.
        try {
            List<SearchResult> fallback = searchYoutube(query + " official audio", limit);
            if (!fallback.isEmpty()) {
                rememberYtmError(String.join(" | ", errors));
                return relabel(fallback, "YouTube 음원 대체");
            }
        } catch (Exception e) {
            errors.add("YouTube fallback: " + compactError(e));
        }

        String detail = String.join(" | ", errors);
        rememberYtmError(detail);
        throw new IllegalStateException("YouTube Music 검색 실패" + (detail.isEmpty() ? "" : " · " + detail));
    }

    private List<SearchResult> searchYoutubeMusicInnertube(String query, int limit) throws Exception {
        YtmConfig config = loadYtmConfig();
        List<String> attempts = new ArrayList<>();
        List<SearchResult> merged = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int batchLimit = Math.max(limit, 40);

        // Match current ytmusicapi's anonymous transport first: WEB_REMIX context,
        // current daily client version, visitor id when available, and no API key.
        String anonymousEndpoint = "https://music.youtube.com/youtubei/v1/search?alt=json";
        String[] params = new String[]{YTM_SONGS_PARAMS, YTM_SONGS_PARAMS_ALT, ""};
        String[] labels = new String[]{"songs-web", "songs-alt", "unfiltered"};
        for (int i = 0; i < params.length; i++) {
            try {
                List<SearchResult> batch = requestYoutubeMusicInnertube(
                        anonymousEndpoint, query, params[i], batchLimit, config);
                appendUnique(merged, seen, batch, Math.max(limit * 2, 60));
                attempts.add("anon-" + labels[i] + "=" + batch.size());
                if (merged.size() >= limit) break;
            } catch (Exception e) {
                attempts.add("anon-" + labels[i] + "=" + compactError(e));
            }
        }

        // A few Android/network combinations reach the Google APIs host more reliably
        // than music.youtube.com. It is the same Innertube search protocol, using the
        // public WEB_REMIX key and the same client context.
        if (merged.isEmpty() && config.apiKey != null && !config.apiKey.isEmpty()) {
            try {
                String googleApisEndpoint = "https://youtubei.googleapis.com/youtubei/v1/search?alt=json&key="
                        + URLEncoder.encode(config.apiKey, StandardCharsets.UTF_8);
                List<SearchResult> batch = requestYoutubeMusicInnertube(
                        googleApisEndpoint, query, YTM_SONGS_PARAMS, batchLimit, config);
                appendUnique(merged, seen, batch, Math.max(limit * 2, 60));
                attempts.add("googleapis-songs=" + batch.size());
            } catch (Exception e) {
                attempts.add("googleapis-songs=" + compactError(e));
            }
        }

        // Some regions still accept/expect the public WEB_REMIX key. Only try this
        // compatibility request if the anonymous form returned nothing.
        if (merged.isEmpty() && config.apiKey != null && !config.apiKey.isEmpty()) {
            try {
                String keyedEndpoint = "https://music.youtube.com/youtubei/v1/search?alt=json&key="
                        + URLEncoder.encode(config.apiKey, StandardCharsets.UTF_8)
                        + "&prettyPrint=false";
                List<SearchResult> batch = requestYoutubeMusicInnertube(
                        keyedEndpoint, query, YTM_SONGS_PARAMS, batchLimit, config);
                appendUnique(merged, seen, batch, Math.max(limit * 2, 60));
                attempts.add("keyed-songs=" + batch.size());
            } catch (Exception e) {
                attempts.add("keyed-songs=" + compactError(e));
            }
        }

        if (!merged.isEmpty()) return merged;
        throw new IllegalStateException(String.join(" / ", attempts));
    }

    private List<SearchResult> requestYoutubeMusicInnertube(String endpoint, String query,
                                                             String params, int limit,
                                                             YtmConfig config) throws Exception {
        JSONObject client = new JSONObject()
                .put("clientName", "WEB_REMIX")
                .put("clientVersion", config.clientVersion)
                .put("hl", "ko")
                .put("gl", "KR");
        if (config.visitorData != null && !config.visitorData.isEmpty()) {
            client.put("visitorData", config.visitorData);
        }
        JSONObject context = new JSONObject()
                .put("client", client)
                .put("user", new JSONObject());
        JSONObject body = new JSONObject()
                .put("context", context)
                .put("query", query);
        if (params != null && !params.isEmpty()) body.put("params", params);

        JSONObject response = new JSONObject(postJson(endpoint, body.toString(), config));
        List<SearchResult> batch = new ArrayList<>();
        Set<String> batchSeen = new HashSet<>();
        collectMusicResponsiveItems(response, batch, batchSeen, limit);
        if (batch.isEmpty() && (params == null || params.isEmpty())) {
            collectGenericVideoItems(response, batch, batchSeen, limit);
        }
        return batch;
    }

    private List<SearchResult> searchYoutubeMusicPage(String query, int limit) throws Exception {
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
        List<SearchResult> merged = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<String> attempts = new ArrayList<>();
        String[] params = new String[]{YTM_SONGS_PARAMS_ALT, YTM_SONGS_PARAMS, ""};

        for (String param : params) {
            try {
                String url = "https://music.youtube.com/search?q=" + encoded;
                if (!param.isEmpty()) {
                    url += "&sp=" + URLEncoder.encode(param, StandardCharsets.UTF_8);
                }
                String page = readYtmUrl(url);
                String json = extractAssignedJson(page, "ytInitialData");
                if (json.isEmpty()) {
                    attempts.add(param.isEmpty() ? "page-unfiltered=json 없음" : "page-filtered=json 없음");
                    continue;
                }
                JSONObject response = new JSONObject(json);
                List<SearchResult> batch = new ArrayList<>();
                Set<String> batchSeen = new HashSet<>();
                collectMusicResponsiveItems(response, batch, batchSeen, Math.max(limit, 40));
                if (batch.isEmpty() && param.isEmpty()) {
                    collectGenericVideoItems(response, batch, batchSeen, Math.max(limit, 40));
                }
                appendUnique(merged, seen, batch, Math.max(limit, 40));
                attempts.add((param.isEmpty() ? "page-unfiltered" : "page-filtered") + "=" + batch.size());
                if (merged.size() >= limit) break;
            } catch (Exception e) {
                attempts.add((param.isEmpty() ? "page-unfiltered" : "page-filtered") + "=" + compactError(e));
            }
        }
        if (!merged.isEmpty()) return merged;
        throw new IllegalStateException(String.join(" / ", attempts));
    }

    private static final class YtmConfig {
        final String apiKey;
        final String clientVersion;
        final String visitorData;
        YtmConfig(String apiKey, String clientVersion, String visitorData) {
            this.apiKey = apiKey;
            this.clientVersion = clientVersion;
            this.visitorData = visitorData;
        }
    }

    private YtmConfig loadYtmConfig() {
        String apiKey = YTM_FALLBACK_API_KEY;
        String visitor = "";
        try {
            String page = readYtmUrl("https://music.youtube.com/");

            // ytmusicapi gets anonymous visitor context from ytcfg.set(...).
            // Parse complete balanced JSON objects because ytcfg contains nested objects
            // and a non-greedy regex can stop at the first inner closing brace.
            int from = 0;
            while (from < page.length()) {
                int markerAt = page.indexOf("ytcfg.set", from);
                if (markerAt < 0) break;
                String json = extractAssignedJson(page.substring(markerAt), "ytcfg.set");
                if (!json.isEmpty()) {
                    try {
                        JSONObject cfg = new JSONObject(json);
                        apiKey = firstNonEmpty(cfg.optString("INNERTUBE_API_KEY"), apiKey);
                        visitor = firstNonEmpty(cfg.optString("VISITOR_DATA"), visitor);
                    } catch (Exception ignored) { }
                }
                from = markerAt + "ytcfg.set".length();
            }

            // Loose fallbacks for page variants that inline the values elsewhere.
            apiKey = firstRegex(page,
                    "\"INNERTUBE_API_KEY\"\\s*:\\s*\"([^\"]+)\"",
                    apiKey);
            visitor = firstRegex(page,
                    "\"VISITOR_DATA\"\\s*:\\s*\"([^\"]+)\"",
                    visitor);
        } catch (Exception ignored) { }
        // WEB_REMIX expects the daily anonymous version. Never replace it with the
        // generic INNERTUBE_CLIENT_VERSION found in the page bootstrap.
        return new YtmConfig(apiKey, currentYtmClientVersion(), visitor);
    }

    static String currentYtmClientVersion() {
        SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return "1." + format.format(new Date()) + YTM_CLIENT_VERSION_SUFFIX;
    }

    private static String firstRegex(String text, String expression, String fallback) {
        try {
            Matcher m = Pattern.compile(expression).matcher(text == null ? "" : text);
            return m.find() ? m.group(1) : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static String postJson(String url, String json, YtmConfig config) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(12000);
        conn.setReadTimeout(18000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("User-Agent", YTM_USER_AGENT);
        conn.setRequestProperty("Accept", "*/*");
        conn.setRequestProperty("Accept-Language", "ko-KR,ko;q=0.9,en-US;q=0.7,en;q=0.5");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Origin", "https://music.youtube.com");
        conn.setRequestProperty("X-Origin", "https://music.youtube.com");
        conn.setRequestProperty("Referer", "https://music.youtube.com/");
        conn.setRequestProperty("X-Goog-Api-Format-Version", "1");
        conn.setRequestProperty("Cookie", YTM_CONSENT_COOKIE);
        // Keep the request close to ytmusicapi's anonymous WEB_REMIX transport.
        // The client identity lives in the JSON context; forcing stale numeric client
        // headers can cause otherwise valid anonymous searches to be rejected.
        if (config.visitorData != null && !config.visitorData.isEmpty()) {
            conn.setRequestProperty("X-Goog-Visitor-Id", config.visitorData);
        }
        try (OutputStream out = conn.getOutputStream()) {
            out.write(json.getBytes(StandardCharsets.UTF_8));
        }
        int code = conn.getResponseCode();
        InputStream in = code >= 200 && code < 400 ? conn.getInputStream() : conn.getErrorStream();
        if (in == null) throw new IllegalStateException("HTTP " + code);
        StringBuilder body = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) body.append(line);
        } finally {
            conn.disconnect();
        }
        if (code < 200 || code >= 400) {
            String sample = body.length() > 180 ? body.substring(0, 180) : body.toString();
            throw new IllegalStateException("HTTP " + code + (sample.isEmpty() ? "" : " · " + sample));
        }
        return body.toString();
    }

    private static String readYtmUrl(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(12000);
        conn.setReadTimeout(18000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", YTM_USER_AGENT);
        conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8");
        conn.setRequestProperty("Accept-Language", "ko-KR,ko;q=0.9,en-US;q=0.7,en;q=0.5");
        conn.setRequestProperty("Cookie", YTM_CONSENT_COOKIE);
        try {
            int code = conn.getResponseCode();
            InputStream in = code >= 200 && code < 400 ? conn.getInputStream() : conn.getErrorStream();
            if (in == null) throw new IllegalStateException("HTTP " + code);
            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) body.append(line).append('\n');
            }
            if (code < 200 || code >= 400) throw new IllegalStateException("HTTP " + code);
            return body.toString();
        } finally {
            conn.disconnect();
        }
    }

    private static String extractAssignedJson(String text, String marker) {
        if (text == null || text.isEmpty() || marker == null || marker.isEmpty()) return "";
        int from = 0;
        while (from < text.length()) {
            int markerAt = text.indexOf(marker, from);
            if (markerAt < 0) return "";
            int start = text.indexOf('{', markerAt + marker.length());
            if (start < 0) return "";

            int depth = 0;
            boolean quoted = false;
            boolean escaped = false;
            for (int i = start; i < text.length(); i++) {
                char ch = text.charAt(i);
                if (quoted) {
                    if (escaped) escaped = false;
                    else if (ch == '\\') escaped = true;
                    else if (ch == '"') quoted = false;
                    continue;
                }
                if (ch == '"') {
                    quoted = true;
                } else if (ch == '{') {
                    depth++;
                } else if (ch == '}') {
                    depth--;
                    if (depth == 0) return text.substring(start, i + 1);
                }
            }
            from = markerAt + marker.length();
        }
        return "";
    }

    private static void collectMusicResponsiveItems(Object node, List<SearchResult> out,
                                                    Set<String> seen, int limit) {
        if (node == null || out.size() >= limit) return;
        if (node instanceof JSONObject) {
            JSONObject obj = (JSONObject) node;
            JSONObject renderer = obj.optJSONObject("musicResponsiveListItemRenderer");
            if (renderer != null) {
                SearchResult parsed = parseMusicResponsiveItem(renderer);
                if (parsed != null && seen.add(parsed.id)) out.add(parsed);
                if (out.size() >= limit) return;
            }
            JSONObject twoRow = obj.optJSONObject("musicTwoRowItemRenderer");
            if (twoRow != null) {
                SearchResult parsed = parseMusicTwoRowItem(twoRow);
                if (parsed != null && seen.add(parsed.id)) out.add(parsed);
                if (out.size() >= limit) return;
            }
            java.util.Iterator<String> keys = obj.keys();
            while (keys.hasNext() && out.size() < limit) {
                collectMusicResponsiveItems(obj.opt(keys.next()), out, seen, limit);
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length() && out.size() < limit; i++) {
                collectMusicResponsiveItems(array.opt(i), out, seen, limit);
            }
        }
    }

    private static void collectGenericVideoItems(Object node, List<SearchResult> out,
                                                 Set<String> seen, int limit) {
        if (node == null || out.size() >= limit) return;
        if (node instanceof JSONObject) {
            JSONObject obj = (JSONObject) node;
            String[] rendererKeys = new String[]{"playlistPanelVideoRenderer", "playlistVideoRenderer", "videoRenderer"};
            for (String key : rendererKeys) {
                JSONObject renderer = obj.optJSONObject(key);
                if (renderer == null) continue;
                SearchResult parsed = parseGenericVideoItem(renderer);
                if (parsed != null && seen.add(parsed.id)) out.add(parsed);
                if (out.size() >= limit) return;
            }
            java.util.Iterator<String> keys = obj.keys();
            while (keys.hasNext() && out.size() < limit) {
                collectGenericVideoItems(obj.opt(keys.next()), out, seen, limit);
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length() && out.size() < limit; i++) {
                collectGenericVideoItems(array.opt(i), out, seen, limit);
            }
        }
    }

    private static SearchResult parseGenericVideoItem(JSONObject renderer) {
        String id = findVideoId(renderer);
        if (id.length() != 11) return null;
        String title = textFromRuns(renderer.optJSONObject("title"));
        if (title.isEmpty()) title = textFromRuns(renderer.optJSONObject("headline"));
        if (title.isEmpty()) return null;

        String artist = "";
        String album = "";
        long duration = 0L;
        String byline = textFromRuns(renderer.optJSONObject("longBylineText"));
        if (byline.isEmpty()) byline = textFromRuns(renderer.optJSONObject("shortBylineText"));
        if (byline.isEmpty()) byline = textFromRuns(renderer.optJSONObject("subtitle"));
        if (!byline.isEmpty()) artist = byline;

        String length = textFromRuns(renderer.optJSONObject("lengthText"));
        if (!length.isEmpty()) duration = parseDurationText(length);
        String thumbnail = findLargestThumbnail(renderer);
        return new SearchResult(id, title, artist,
                "https://www.youtube.com/watch?v=" + id,
                duration, thumbnail, 40, "YTM_OTHER", album);
    }

    private static String summarizeYtmResponse(JSONObject response) {
        if (response == null) return "null";
        List<String> top = new ArrayList<>();
        java.util.Iterator<String> keys = response.keys();
        while (keys.hasNext() && top.size() < 6) top.add(keys.next());
        int responsive = countJsonKey(response, "musicResponsiveListItemRenderer");
        int panels = countJsonKey(response, "playlistPanelVideoRenderer");
        int videos = countJsonKey(response, "playlistVideoRenderer") + countJsonKey(response, "videoRenderer");
        return "keys=" + String.join(",", top)
                + ", responsive=" + responsive + ", panel=" + panels + ", video=" + videos;
    }

    private static int countJsonKey(Object node, String wanted) {
        if (node == null) return 0;
        int count = 0;
        if (node instanceof JSONObject) {
            JSONObject obj = (JSONObject) node;
            if (obj.has(wanted)) count++;
            java.util.Iterator<String> keys = obj.keys();
            while (keys.hasNext()) count += countJsonKey(obj.opt(keys.next()), wanted);
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) count += countJsonKey(array.opt(i), wanted);
        }
        return count;
    }

    private static SearchResult parseMusicResponsiveItem(JSONObject renderer) {
        String id = findVideoId(renderer);
        if (id.isEmpty() || id.length() != 11) return null;

        String videoType = findStringValue(renderer, "musicVideoType");
        String normalizedType = lower(videoType);
        if (normalizedType.contains("podcast_episode")) return null;

        List<String> primary = extractTextRuns(renderer.optJSONArray("flexColumns"), 0);
        List<String> secondary = extractTextRuns(renderer.optJSONArray("flexColumns"), 1);
        if (primary.isEmpty()) return null;

        String title = firstMeaningful(primary);
        String artist = findFlexRunByBrowsePrefix(renderer.optJSONArray("flexColumns"), 1, "UC");
        String album = findFlexRunByBrowsePrefix(renderer.optJSONArray("flexColumns"), 1, "MPRE");
        long duration = 0L;

        List<String> metadata = new ArrayList<>();
        boolean episodeLike = false;
        for (String s : secondary) {
            String t = s == null ? "" : s.trim();
            if (t.isEmpty() || "•".equals(t) || "·".equals(t)) continue;
            String lt = lower(t);
            if (t.matches("\\d{1,2}:\\d{2}(?::\\d{2})?")) {
                duration = parseDurationText(t);
                continue;
            }
            if (isYtmTypeLabel(lt)) {
                if (containsAny(lt, "episode", "에피소드", "podcast", "팟캐스트")) episodeLike = true;
                continue;
            }
            metadata.add(t);
        }
        if (episodeLike) return null;

        if (artist.isEmpty() && !metadata.isEmpty()) artist = metadata.get(0);
        if (album.isEmpty()) {
            for (String candidate : metadata) {
                if (!candidate.equals(artist)) {
                    album = candidate;
                    break;
                }
            }
        }

        if (duration == 0L) {
            List<String> fixed = extractTextRuns(renderer.optJSONArray("fixedColumns"), 0);
            for (String s : fixed) {
                if (s != null && s.trim().matches("\\d{1,2}:\\d{2}(?::\\d{2})?")) {
                    duration = parseDurationText(s.trim());
                    break;
                }
            }
        }

        String thumbnail = findLargestThumbnail(renderer);
        boolean audioTrack = normalizedType.contains("music_video_type_atv");
        boolean musicVideo = normalizedType.contains("music_video_type_omv")
                || normalizedType.contains("music_video_type_ugc");

        String badge = audioTrack ? "YTM_AUDIO" : (musicVideo ? "YTM_VIDEO" : "YTM_OTHER");
        int baseScore = audioTrack ? 360 : (musicVideo ? 120 : 60);
        return new SearchResult(id, title, artist,
                "https://www.youtube.com/watch?v=" + id,
                duration, thumbnail, baseScore, badge, album);
    }

    private static SearchResult parseMusicTwoRowItem(JSONObject renderer) {
        String id = findVideoId(renderer);
        if (id.isEmpty() || id.length() != 11) return null;
        String videoType = findStringValue(renderer, "musicVideoType");
        String normalizedType = lower(videoType);
        if (normalizedType.contains("podcast_episode")) return null;

        String title = textFromRuns(renderer.optJSONObject("title"));
        String subtitle = textFromRuns(renderer.optJSONObject("subtitle"));
        if (title.isEmpty()) return null;
        String artist = subtitle;
        int dot = subtitle.indexOf(" • ");
        if (dot > 0) artist = subtitle.substring(0, dot).trim();

        String thumbnail = findLargestThumbnail(renderer);
        boolean audioTrack = normalizedType.contains("music_video_type_atv");
        String badge = audioTrack ? "YTM_AUDIO" : "YTM_VIDEO";
        int baseScore = audioTrack ? 330 : 100;
        return new SearchResult(id, title, artist,
                "https://www.youtube.com/watch?v=" + id,
                0L, thumbnail, baseScore, badge, "");
    }

    private static String findFlexRunByBrowsePrefix(JSONArray columns, int index, String prefix) {
        if (columns == null || index < 0 || index >= columns.length()) return "";
        JSONObject wrapper = columns.optJSONObject(index);
        if (wrapper == null) return "";
        JSONObject column = wrapper.optJSONObject("musicResponsiveListItemFlexColumnRenderer");
        if (column == null) column = wrapper.optJSONObject("musicResponsiveListItemFixedColumnRenderer");
        if (column == null) return "";
        JSONObject text = column.optJSONObject("text");
        JSONArray runs = text == null ? null : text.optJSONArray("runs");
        if (runs == null) return "";
        for (int i = 0; i < runs.length(); i++) {
            JSONObject run = runs.optJSONObject(i);
            if (run == null) continue;
            JSONObject nav = run.optJSONObject("navigationEndpoint");
            JSONObject browse = nav == null ? null : nav.optJSONObject("browseEndpoint");
            String browseId = browse == null ? "" : browse.optString("browseId", "");
            if (browseId.startsWith(prefix)) return run.optString("text", "").trim();
        }
        return "";
    }

    private static boolean isYtmTypeLabel(String value) {
        return containsAny(value,
                "song", "songs", "곡", "노래",
                "video", "videos", "동영상",
                "episode", "episodes", "에피소드",
                "podcast", "팟캐스트");
    }

    private static String findStringValue(Object node, String wantedKey) {
        if (node == null) return "";
        if (node instanceof JSONObject) {
            JSONObject obj = (JSONObject) node;
            if (obj.has(wantedKey)) {
                String value = obj.optString(wantedKey, "");
                if (!value.isEmpty()) return value;
            }
            java.util.Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String value = findStringValue(obj.opt(keys.next()), wantedKey);
                if (!value.isEmpty()) return value;
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) {
                String value = findStringValue(array.opt(i), wantedKey);
                if (!value.isEmpty()) return value;
            }
        }
        return "";
    }

    private static String textFromRuns(JSONObject textObject) {
        if (textObject == null) return "";
        String simple = textObject.optString("simpleText", "").trim();
        if (!simple.isEmpty()) return simple;
        JSONArray runs = textObject.optJSONArray("runs");
        if (runs == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < runs.length(); i++) {
            JSONObject run = runs.optJSONObject(i);
            if (run != null) b.append(run.optString("text", ""));
        }
        return b.toString().trim();
    }

    private static List<String> extractTextRuns(JSONArray columns, int index) {
        List<String> out = new ArrayList<>();
        if (columns == null || index < 0 || index >= columns.length()) return out;
        JSONObject wrapper = columns.optJSONObject(index);
        if (wrapper == null) return out;

        JSONObject column = wrapper.optJSONObject("musicResponsiveListItemFlexColumnRenderer");
        if (column == null) column = wrapper.optJSONObject("musicResponsiveListItemFixedColumnRenderer");
        if (column == null) return out;

        JSONObject text = column.optJSONObject("text");
        JSONArray runs = text == null ? null : text.optJSONArray("runs");
        if (runs == null) return out;
        for (int i = 0; i < runs.length(); i++) {
            JSONObject run = runs.optJSONObject(i);
            if (run != null) out.add(run.optString("text", ""));
        }
        return out;
    }

    private static String firstMeaningful(List<String> values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) return value.trim();
        }
        return "";
    }

    private static String findVideoId(Object node) {
        if (node == null) return "";
        if (node instanceof JSONObject) {
            JSONObject obj = (JSONObject) node;
            String direct = obj.optString("videoId", "");
            if (direct.length() == 11) return direct;
            java.util.Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String value = findVideoId(obj.opt(keys.next()));
                if (!value.isEmpty()) return value;
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) {
                String value = findVideoId(array.opt(i));
                if (!value.isEmpty()) return value;
            }
        }
        return "";
    }

    private static String findLargestThumbnail(Object node) {
        List<String> urls = new ArrayList<>();
        collectThumbnailUrls(node, urls);
        return urls.isEmpty() ? "" : urls.get(urls.size() - 1);
    }

    private static void collectThumbnailUrls(Object node, List<String> urls) {
        if (node == null) return;
        if (node instanceof JSONObject) {
            JSONObject obj = (JSONObject) node;
            String url = obj.optString("url", "");
            if (url.startsWith("http") && (url.contains("ggpht") || url.contains("ytimg"))) urls.add(url);
            java.util.Iterator<String> keys = obj.keys();
            while (keys.hasNext()) collectThumbnailUrls(obj.opt(keys.next()), urls);
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) collectThumbnailUrls(a.opt(i), urls);
        }
    }

    private static long parseDurationText(String value) {
        try {
            String[] p = value.split(":");
            long total = 0L;
            for (String s : p) total = total * 60L + Long.parseLong(s);
            return total;
        } catch (Exception ignored) {
            return 0L;
        }
    }

    public String getLastYtmError() {
        return appContext.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
                .getString(KEY_LAST_YTM_ERROR, "");
    }

    private void rememberYtmError(String error) {
        appContext.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_LAST_YTM_ERROR, error == null ? "" : error).apply();
    }

    private static List<SearchResult> relabel(List<SearchResult> input, String source) {
        List<SearchResult> out = new ArrayList<>();
        for (SearchResult item : input) {
            out.add(new SearchResult(item.id, item.title, item.channel, item.url,
                    item.durationSeconds, item.thumbnail, item.score,
                    source + (item.badge == null || item.badge.isEmpty() ? "" : " · " + item.badge),
                    item.album));
        }
        return out;
    }

    public List<SearchResult> searchYoutube(String query, int limit) throws Exception {
        List<String> errors = new ArrayList<>();

        // Fast path: yt-dlp search. Nightly updates are still used by the app.
        try {
            List<SearchResult> raw = executeFlatSearch("ytsearch" + limit + ":" + query, limit);
            if (!raw.isEmpty()) {
                return tagResults(MusicRanker.rank(raw, query), "", "YouTube");
            }
            errors.add("yt-dlp: 결과 없음");
        } catch (Exception e) {
            errors.add("yt-dlp: " + compactError(e));
        }

        // Resilient fallback: parse the public YouTube search page directly.
        // This path only needs search metadata and therefore does not depend on
        // playback-format extraction, PO tokens, or a working yt-dlp extractor.
        try {
            List<SearchResult> raw = searchYoutubeHtml(query, limit);
            if (!raw.isEmpty()) {
                return tagResults(MusicRanker.rank(raw, query), "", "YouTube");
            }
            errors.add("YouTube web: 결과 없음");
        } catch (Exception e) {
            errors.add("YouTube web: " + compactError(e));
        }

        throw new IllegalStateException("YouTube 검색 실패 · " + String.join(" | ", errors));
    }

    private List<SearchResult> searchYoutubeHtml(String query, int limit) throws Exception {
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String page = readYoutubeUrl("https://www.youtube.com/results?search_query=" + encoded);
        String json = extractAssignedJson(page, "ytInitialData");
        if (json.isEmpty()) throw new IllegalStateException("ytInitialData 없음");

        JSONObject response = new JSONObject(json);
        List<SearchResult> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        collectGenericVideoItems(response, out, seen, Math.max(1, limit));
        if (out.isEmpty()) throw new IllegalStateException("videoRenderer 없음");
        return out;
    }

    private static String readYoutubeUrl(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(12000);
        conn.setReadTimeout(18000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", YTM_USER_AGENT);
        conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8");
        conn.setRequestProperty("Accept-Language", "ko-KR,ko;q=0.9,en-US;q=0.7,en;q=0.5");
        conn.setRequestProperty("Cookie", YTM_CONSENT_COOKIE);
        try {
            int code = conn.getResponseCode();
            InputStream in = code >= 200 && code < 400 ? conn.getInputStream() : conn.getErrorStream();
            if (in == null) throw new IllegalStateException("HTTP " + code);
            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) body.append(line).append('\n');
            }
            if (code < 200 || code >= 400) throw new IllegalStateException("HTTP " + code);
            return body.toString();
        } finally {
            conn.disconnect();
        }
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
        request.addOption("--socket-timeout", "12");
        request.addOption("--retries", "1");
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
            boolean ytmCatalogSong = item.badge != null && item.badge.contains("YouTube Music");
            if (officialOnly && !directCreatorPlatform && !ytmCatalogSong && !isOfficialCandidate(title, channel)) continue;
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
