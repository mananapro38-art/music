package com.nolimit.music.data;

import android.content.Context;
import android.net.Uri;
import android.os.Environment;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
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
    private static final Pattern LRC_TIME = Pattern.compile("\\[(\\d{1,3}):(\\d{2}(?:[.:]\\d{1,3})?)\\]");

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
            return name.startsWith(videoId + ".") && (n.endsWith(".vtt") || n.endsWith(".srt") || n.endsWith(".lrc"));
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

    public File importLyrics(Uri source, String mediaId) throws Exception {
        if (source == null || mediaId == null || mediaId.trim().isEmpty()) throw new IllegalArgumentException("가사 파일 또는 곡 정보가 없습니다.");
        String ext = extensionFromUri(source);
        if (!"lrc".equals(ext) && !"vtt".equals(ext) && !"srt".equals(ext)) ext = "lrc";
        File target = new File(musicDir(), mediaId + ".user." + ext);
        try (InputStream in = appContext.getContentResolver().openInputStream(source);
             OutputStream out = new FileOutputStream(target)) {
            if (in == null) throw new IllegalStateException("가사 파일을 읽을 수 없습니다.");
            byte[] buffer = new byte[32768];
            int read;
            while ((read = in.read(buffer)) >= 0) out.write(buffer, 0, read);
            out.flush();
        }
        List<Cue> test = parse(target);
        if (test.isEmpty()) {
            target.delete();
            throw new IllegalArgumentException("지원되는 타임코드가 없는 가사 파일입니다.");
        }
        return target;
    }

    private int languagePriority(File file) {
        String n = file.getName().toLowerCase(Locale.ROOT);
        if (n.contains(".user.")) return -10;
        if (n.endsWith(".lrc")) return -5;
        if (n.contains(".ko") || n.contains("-ko")) return 0;
        if (n.contains(".en") || n.contains("-en")) return 1;
        if (n.contains(".ja") || n.contains("-ja")) return 2;
        return 3;
    }

    private static List<Cue> parse(File file) throws Exception {
        String name = file.getName().toLowerCase(Locale.ROOT);
        if (name.endsWith(".lrc")) return parseLrc(file);
        return parseTimedText(file);
    }

    private static List<Cue> parseTimedText(File file) throws Exception {
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

    private static List<Cue> parseLrc(File file) throws Exception {
        List<Cue> raw = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                Matcher matcher = LRC_TIME.matcher(line);
                int textStart = -1;
                List<Long> starts = new ArrayList<>();
                while (matcher.find()) {
                    starts.add(parseLrcTimestamp(matcher.group(1), matcher.group(2)));
                    textStart = matcher.end();
                }
                if (starts.isEmpty() || textStart < 0) continue;
                String text = cleanText(line.substring(textStart));
                if (text.isEmpty()) continue;
                for (Long start : starts) raw.add(new Cue(start, start + 4000L, text));
            }
        }
        raw.sort(Comparator.comparingLong(c -> c.startMs));
        List<Cue> out = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            Cue cue = raw.get(i);
            long end = i + 1 < raw.size() ? Math.max(cue.startMs + 500L, raw.get(i + 1).startMs - 1L) : cue.startMs + 5000L;
            out.add(new Cue(cue.startMs, end, cue.text));
        }
        return dedupe(out);
    }

    private static long parseLrcTimestamp(String minutes, String secFraction) {
        try {
            int min = Integer.parseInt(minutes);
            String s = secFraction.replace(':', '.');
            double seconds = Double.parseDouble(s);
            return Math.max(0L, Math.round((min * 60d + seconds) * 1000d));
        } catch (Exception ignored) { return 0L; }
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

    private String extensionFromUri(Uri uri) {
        String value = uri.toString().toLowerCase(Locale.ROOT);
        if (value.contains(".vtt")) return "vtt";
        if (value.contains(".srt")) return "srt";
        if (value.contains(".lrc")) return "lrc";
        String type = appContext.getContentResolver().getType(uri);
        if (type != null && type.contains("vtt")) return "vtt";
        if (type != null && type.contains("subrip")) return "srt";
        return "lrc";
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
