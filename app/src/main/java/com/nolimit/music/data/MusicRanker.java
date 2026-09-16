package com.nolimit.music.data;

import com.nolimit.music.model.SearchResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Ranks search results toward official album/audio uploads and away from MVs/live/shorts.
 * This is intentionally heuristic: it never claims that a result is definitively licensed.
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

    static int score(SearchResult item, String query) {
        String title = safe(item.title);
        String channel = safe(item.channel);
        String q = safe(query);
        int s = 0;

        if (channel.endsWith(" - topic") || channel.endsWith("- topic")) s += 130;
        if (title.contains("official audio")) s += 95;
        if (title.contains("provided to youtube")) s += 70;
        if (title.contains("audio")) s += 18;
        if (channel.contains("official") && !channel.contains("vevo")) s += 16;
        if (item.durationSeconds >= 90 && item.durationSeconds <= 600) s += 12;

        if (containsAny(title, "official music video", "music video", "m/v", " mv ")) s -= 85;
        if (channel.contains("vevo")) s -= 45;
        if (containsAny(title, "live", "concert", "performance", "직캠", "fancam")) s -= 70;
        if (containsAny(title, "cover", "reaction", "lyrics", "lyric video", "shorts", "teaser")) s -= 55;
        if (containsAny(title, "remix", "sped up", "slowed", "nightcore") && !containsAny(q, "remix", "sped up", "slowed", "nightcore")) s -= 40;

        String[] tokens = q.split("\\s+");
        for (String token : tokens) {
            if (token.length() >= 2 && title.contains(token)) s += 4;
        }
        return s;
    }

    static String badge(SearchResult item) {
        String title = safe(item.title);
        String channel = safe(item.channel);
        if (channel.endsWith(" - topic") || channel.endsWith("- topic")) return "공식 앨범 음원 후보";
        if (title.contains("official audio")) return "공식 오디오 후보";
        if (containsAny(title, "official music video", "music video", "m/v", " mv ") || channel.contains("vevo")) return "뮤직비디오 · 후순위";
        if (containsAny(title, "live", "concert", "performance", "직캠", "fancam")) return "라이브/공연 · 후순위";
        return "음원 후보";
    }

    private static boolean containsAny(String text, String... words) {
        for (String word : words) if (text.contains(word)) return true;
        return false;
    }

    private static String safe(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
