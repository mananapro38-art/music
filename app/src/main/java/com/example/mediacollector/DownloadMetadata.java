package com.example.mediacollector;

import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.offline.DownloadRequest;

import java.nio.charset.StandardCharsets;

@UnstableApi
public final class DownloadMetadata {
    private DownloadMetadata() {}

    public static byte[] encode(String title, String mimeType) {
        String safeTitle = title == null ? "미디어" : title.replace("\n", " ");
        String safeMime = mimeType == null ? "" : mimeType.replace("\n", " ");
        return (safeTitle + "\n" + safeMime).getBytes(StandardCharsets.UTF_8);
    }

    public static MediaCandidate decode(DownloadRequest request) {
        String title = MediaTypes.titleFromUrl(request.uri.toString());
        String mime = request.mimeType;
        try {
            String data = new String(request.data, StandardCharsets.UTF_8);
            String[] parts = data.split("\\n", -1);
            if (parts.length > 0 && !parts[0].isEmpty()) title = parts[0];
            if (parts.length > 1 && !parts[1].isEmpty()) mime = parts[1];
        } catch (Exception ignored) {}
        return new MediaCandidate(request.uri.toString(), mime, title);
    }
}
