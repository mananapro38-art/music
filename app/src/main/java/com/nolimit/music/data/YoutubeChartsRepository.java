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

    public List<SearchResult> loadKoreaTopSongs(int limit) throws Exception {
        String page = get("https://charts.youtube.com/charts/TopSongs/kr");
        Matcher matcher = API_KEY.matcher(page);
        String key = matcher.find() ? matcher.group(1) : FALLBACK_KEY;

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

        String raw = post("https://charts.youtube.com/youtubei/v1/browse?alt=json&key=" + key, body.toString());
        JSONObject root = new JSONObject(raw);
        JSONObject content = root.getJSONObject("contents")
                .getJSONObject("sectionListRenderer")
                .getJSONArray("contents")
                .getJSONObject(0)
                .getJSONObject("musicAnalyticsSectionRenderer");
        JSONArray trackTypes = content.getJSONArray("trackTypes");
        JSONArray rows = trackTypes.getJSONObject(0).getJSONArray("trackViews");

        List<SearchResult> result = new ArrayList<>();
        int count = Math.min(limit, rows.length());
        for (int i = 0; i < count; i++) {
            JSONObject row = rows.getJSONObject(i);
            String id = row.optString("encryptedVideoId");
            if (id.isEmpty()) id = row.optString("id");
            if (id.isEmpty()) continue;
            String title = row.optString("title", row.optString("name", "제목 없음"));
            String artist = artists(row.optJSONArray("artists"));
            String thumbnail = "";
            JSONObject thumb = row.optJSONObject("thumbnail");
            if (thumb != null) {
                JSONArray thumbs = thumb.optJSONArray("thumbnails");
                if (thumbs != null && thumbs.length() > 0) thumbnail = thumbs.getJSONObject(thumbs.length() - 1).optString("url");
            }
            result.add(new SearchResult(id, title, artist, "https://www.youtube.com/watch?v=" + id, 0, thumbnail, 1000 - i, "YouTube 차트 #" + (i + 1)));
        }
        return result;
    }

    private static String artists(JSONArray array) {
        if (array == null) return "YouTube Music";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < array.length(); i++) {
            String name = array.optJSONObject(i) == null ? "" : array.optJSONObject(i).optString("name");
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
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) NoLimitMusic/0.4");
        return read(c);
    }

    private static String post(String address, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) NoLimitMusic/0.4");
        try (OutputStream os = c.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        return read(c);
    }

    private static String read(HttpURLConnection c) throws Exception {
        int code = c.getResponseCode();
        InputStream stream = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
        if (stream == null) throw new IllegalStateException("HTTP " + code);
        StringBuilder out = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) out.append(line);
        } finally {
            c.disconnect();
        }
        if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code);
        return out.toString();
    }
}
