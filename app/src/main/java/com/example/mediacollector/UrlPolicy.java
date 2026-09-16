package com.example.mediacollector;

import android.net.Uri;

import java.util.Locale;

public final class UrlPolicy {
    private UrlPolicy() {}

    private static final String[] BLOCKED_HOSTS = {
            "youtube.com", "www.youtube.com", "m.youtube.com", "youtu.be",
            "googlevideo.com", "www.googlevideo.com", "youtube-nocookie.com"
    };

    public static boolean isAllowed(String raw) {
        try {
            Uri uri = Uri.parse(raw);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null) return false;
            if (!scheme.equalsIgnoreCase("https")) return false;
            host = host.toLowerCase(Locale.US);
            if (isBlockedHost(host)) return false;
            if (host.equals("localhost") || host.endsWith(".local")) return false;
            if (isPrivateIpv4(host)) return false;
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isBlockedHost(String host) {
        String h = host.toLowerCase(Locale.US);
        for (String blocked : BLOCKED_HOSTS) {
            if (h.equals(blocked) || h.endsWith("." + blocked)) return true;
        }
        return false;
    }

    private static boolean isPrivateIpv4(String host) {
        String[] p = host.split("\\.");
        if (p.length != 4) return false;
        try {
            int a = Integer.parseInt(p[0]);
            int b = Integer.parseInt(p[1]);
            if (a == 10 || a == 127 || a == 0) return true;
            if (a == 169 && b == 254) return true;
            if (a == 172 && b >= 16 && b <= 31) return true;
            if (a == 192 && b == 168) return true;
            return a >= 224;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
