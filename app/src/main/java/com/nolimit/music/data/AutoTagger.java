package com.nolimit.music.data;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

public final class AutoTagger {
    private AutoTagger() { }

    public static String infer(String title, String artist) {
        String s = ((title == null ? "" : title) + " " + (artist == null ? "" : artist)).toLowerCase(Locale.ROOT);
        Set<String> tags = new LinkedHashSet<>();
        add(tags, s, "live", "라이브");
        add(tags, s, "acoustic", "어쿠스틱");
        add(tags, s, "ballad", "발라드");
        add(tags, s, "piano", "피아노");
        add(tags, s, "jazz", "재즈");
        add(tags, s, "rock", "록");
        add(tags, s, "hip hop", "힙합");
        add(tags, s, "hiphop", "힙합");
        add(tags, s, "r&b", "R&B");
        add(tags, s, "lofi", "로파이");
        add(tags, s, "lo-fi", "로파이");
        add(tags, s, "sleep", "수면");
        add(tags, s, "study", "집중");
        add(tags, s, "workout", "운동");
        add(tags, s, "drive", "드라이브");
        add(tags, s, "night", "밤");
        add(tags, s, "sad", "감성");
        add(tags, s, "chill", "잔잔함");
        add(tags, s, "relax", "잔잔함");
        add(tags, s, "dance", "신남");
        add(tags, s, "party", "신남");
        add(tags, s, "remix", "리믹스");
        if (containsAny(s, "k-pop", "kpop", "케이팝")) tags.add("K-POP");
        if (containsAny(s, "j-pop", "jpop")) tags.add("J-POP");
        if (containsAny(s, "ost", "soundtrack")) tags.add("OST");
        if (tags.isEmpty()) tags.add("일반");
        return String.join(",", tags);
    }

    public static String inferAlbum(String title, String artist) {
        String t = title == null ? "" : title;
        int dash = t.indexOf(" - ");
        if (dash > 1 && dash < t.length() - 3) {
            String left = t.substring(0, dash).trim();
            if (artist != null && left.equalsIgnoreCase(artist.trim())) return "싱글/기타";
        }
        if (containsAny(t.toLowerCase(Locale.ROOT), "album", "ep", "ost")) return "앨범/OST";
        return "싱글/기타";
    }

    private static void add(Set<String> tags, String s, String needle, String tag) {
        if (s.contains(needle)) tags.add(tag);
    }

    private static boolean containsAny(String s, String... values) {
        for (String value : values) if (s.contains(value)) return true;
        return false;
    }
}
