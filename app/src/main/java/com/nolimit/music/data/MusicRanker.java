package com.nolimit.music.data;

import com.nolimit.music.model.SearchResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Heuristically ranks results toward album/audio uploads and away from MV/live/cover results.
 * Labels are candidates, not licensing claims.
 */
public final class MusicRanker {
    private MusicRanker() {}

    public static List<SearchResult> rank(List<SearchResult> input, String query) {
        List<SearchResult> output = new ArrayList<>();
        for (SearchResult item : input) {
            int score = score(item, query);
            output.add(item.withRanking(score, badge(item)));
        }
        output.sort(Comparator.comparingInt((SearchResult r) -> r.score).reversed()
                .thenComparingLong(r -> r.durationSeconds <= 0 ? Long.MAX_VALUE : r.durationSeconds));
        return output;
    }

    /**
     * Final cross-provider pass. Keeps the provider badge but strongly prefers true music/audio
     * candidates. This is intentionally applied even when ordinary YouTube fills missing results.
     */
    public static List<SearchResult> preferAudioResults(List<SearchResult> input, String query) {
        List<SearchResult> output = new ArrayList<>(input);
        output.sort(Comparator
                .comparingInt((SearchResult r) -> crossProviderScore(r, query)).reversed()
                .thenComparingLong(r -> r.durationSeconds <= 0 ? Long.MAX_VALUE : r.durationSeconds));
        return output;
    }

    private static int crossProviderScore(SearchResult item, String query) {
        String title = safe(item.title);
        String channel = safe(item.channel);
        String badge = safe(item.badge);
        int s = item.score + score(item, query);

        // A catalog audio item should always lead video-like YouTube Music results.
        if (badge.contains("youtube music · 음원")) s += 420;
        else if (badge.contains("youtube music")) s += 120;

        // YouTube's auto-generated album/track uploads are another strong audio signal.
        if (channel.endsWith(" - topic") || channel.endsWith("- topic")) s += 220;
        if (title.contains("provided to youtube")) s += 190;
        if (title.contains("official audio")) s += 165;
        if (containsAny(title, "audio only", "official lyric audio")) s += 100;
        if (!containsAny(title, "video", "m/v", " mv ", "live", "concert", "performance")
                && item.durationSeconds >= 90 && item.durationSeconds <= 600) s += 35;

        if (badge.contains("soundcloud") || badge.contains("audius") || badge.contains("bandcamp")) s += 80;

        if (containsAny(title, "official music video", "music video", "m/v", " mv ")) s -= 220;
        if (channel.contains("vevo")) s -= 100;
        if (containsAny(title, "live", "concert", "performance", "직캠", "fancam")) s -= 170;
        if (containsAny(title, "cover", "reaction", "리액션", "shorts", "teaser")) s -= 160;
        if (isNonSongContent(title, channel, badge)) s -= 360;
        return s;
    }

    static int score(SearchResult item, String query) {
        String title = safe(item.title);
        String channel = safe(item.channel);
        String album = safe(item.album);
        String badge = safe(item.badge);
        String q = safe(query).trim();
        int s = 0;

        // The YTM response itself tells us whether this is an audio track.
        if (badge.contains("ytm_audio") || badge.contains("youtube music · 음원")) s += 320;
        if (badge.contains("ytm_video") || badge.contains("뮤직비디오")) s -= 80;
        if (badge.contains("ytm_other")) s -= 150;

        if (channel.endsWith(" - topic") || channel.endsWith("- topic")) s += 150;
        if (title.contains("official audio")) s += 115;
        if (title.contains("provided to youtube")) s += 90;
        if (title.contains("audio")) s += 24;
        if (channel.contains("official") && !channel.contains("vevo")) s += 20;
        if (item.durationSeconds >= 90 && item.durationSeconds <= 600) s += 16;

        // Artist-name searches are common. A channel/artist match is much stronger
        // evidence than a video title merely mentioning the query.
        if (q.length() >= 2) {
            if (channel.equals(q)) s += 240;
            else if (channel.contains(q)) s += 170;
            if (title.equals(q)) s += 100;
            else if (title.contains(q)) s += 55;
            if (!album.isEmpty() && album.contains(q)) s += 25;
        }

        if (containsAny(title, "official music video", "music video", "m/v", " mv ")) s -= 110;
        if (channel.contains("vevo")) s -= 55;
        if (containsAny(title, "live", "concert", "performance", "직캠", "fancam")) s -= 95;
        if (containsAny(title, "cover", "reaction", "lyrics", "lyric video", "shorts", "teaser")) s -= 70;
        if (containsAny(title, "remix", "sped up", "slowed", "nightcore")
                && !containsAny(q, "remix", "리믹스", "sped up", "slowed", "nightcore")) s -= 55;
        if (isNonSongContent(title, channel, badge)) s -= 260;

        String[] tokens = q.split("\\s+");
        for (String token : tokens) {
            if (token.length() < 2) continue;
            if (channel.contains(token)) s += 35;
            if (title.contains(token)) s += 8;
            if (album.contains(token)) s += 6;
        }
        return s;
    }

    static String badge(SearchResult item) {
        String title = safe(item.title);
        String channel = safe(item.channel);
        String existing = safe(item.badge);
        if (existing.contains("ytm_audio")) return "음원";
        if (existing.contains("ytm_video")) return "뮤직비디오 · 후순위";
        if (existing.contains("ytm_other")) return "기타 · 후순위";
        if (channel.endsWith(" - topic") || channel.endsWith("- topic")) return "앨범 음원 후보";
        if (title.contains("provided to youtube")) return "YouTube 제공 음원";
        if (title.contains("official audio")) return "공식 오디오 후보";
        if (containsAny(title, "official music video", "music video", "m/v", " mv ") || channel.contains("vevo"))
            return "뮤직비디오 · 후순위";
        if (containsAny(title, "live", "concert", "performance", "직캠", "fancam"))
            return "라이브/공연 · 후순위";
        return "음원 후보";
    }

    private static boolean isNonSongContent(String title, String channel, String badge) {
        return containsAny(title,
                "episode", "에피소드", "podcast", "팟캐스트", "interview", "인터뷰",
                "노래방", "karaoke", "[tj", "tj노래방", "금영", "ky karaoke")
                || containsAny(channel, "노래방", "karaoke", "podcast", "팟캐스트")
                || badge.contains("episode") || badge.contains("podcast");
    }

    private static boolean containsAny(String text, String... words) {
        for (String word : words) if (text.contains(word)) return true;
        return false;
    }

    private static String safe(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
