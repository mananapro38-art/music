package com.nolimit.music.data;

import android.content.Context;
import android.os.Environment;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SubtitleStore {
    private static final Pattern TIMING = Pattern.compile("(\\d{1,2}:)?\\d{2}:\\d{2}[.,]\\d{3}\\s+-->\\s+(\\d{1,2}:)?\\d{2}:\\d{2}[.,]\\d{3}.*");

    public static final class Cue {
        public final long startMs;
        public final long endMs;
        public final String text;

        public Cue(long startMs, long endMs, String text) {
            this.startMs = startMs;
            this.endMs = endMs;
            this.text = text;
        }
    }

    private final Context appContext;

    public SubtitleStore(Context context) {
        appContext = context.getApplicationContext();
    }

    public File musicDir() {
        File base = appContext.getExternalFilesDir(Environment.DIRECTORY_MUSIC);
        if (base == null) base = appContext.getFilesDir();
        File dir = new File(base, "NoLimitMusic");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    public File findBest(String videoId) {
        if (videoId == null || videoId.trim().isEmpty()) return null;
        File[] files = musicDir().listFiles((dir, name) -> {
            String n = name.toLowerCase(Locale.ROOT);
            return name.startsWith(videoId + ".") && (n.endsWith(".vtt") || n.endsWith(".srt"));
        });
        if (files == null || files.length == 0) return null;

        List<File> list = new ArrayList<>();
        Collections.addAll(list, files);
        list.sort(Comparator.comparingInt(this::languagePriority).thenComparing(File::getName));
        return list.get(0);
    }

    public List<Cue> load(String videoId) {
        File file = findBest(videoId);
        if (file == null) return Collections.emptyList();
        try {
            return parse(file);
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
    }

    private int languagePriority(File file) {
        String n = file.getName().toLowerCase(Locale.ROOT);
        if (n.contains(".ko") || n.contains("-ko")) return 0;
        if (n.contains(".en") || n.contains("-en")) return 1;
        if (n.contains(".ja") || n.contains("-ja")) return 2;
        return 3;
    }

    private static List<Cue> parse(File file) throws Exception {
        List<Cue> cues = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            long start = -1L;
            long end = -1L;
            StringBuilder text = new StringBuilder();

            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                Matcher matcher = TIMING.matcher(trimmed);
                if (matcher.matches()) {
                    flush(cues, start, end, text);
                    String[] parts = trimmed.split("\\s+-->\\s+", 2);
                    start = parseTimestamp(parts[0].trim());
                    String endPart = parts.length > 1 ? parts[1].trim().split("\\s+", 2)[0] : "";
                    end = parseTimestamp(endPart);
                    text.setLength(0);
                    continue;
                }

                if (trimmed.isEmpty()) {
                    flush(cues, start, end, text);
                    start = -1L;
                    end = -1L;
                    text.setLength(0);
                    continue;
                }

                if (start >= 0 && !trimmed.matches("^\\d+$")) {
                    String clean = cleanText(trimmed);
                    if (!clean.isEmpty()) {
                        if (text.length() > 0) text.append(' ');
                        text.append(clean);
                    }
                }
            }
            flush(cues, start, end, text);
        }

        cues.sort(Comparator.comparingLong(c -> c.startMs));
        return dedupe(cues);
    }

    private static void flush(List<Cue> cues, long start, long end, StringBuilder text) {
        if (start < 0 || text.length() == 0) return;
        long safeEnd = end > start ? end : start + 3000L;
        cues.add(new Cue(start, safeEnd, text.toString().trim()));
    }

    private static List<Cue> dedupe(List<Cue> source) {
        List<Cue> out = new ArrayList<>();
        String previous = "";
        for (Cue cue : source) {
            String normalized = cue.text.replaceAll("\\s+", " ").trim();
            if (normalized.isEmpty() || normalized.equals(previous)) continue;
            out.add(new Cue(cue.startMs, cue.endMs, normalized));
            previous = normalized;
        }
        return out;
    }

    private static long parseTimestamp(String value) {
        try {
            String clean = value.replace(',', '.').trim();
            String[] main = clean.split(":");
            double seconds;
            if (main.length == 3) {
                seconds = Integer.parseInt(main[0]) * 3600d + Integer.parseInt(main[1]) * 60d + Double.parseDouble(main[2]);
            } else if (main.length == 2) {
                seconds = Integer.parseInt(main[0]) * 60d + Double.parseDouble(main[1]);
            } else {
                return 0L;
            }
            return Math.max(0L, Math.round(seconds * 1000d));
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private static String cleanText(String value) {
        String text = value
                .replaceAll("<[^>]+>", "")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replaceAll("\\s+", " ")
                .trim();
        if (text.startsWith("NOTE ") || "WEBVTT".equals(text)) return "";
        return text;
    }
}
