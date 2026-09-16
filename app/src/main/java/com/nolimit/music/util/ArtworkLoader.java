package com.nolimit.music.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import com.nolimit.music.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ArtworkLoader {
    private static final ExecutorService IO = Executors.newFixedThreadPool(3);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final LruCache<String, Bitmap> MEMORY = new LruCache<String, Bitmap>(12 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) {
            return Math.max(1, value.getByteCount() / 1024);
        }
    };

    private ArtworkLoader() {}

    public static String fallbackUrl(String videoId) {
        if (videoId == null || videoId.trim().isEmpty()) return "";
        return "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg";
    }

    public static File localArtworkFile(Context context, String videoId) {
        File base = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        if (base == null) base = new File(context.getFilesDir(), "artwork");
        File dir = new File(base, "NoLimitMusicArtwork");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, safeId(videoId) + ".img");
    }

    public static String bestSource(Context context, String videoId, String remoteUrl) {
        File local = localArtworkFile(context, videoId);
        if (local.exists() && local.length() > 0) return local.getAbsolutePath();
        String remote = normalize(remoteUrl);
        return remote.isEmpty() ? fallbackUrl(videoId) : remote;
    }

    public static Uri bestArtworkUri(Context context, String videoId, String remoteUrl) {
        String source = bestSource(context, videoId, remoteUrl);
        if (source.isEmpty()) return null;
        File f = new File(source);
        return f.exists() ? Uri.fromFile(f) : Uri.parse(source);
    }

    public static void cacheToDisk(Context context, String videoId, String remoteUrl) {
        if (videoId == null || videoId.isEmpty()) return;
        File target = localArtworkFile(context, videoId);
        if (target.exists() && target.length() > 0) return;
        String source = normalize(remoteUrl);
        if (source.isEmpty()) source = fallbackUrl(videoId);
        HttpURLConnection connection = null;
        File temp = new File(target.getParentFile(), target.getName() + ".part");
        try {
            connection = (HttpURLConnection) new URL(source).openConnection();
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(15000);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) NoLimitMusic/0.6");
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) return;
            try (InputStream in = connection.getInputStream(); FileOutputStream out = new FileOutputStream(temp)) {
                byte[] buffer = new byte[16 * 1024];
                int n;
                while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n);
                out.flush();
            }
            if (temp.length() > 0) {
                if (target.exists()) target.delete();
                temp.renameTo(target);
            }
        } catch (Exception ignored) {
        } finally {
            if (connection != null) connection.disconnect();
            if (temp.exists()) temp.delete();
        }
    }

    public static void load(ImageView view, Context context, String videoId, String remoteUrl) {
        String source = bestSource(context, videoId, remoteUrl);
        view.setTag(R.id.artwork_tag, source);
        view.setImageResource(R.drawable.ic_music_note);
        if (source.isEmpty()) return;
        Bitmap cached = MEMORY.get(source);
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }
        IO.execute(() -> {
            Bitmap bitmap = loadBitmapBlocking(context, source);
            if (bitmap == null) return;
            MEMORY.put(source, bitmap);
            MAIN.post(() -> {
                Object tag = view.getTag(R.id.artwork_tag);
                if (source.equals(tag)) view.setImageBitmap(bitmap);
            });
        });
    }

    public static Bitmap loadBitmapBlocking(Context context, String source) {
        if (source == null || source.trim().isEmpty()) return null;
        Bitmap cached = MEMORY.get(source);
        if (cached != null) return cached;
        try {
            Uri uri = Uri.parse(source);
            Bitmap bitmap;
            if ("file".equalsIgnoreCase(uri.getScheme())) {
                bitmap = BitmapFactory.decodeFile(uri.getPath());
            } else if (source.startsWith("/")) {
                bitmap = BitmapFactory.decodeFile(source);
            } else if ("content".equalsIgnoreCase(uri.getScheme())) {
                try (InputStream in = context.getContentResolver().openInputStream(uri)) {
                    bitmap = BitmapFactory.decodeStream(in);
                }
            } else {
                HttpURLConnection connection = (HttpURLConnection) new URL(normalize(source)).openConnection();
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(12000);
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) NoLimitMusic/0.6");
                try (InputStream in = connection.getInputStream()) {
                    bitmap = BitmapFactory.decodeStream(in);
                } finally {
                    connection.disconnect();
                }
            }
            if (bitmap != null) MEMORY.put(source, bitmap);
            return bitmap;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String normalize(String value) {
        if (value == null) return "";
        String v = value.trim();
        if (v.startsWith("//")) return "https:" + v;
        return v;
    }

    private static String safeId(String id) {
        return id == null ? "unknown" : id.replaceAll("[^A-Za-z0-9_-]", "_");
    }
}
