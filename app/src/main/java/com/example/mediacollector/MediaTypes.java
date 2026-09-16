package com.example.mediacollector;

import android.net.Uri;

import androidx.media3.common.MimeTypes;

import java.util.Locale;

public final class MediaTypes {
    private MediaTypes() {}

    public static String infer(String url) {
        String lower = url.toLowerCase(Locale.US);
        if (lower.contains(".m3u8")) return MimeTypes.APPLICATION_M3U8;
        if (lower.contains(".mpd")) return MimeTypes.APPLICATION_MPD;
        if (lower.contains(".m4a")) return MimeTypes.AUDIO_MP4;
        if (lower.contains(".mp3")) return MimeTypes.AUDIO_MPEG;
        if (lower.contains(".aac")) return MimeTypes.AUDIO_AAC;
        if (lower.contains(".wav")) return MimeTypes.AUDIO_WAV;
        if (lower.contains(".mp4") || lower.contains(".m4v")) return MimeTypes.VIDEO_MP4;
        if (lower.contains(".webm")) return MimeTypes.VIDEO_WEBM;
        return null;
    }

    public static boolean looksLikeMedia(String url) {
        return infer(url) != null;
    }

    public static String titleFromUrl(String raw) {
        try {
            Uri uri = Uri.parse(raw);
            String last = uri.getLastPathSegment();
            if (last == null || last.trim().isEmpty()) return uri.getHost() == null ? "미디어" : uri.getHost();
            return Uri.decode(last);
        } catch (Exception e) {
            return "미디어";
        }
    }
}
