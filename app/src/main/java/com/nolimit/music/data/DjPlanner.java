package com.nolimit.music.data;

import com.nolimit.music.model.SearchResult;
import com.nolimit.music.model.Track;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Local AI-DJ beta planner.
 *
 * This deliberately does not call a hosted model from the APK. It turns a natural-language
 * request plus local listening signals into several YouTube searches, then blends the ranked
 * results. A server-side generative-AI planner can replace buildQueries later without exposing
 * an API key in the app.
 */
public final class DjPlanner {
    private DjPlanner() { }

    public static List<String> buildQueries(String prompt, List<Track> library) {
        String base = prompt == null ? "" : prompt.trim();
        if (base.isEmpty()) base = "내 취향 음악";

        LinkedHashSet<String> queries = new LinkedHashSet<>();
        queries.add(base);
        queries.add(base + " official audio");

        String lower = base.toLowerCase(Locale.ROOT);
        if (containsAny(lower, "잔잔", "새벽", "밤", "chill", "감성")) {
            queries.add(base + " chill ballad official audio");
        }
        if (containsAny(lower, "신나", "운동", "드라이브", "upbeat", "댄스")) {
            queries.add(base + " upbeat dance official audio");
        }
        if (containsAny(lower, "공부", "집중", "focus")) {
            queries.add(base + " focus calm official audio");
        }
        if (containsAny(lower, "90년", "1990")) queries.add("1990s " + base + " official audio");
        if (containsAny(lower, "2000년", "2000s")) queries.add("2000s " + base + " official audio");
        if (containsAny(lower, "한국", "k-pop", "kpop", "케이팝")) queries.add("K-pop " + base + " official audio");

        for (String artist : favoriteArtists(library, 2)) {
            queries.add(artist + " 비슷한 분위기 " + base);
        }

        List<String> out = new ArrayList<>();
        for (String query : queries) {
            out.add(query);
            if (out.size() >= 5) break;
        }
        return out;
    }

    public static List<SearchResult> merge(List<List<SearchResult>> batches, List<Track> library, int limit) {
        Set<String> favorites = new LinkedHashSet<>();
        for (String artist : favoriteArtists(library, 6)) favorites.add(artist.toLowerCase(Locale.ROOT));

        Map<String, SearchResult> best = new LinkedHashMap<>();
        for (int batch = 0; batch < batches.size(); batch++) {
            List<SearchResult> values = batches.get(batch);
            if (values == null) continue;
            for (SearchResult item : values) {
                int score = item.score + Math.max(0, 30 - batch * 6);
                String hay = (item.title + " " + item.channel).toLowerCase(Locale.ROOT);
                for (String favorite : favorites) {
                    if (!favorite.isEmpty() && hay.contains(favorite)) {
                        score += 28;
                        break;
                    }
                }
                SearchResult ranked = new SearchResult(
                        item.id,
                        item.title,
                        item.channel,
                        item.url,
                        item.durationSeconds,
                        item.thumbnail,
                        score,
                        "AI DJ 후보"
                );
                SearchResult previous = best.get(item.id);
                if (previous == null || ranked.score > previous.score) best.put(item.id, ranked);
            }
        }

        List<SearchResult> merged = new ArrayList<>(best.values());
        merged.sort(Comparator.comparingInt((SearchResult r) -> r.score).reversed());
        if (limit > 0 && merged.size() > limit) return new ArrayList<>(merged.subList(0, limit));
        return merged;
    }

    private static List<String> favoriteArtists(List<Track> library, int limit) {
        List<Track> sorted = new ArrayList<>(library == null ? new ArrayList<>() : library);
        sorted.sort((a, b) -> {
            int aWeight = (a.liked ? 1000 : 0) + a.playCount * 10;
            int bWeight = (b.liked ? 1000 : 0) + b.playCount * 10;
            if (aWeight != bWeight) return Integer.compare(bWeight, aWeight);
            return Long.compare(b.lastPlayedAt, a.lastPlayedAt);
        });

        LinkedHashSet<String> artists = new LinkedHashSet<>();
        for (Track track : sorted) {
            String artist = track.artist == null ? "" : track.artist.trim();
            if (artist.isEmpty() || "YouTube".equalsIgnoreCase(artist)) continue;
            artists.add(artist);
            if (artists.size() >= limit) break;
        }
        return new ArrayList<>(artists);
    }

    private static boolean containsAny(String text, String... needles) {
        for (String needle : needles) if (text.contains(needle)) return true;
        return false;
    }
}
