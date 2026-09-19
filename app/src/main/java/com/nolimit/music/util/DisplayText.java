package com.nolimit.music.util;

import java.util.Locale;
import java.util.regex.Pattern;

/** Small display-only cleanup for noisy video-style music metadata. */
public final class DisplayText {
    private static final Pattern LEADING_BRACKET = Pattern.compile(
            "(?i)^\\s*\\[(official\\s+)?(audio|music\\s*video|mv|m/v|lyric(?:s)?(?:\\s+video)?)\\]\\s*");
    private static final Pattern TRAILING_PARENS = Pattern.compile(
            "(?i)\\s*[\\(\\[]\\s*(official\\s+)?(audio|music\\s*video|mv|m/v|lyric(?:s)?(?:\\s+video)?)\\s*[\\)\\]]\\s*$");
    private static final Pattern DUP_SPACES = Pattern.compile("\\s{2,}");

    private DisplayText() {}

    public static String cleanTitle(String value) {
        if (value == null) return "";
        String out = value.trim();
        String before;
        do {
            before = out;
            out = LEADING_BRACKET.matcher(out).replaceFirst("");
            out = TRAILING_PARENS.matcher(out).replaceFirst("");
        } while (!before.equals(out));
        out = DUP_SPACES.matcher(out).replaceAll(" ").trim();
        return out.isEmpty() ? value.trim() : out;
    }

    public static String cleanArtist(String value) {
        if (value == null) return "";
        String out = value.trim();
        String lower = out.toLowerCase(Locale.ROOT);
        if (lower.endsWith(" - topic")) out = out.substring(0, out.length() - 8).trim();
        else if (lower.endsWith("- topic")) out = out.substring(0, out.length() - 7).trim();
        return out;
    }
}
