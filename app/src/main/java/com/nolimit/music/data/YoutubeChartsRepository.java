package com.nolimit.music.data;

import com.nolimit.music.model.SearchResult;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class YoutubeChartsRepository {
    private static final Pattern API_KEY = Pattern.compile("\\\"INNERTUBE_API_KEY\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private static final String FALLBACK_KEY = "AIzaSyCzEW7JUJdSql0-2V4tHUb6laYm4iAE_dM";
    private static final String CHART_PAGE = "https://charts.youtube.com/charts/TopSongs/kr";
    private static final String CHART_ORIGIN = "https://charts.youtube.com";

    public List<SearchResult> loadKoreaTopSongs(int limit) throws Exception {
        String key = FALLBACK_KEY;

        // The public chart page occasionally rejects non-browser GETs. That should not
        // prevent the chart itself from loading, so API-key discovery is best-effort.
        try {
            String page = get(CHART_PAGE);
            Matcher matcher = API_KEY.matcher(page);
            if (matcher.find()) key = matcher.group(1);
        } catch (Exception ignored) { }

        JSONObject client = new JSONObject()
                .put("clientName", "WEB_MUSIC_ANALYTICS")
                .put("clientVersion", "0.2")
                .put("experimentIds", new JSONArray())
                .put("experimentsToken", "")
                .put("gl", "KR")
                .put("hl", "ko")
                .put("theme", "MUSIC");
        JSONObject context = new JSONObject()
                .put("capabilities", new JSONObject())
                .put("client", client)
                .put("request", new JSONObject().put("internalExperimentFlags", new JSONArray()));
        JSONObject body = new JSONObject()
                .put("browseId", "FEmusic_analytics_charts_home")
                .put("context", context)
                .put("query", "chart_params_type=WEEK&perspective=CHART&flags=viral_video_chart&selected_chart=TRACKS&chart_params_id=weekly:0:0:kr");

        String raw = postCharts("https://charts.youtube.com/youtubei/v1/browse?alt=json&key=" + key, body.toString());
        JSONObject root = new JSONObject(raw);
        JSONArray rows = findArrayByKey(root, "trackViews");
        if (rows == null) throw new IllegalStateException("차트 응답에서 trackViews를 찾지 못했습니다.");

        List<SearchResult> result = new ArrayList<>();
        int rank = 0;
        for (int i = 0; i < rows.length() && result.size() < limit; i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;

            String id = row.optString("encryptedVideoId");
            if (id.isEmpty()) id = row.optString("id");
            if (id.isEmpty()) continue;

            String title = row.optString("title");
            if (title.isEmpty()) title = row.optString("name", "제목 없음");
            String artist = artists(row.optJSONArray("artists"));
            String thumbnail = extractThumbnail(row);

            rank++;
            result.add(new SearchResult(
                    id,
                    title,
                    artist,
                    "https://www.youtube.com/watch?v=" + id,
                    0,
                    thumbnail,
                    1000 - rank,
                    "YouTube 차트 #" + rank
            ));
        }

        if (result.isEmpty()) throw new IllegalStateException("차트 응답은 왔지만 곡 항목이 비어 있습니다.");
        return result;
    }

    private static JSONArray findArrayByKey(Object node, String key) {
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            JSONArray direct = object.optJSONArray(key);
            if (direct != null) return direct;
            JSONArray names = object.names();
            if (names == null) return null;
            for (int i = 0; i < names.length(); i++) {
                Object child = object.opt(names.optString(i));
                JSONArray found = findArrayByKey(child, key);
                if (found != null) return found;
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) {
                JSONArray found = findArrayByKey(array.opt(i), key);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static String extractThumbnail(JSONObject row) {
        JSONObject thumb = row.optJSONObject("thumbnail");
        if (thumb == null) return "";
        JSONArray thumbs = thumb.optJSONArray("thumbnails");
        if (thumbs == null || thumbs.length() == 0) return "";
        JSONObject best = thumbs.optJSONObject(thumbs.length() - 1);
        return best == null ? "" : best.optString("url");
    }

    private static String artists(JSONArray array) {
        if (array == null) return "YouTube Music";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < array.length(); i++) {
            JSONObject artist = array.optJSONObject(i);
            String name = artist == null ? "" : artist.optString("name");
            if (name.isEmpty()) continue;
            if (out.length() > 0) out.append(", ");
            out.append(name);
        }
        return out.length() == 0 ? "YouTube Music" : out.toString();
    }

    private static String get(String address) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(10000);
        c.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        c.setRequestProperty("Accept-Language", "ko-KR,ko;q=0.9,en;q=0.8");
        c.setRequestProperty("User-Agent", browserUserAgent());
        return read(c);
    }

    private static String postCharts(String address, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("Accept-Language", "ko-KR,ko;q=0.9,en;q=0.8");
        c.setRequestProperty("User-Agent", browserUserAgent());
        c.setRequestProperty("Origin", CHART_ORIGIN);
        c.setRequestProperty("Referer", CHART_PAGE);
        c.setRequestProperty("X-Origin", CHART_ORIGIN);
        c.setRequestProperty("X-YouTube-Client-Name", "31");
        c.setRequestProperty("X-YouTube-Client-Version", "0.2");
        try (OutputStream os = c.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        return read(c);
    }

    private static String browserUserAgent() {
        return "Mozilla/5.0 (Linux; Android 15; Mobile) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36";
    }

    private static String read(HttpURLConnection c) throws Exception {
        int code = c.getResponseCode();
        InputStream stream = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
        if (stream == null) {
            c.disconnect();
            throw new IllegalStateException("HTTP " + code);
        }
        StringBuilder out = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) out.append(line);
        } finally {
            c.disconnect();
        }
        if (code < 200 || code >= 300) {
            String detail = out.toString().replace('\n', ' ').trim();
            if (detail.length() > 180) detail = detail.substring(0, 180) + "…";
            throw new IllegalStateException("HTTP " + code + (detail.isEmpty() ? "" : " · " + detail));
        }
        return out.toString();
    }
}
