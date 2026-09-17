package com.nolimit.music.data;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import com.nolimit.music.model.Track;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class TrackStorage {
    public static final String RELATIVE_DIR = Environment.DIRECTORY_MUSIC + "/No Limit Music";
    private TrackStorage() { }

    public static String publish(Context context, File source, String id, String title,
                                 String artist, String album, long durationSeconds) throws Exception {
        if (source == null || !source.exists()) throw new IllegalArgumentException("저장할 음원 파일이 없습니다.");
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return source.getAbsolutePath();
        ContentResolver resolver = context.getContentResolver();
        String ext = extension(source.getName());
        if (ext.isEmpty()) ext = "m4a";
        String displayName = safe(id) + "__" + safe(title) + "." + ext;
        Uri existing = findByDisplayName(resolver, displayName);
        if (existing != null) return existing.toString();

        ContentValues values = new ContentValues();
        values.put(MediaStore.Audio.Media.DISPLAY_NAME, displayName);
        values.put(MediaStore.Audio.Media.TITLE, title);
        values.put(MediaStore.Audio.Media.ARTIST, artist);
        values.put(MediaStore.Audio.Media.ALBUM, album == null ? "" : album);
        values.put(MediaStore.Audio.Media.MIME_TYPE, mime(ext));
        values.put(MediaStore.Audio.Media.RELATIVE_PATH, RELATIVE_DIR);
        values.put(MediaStore.Audio.Media.IS_MUSIC, 1);
        if (durationSeconds > 0) values.put(MediaStore.Audio.Media.DURATION, durationSeconds * 1000L);
        values.put(MediaStore.Audio.Media.IS_PENDING, 1);
        Uri collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        Uri uri = resolver.insert(collection, values);
        if (uri == null) throw new IllegalStateException("공용 Music 폴더에 파일을 만들 수 없습니다.");
        boolean ok = false;
        try (InputStream in = new FileInputStream(source); OutputStream out = resolver.openOutputStream(uri, "w")) {
            if (out == null) throw new IllegalStateException("공용 음악 파일을 열 수 없습니다.");
            byte[] buffer = new byte[128 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) out.write(buffer, 0, read);
            out.flush(); ok = true;
        } finally {
            if (!ok) resolver.delete(uri, null, null);
        }
        ContentValues done = new ContentValues(); done.put(MediaStore.Audio.Media.IS_PENDING, 0);
        resolver.update(uri, done, null, null);
        return uri.toString();
    }

    public static void mirrorBestEffort(Context context, Track track) {
        if (track == null || track.path == null || track.path.startsWith("content://")) return;
        File f = new File(track.path);
        if (!f.exists()) return;
        try { publish(context, f, track.id, track.title, track.artist, track.album, track.durationSeconds); } catch (Exception ignored) { }
    }

    public static boolean exists(Context context, String path) {
        if (path == null || path.isEmpty()) return false;
        if (!path.startsWith("content://")) return new File(path).exists();
        try (android.content.res.AssetFileDescriptor ignored = context.getContentResolver().openAssetFileDescriptor(Uri.parse(path), "r")) {
            return ignored != null;
        } catch (Exception e) { return false; }
    }

    public static Uri uri(String path) {
        if (path == null || path.isEmpty()) return Uri.EMPTY;
        return path.startsWith("content://") ? Uri.parse(path) : Uri.fromFile(new File(path));
    }

    public static void delete(Context context, String path) {
        if (path == null || path.isEmpty()) return;
        try { if (path.startsWith("content://")) context.getContentResolver().delete(Uri.parse(path), null, null); else new File(path).delete(); } catch (Exception ignored) { }
    }

    public static void deleteSharedById(Context context, String id) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || id == null || id.isEmpty()) return;
        Uri collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL);
        try (Cursor c = context.getContentResolver().query(collection, new String[]{MediaStore.Audio.Media._ID},
                MediaStore.Audio.Media.DISPLAY_NAME + " LIKE ? AND " + MediaStore.Audio.Media.RELATIVE_PATH + " LIKE ?",
                new String[]{safe(id) + "__%", "%No Limit Music%"}, null)) {
            if (c == null) return;
            List<Uri> uris = new ArrayList<>();
            while (c.moveToNext()) uris.add(ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, c.getLong(0)));
            for (Uri uri : uris) try { context.getContentResolver().delete(uri, null, null); } catch (Exception ignored) { }
        } catch (Exception ignored) { }
    }

    public static List<Track> scanShared(Context context) {
        List<Track> out = new ArrayList<>();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return out;
        Uri collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL);
        String[] projection = {MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DATE_ADDED, MediaStore.Audio.Media.RELATIVE_PATH};
        String selection = MediaStore.Audio.Media.RELATIVE_PATH + " LIKE ?";
        try (Cursor cursor = context.getContentResolver().query(collection, projection, selection,
                new String[]{"%No Limit Music%"}, MediaStore.Audio.Media.DATE_ADDED + " DESC")) {
            if (cursor == null) return out;
            int idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
            int nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME);
            int titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
            int artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
            int albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM);
            int durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
            int addedCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED);
            while (cursor.moveToNext()) {
                long rowId = cursor.getLong(idCol); String name = cursor.getString(nameCol); String videoId = parseVideoId(name);
                if (videoId.isEmpty()) continue;
                Uri uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, rowId);
                String title = nvl(cursor.getString(titleCol), stripName(name));
                String artist = nvl(cursor.getString(artistCol), "YouTube");
                String album = nvl(cursor.getString(albumCol), "싱글/기타");
                long duration = Math.max(0L, cursor.getLong(durationCol) / 1000L);
                long added = Math.max(0L, cursor.getLong(addedCol) * 1000L);
                out.add(new Track(videoId, title, artist, uri.toString(), duration, added, false, 0, 0L, "", album, AutoTagger.infer(title, artist)));
            }
        } catch (Exception ignored) { }
        return out;
    }

    private static Uri findByDisplayName(ContentResolver resolver, String displayName) {
        Uri collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL);
        try (Cursor cursor = resolver.query(collection, new String[]{MediaStore.Audio.Media._ID},
                MediaStore.Audio.Media.DISPLAY_NAME + "=? AND " + MediaStore.Audio.Media.RELATIVE_PATH + " LIKE ?",
                new String[]{displayName, "%No Limit Music%"}, null)) {
            if (cursor != null && cursor.moveToFirst()) return ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0));
        } catch (Exception ignored) { }
        return null;
    }

    private static String parseVideoId(String displayName) { if (displayName == null) return ""; int split = displayName.indexOf("__"); return split <= 0 ? "" : displayName.substring(0, split); }
    private static String stripName(String displayName) { if (displayName == null) return "제목 없음"; int split = displayName.indexOf("__"); String s = split >= 0 ? displayName.substring(split + 2) : displayName; int dot = s.lastIndexOf('.'); return dot > 0 ? s.substring(0, dot) : s; }
    private static String safe(String s) { if (s == null) return "track"; String cleaned = s.replaceAll("[\\\\/:*?\"<>|]", "_").replaceAll("\\s+", " ").trim(); if (cleaned.length() > 80) cleaned = cleaned.substring(0, 80).trim(); return cleaned.isEmpty() ? "track" : cleaned; }
    private static String extension(String name) { int dot = name == null ? -1 : name.lastIndexOf('.'); return dot >= 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : ""; }
    private static String mime(String ext) { if ("m4a".equals(ext) || "mp4".equals(ext)) return "audio/mp4"; if ("webm".equals(ext)) return "audio/webm"; if ("mp3".equals(ext)) return "audio/mpeg"; if ("opus".equals(ext)) return "audio/ogg"; return "audio/*"; }
    private static String nvl(String value, String fallback) { return value == null || value.trim().isEmpty() || "<unknown>".equals(value) ? fallback : value; }
}
