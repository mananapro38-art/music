package com.nolimit.music.data;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

public final class AutoBackupManager {
    public static final String FILE_NAME = "NoLimitMusic-auto-backup.json";
    private final Context context;

    public AutoBackupManager(Context context) {
        this.context = context.getApplicationContext();
    }

    public Uri backupNow() throws Exception {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null;
        ContentResolver resolver = context.getContentResolver();
        Uri collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        Uri target = findExisting(resolver, collection);
        if (target == null) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, FILE_NAME);
            values.put(MediaStore.Downloads.MIME_TYPE, "application/json");
            values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/No Limit Music");
            target = resolver.insert(collection, values);
        }
        if (target == null) throw new IllegalStateException("자동 백업 파일을 만들 수 없습니다.");
        new LocalBackupStore(context).exportTo(target);
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
                .putLong("last_auto_backup", System.currentTimeMillis()).apply();
        return target;
    }

    private Uri findExisting(ContentResolver resolver, Uri collection) {
        String[] projection = {MediaStore.Downloads._ID};
        String selection = MediaStore.Downloads.DISPLAY_NAME + "=?";
        try (Cursor c = resolver.query(collection, projection, selection, new String[]{FILE_NAME}, null)) {
            if (c != null && c.moveToFirst()) {
                return android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0));
            }
        } catch (Exception ignored) { }
        return null;
    }
}
