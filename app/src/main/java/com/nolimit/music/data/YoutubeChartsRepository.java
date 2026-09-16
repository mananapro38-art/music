package com.nolimit.music.data;

import com.nolimit.music.model.SearchResult;
import com.nolimit.music.util.ArtworkLoader;

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
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class YoutubeChartsRepository {
    public enum Category {
        TOP_SONGS,
        TOP_ARTISTS,
        TOP_VIDEOS,
        TRENDING
    }

    private static final Pattern API_KEY = Pattern.compile("\\\"INNERTUBE_API_KEY\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private static final String FALLBACK_KEY = "AIzaSyCzEW7JUJdSql0-2V4tHUb6laYm4iAE_dM";
    private static final String CHART_ORIGIN = "https://charts.youtube.com";

    public List<SearchResult> loadKoreaTopSongs(int limit) throws Exception {
        return loadChart(Category.TOP_SONGS, "kr", limit);
    }

    public List<SearchResult> loadChart(Category category, String countryCode, int limit) throws Exception {
        String country = countryCode == null ? "" : countryCode.trim().toLowerCase(Locale.ROOT);
        String chartName = chartPageName(category);
        String pageCountry = country.isEmpty() ? "global" : country;
        String chartPage = CHART_ORIGIN + "/charts/" + chartName + "/" + pageCountry;

        String key = FALLBACK_KEY;
        try {
            String page = get(chartPage);
            Matcher matcher = API_KEY.matcher(page);
            if (matcher.find()) key = matcher.group(1);
        } catch (Exception ignored) { }

        String gl = country.isEmpty() ? "US" : country.toUpperCase(Locale.ROOT);
        JSONObject client = new JSONObject()
                .put("clientName", "WEB_MUSIC_ANALYTICS")
                .put("clientVersion", "0.2")
                .put("experimentIds", new JSONArray())
                .put("experimentsToken", "")
                .put("gl", gl)
                .put("hl", "ko")
                .put("theme", "MUSIC");
        JSONObject context = new JSONObject()
                .put("capabilities", new JSONObject())
                .put("client", client)
                .put("request", new JSONObject().put("internalExperimentFlags", new JSONArray()));

        String chartId = "weekly:0:0" + (country.isEmpty() ? "" : ":" + country);
        JSONObject body = new JSONObject()
                .put("browseId", "FEmusic_analytics_charts_home")
                .put("context", context)
                .put("query", "chart_params_type=WEEK&perspective=CHART&flags=viral_video_chart&selected_chart=TRACKS&chart_params_id=" + chartId);

        String raw = postCharts(
                "https://charts.youtube.com/youtubei/v1/browse?alt=json&key=" + key,
                body.toString(),
                chartPage
        );
        JSONObject root = new JSONObject(raw);
        JSONObject analytics = findAnalyticsRenderer(root);
        if (analytics == null) throw new IllegalStateException("차트 응답에서 musicAnalyticsSectionRenderer를 찾지 못했습니다.");

        JSONArray rows = rowsFor(analytics, category);
        if (rows == null || rows.length() == 0) {
            String fallbackKey = category == Category.TOP_ARTISTS ? "artistViews"
                    : category == Category.TOP_SONGS ? "trackViews" : "videoViews";
            rows = findLargestArrayByKey(analytics, fallbackKey);
        }
        if (rows == null || rows.length() == 0) throw new IllegalStateException("선택한 차트 항목을 찾지 못했습니다.");

        List<SearchResult> result = new ArrayList<>();
        for (int i = 0; i < rows.length() && result.size() < limit; i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;
            SearchResult item = category == Category.TOP_ARTISTS
                    ? parseArtist(row, result.size() + 1)
                    : parseMedia(row, category, result.size() + 1);
            if (item != null) result.add(item);
        }

        if (result.isEmpty()) throw new IllegalStateException("차트 응답은 왔지만 표시 가능한 항목이 없습니다.");
        return result;
    }

    private static SearchResult parseArtist(JSONObject row, int rank) {
        String id = row.optString("id");
        String name = row.optString("name");
        if (name.isEmpty()) name = row.optString("title");
        if (name.isEmpty()) return null;
        String thumbnail = extractThumbnail(row);
        return new SearchResult(
                "artist:" + (id.isEmpty() ? Integer.toString(rank) : id),
                name,
                "YouTube Music",
                "",
                0,
                thumbnail,
                1000 - rank,
                "인기 아티스트 #" + rank
        );
    }

    private static SearchResult parseMedia(JSONObject row, Category category, int rank) {
        String id = category == Category.TOP_SONGS ? row.optString("encryptedVideoId") : row.optString("id");
        if (id.isEmpty()) id = row.optString("encryptedVideoId");
        if (id.isEmpty()) return null;

        String title = row.optString("title");
        if (title.isEmpty()) title = row.optString("name", "제목 없음");
        String artist = artists(row.optJSONArray("artists"));
        String thumbnail = extractThumbnail(row);
        if (thumbnail.isEmpty()) thumbnail = ArtworkLoader.fallbackUrl(id);

        String badge;
        if (category == Category.TOP_SONGS) badge = "인기곡 #" + rank;
        else if (category == Category.TOP_VIDEOS) badge = "뮤직비디오 #" + rank;
        else badge = "급상승 #" + rank;

        return new SearchResult(
                id,
                title,
                artist,
                "https://www.youtube.com/watch?v=" + id,
                0,
                thumbnail,
                1000 - rank,
                badge
        );
    }

    private static String chartPageName(Category category) {
        switch (category) {
            case TOP_ARTISTS: return "TopArtists";
            case TOP_VIDEOS: return "TopVideos";
            case TRENDING: return "TrendingVideos";
            case TOP_SONGS:
            default: return "TopSongs";
        }
    }

    private static JSONObject findAnalyticsRenderer(JSONObject root) {
        try {
            JSONArray sections = root.getJSONObject("contents")
                    .getJSONObject("sectionListRenderer")
                    .getJSONArray("contents");
            for (int i = 0; i < sections.length(); i++) {
                JSONObject section = sections.optJSONObject(i);
                JSONObject analytics = section == null ? null : section.optJSONObject("musicAnalyticsSectionRenderer");
                if (analytics != null) return analytics;
            }
        } catch (Exception ignored) { }
        return findObjectByKey(root, "musicAnalyticsSectionRenderer");
    }

    private static JSONArray rowsFor(JSONObject analytics, Category category) {
        try {
            if (category == Category.TOP_SONGS) {
                JSONArray types = analytics.optJSONArray("trackTypes");
                if (types != null && types.length() > 0) {
                    JSONObject first = types.optJSONObject(0);
                    if (first != null) return first.optJSONArray("trackViews");
                }
            } else if (category == Category.TOP_ARTISTS) {
                JSONArray artists = analytics.optJSONArray("artists");
                if (artists != null && artists.length() > 0) {
                    JSONObject first = artists.optJSONObject(0);
                    if (first != null) return first.optJSONArray("artistViews");
                }
            } else {
                JSONArray videos = analytics.optJSONArray("videos");
                int index = category == Category.TRENDING ? 1 : 0;
                if (videos != null && videos.length() > index) {
                    JSONObject type = videos.optJSONObject(index);
                    if (type != null) return type.optJSONArray("videoViews");
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    private static JSONObject findObjectByKey(Object node, String key) {
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            JSONObject direct = object.optJSONObject(key);
            if (direct != null) return direct;
            JSONArray names = object.names();
            if (names == null) return null;
            for (int i = 0; i < names.length(); i++) {
                JSONObject found = findObjectByKey(object.opt(names.optString(i)), key);
                if (found != null) return found;
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) {
                JSONObject found = findObjectByKey(array.opt(i), key);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static JSONArray findLargestArrayByKey(Object node, String key) {
        JSONArray best = null;
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            JSONArray direct = object.optJSONArray(key);
            if (direct != null) best = direct;
            JSONArray names = object.names();
            if (names == null) return best;
            for (int i = 0; i < names.length(); i++) {
                JSONArray found = findLargestArrayByKey(object.opt(names.optString(i)), key);
                if (found != null && (best == null || found.length() > best.length())) best = found;
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) {
                JSONArray found = findLargestArrayByKey(array.opt(i), key);
                if (found != null && (best == null || found.length() > best.length())) best = found;
            }
        }
        return best;
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

    private static String postCharts(String address, String body, String referer) throws Exception {
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
        c.setRequestProperty("Referer", referer);
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
