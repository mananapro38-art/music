package com.nolimit.music.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.widget.RemoteViews;

import androidx.core.content.ContextCompat;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.MediaController;
import androidx.media3.session.PlaybackPendingIntentBuilder;
import androidx.media3.session.SessionToken;

import com.google.common.util.concurrent.ListenableFuture;
import com.nolimit.music.MainActivity;
import com.nolimit.music.R;
import com.nolimit.music.data.LibraryStore;
import com.nolimit.music.model.Track;
import com.nolimit.music.playback.PlaybackService;
import com.nolimit.music.util.ArtworkLoader;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@UnstableApi
public final class LargeMusicWidgetProvider extends AppWidgetProvider {
    private static final String PREFS = "widget_state";
    private static final String ACTION_SHUFFLE = "com.nolimit.music.widget.SHUFFLE";
    private static final String ACTION_REPEAT = "com.nolimit.music.widget.REPEAT";
    private static final String ACTION_LIKE = "com.nolimit.music.widget.LIKE";
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) { updateWidgets(context, manager, ids); }

    @Override public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        String action = intent.getAction();
        if (ACTION_SHUFFLE.equals(action) || ACTION_REPEAT.equals(action)) {
            SessionToken token = new SessionToken(context, new ComponentName(context, PlaybackService.class));
            ListenableFuture<MediaController> future = new MediaController.Builder(context, token).buildAsync();
            future.addListener(() -> {
                try {
                    MediaController c = future.get();
                    if (ACTION_SHUFFLE.equals(action)) c.setShuffleModeEnabled(!c.getShuffleModeEnabled());
                    else c.setRepeatMode(c.getRepeatMode() == Player.REPEAT_MODE_OFF ? Player.REPEAT_MODE_ALL
                            : c.getRepeatMode() == Player.REPEAT_MODE_ALL ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
                } catch (Exception ignored) { }
                MediaController.releaseFuture(future);
            }, ContextCompat.getMainExecutor(context));
        } else if (ACTION_LIKE.equals(action)) {
            SharedPreferences state = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String mediaId = state.getString("mediaId", "");
            if (!mediaId.isEmpty()) {
                LibraryStore library = new LibraryStore(context);
                Track track = library.find(mediaId);
                if (track != null) library.setLiked(mediaId, !track.liked);
                updateAll(context);
            }
        }
    }

    public static void updateAll(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        ComponentName name = new ComponentName(context, LargeMusicWidgetProvider.class);
        updateWidgets(context, manager, manager.getAppWidgetIds(name));
    }

    private static void updateWidgets(Context context, AppWidgetManager manager, int[] ids) {
        SharedPreferences state = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String title = state.getString("title", "No Limit Music");
        String artist = state.getString("artist", "재생할 곡을 선택하세요");
        String artwork = state.getString("artwork", "");
        String mediaId = state.getString("mediaId", "");
        boolean playing = state.getBoolean("playing", false);
        boolean shuffle = state.getBoolean("shuffle", false);
        int repeat = state.getInt("repeat", Player.REPEAT_MODE_OFF);
        Track track = mediaId.isEmpty() ? null : new LibraryStore(context).find(mediaId);
        boolean liked = track != null && track.liked;
        for (int id : ids) {
            manager.updateAppWidget(id, views(context, title, artist, playing, shuffle, repeat, liked, null));
            if (!artwork.isEmpty()) {
                int widgetId = id;
                IO.execute(() -> {
                    Bitmap bitmap = ArtworkLoader.loadBitmapBlocking(context, artwork);
                    if (bitmap != null) manager.updateAppWidget(widgetId, views(context, title, artist, playing, shuffle, repeat, liked, bitmap));
                });
            }
        }
    }

    private static RemoteViews views(Context context, String title, String artist, boolean playing,
                                     boolean shuffle, int repeat, boolean liked, Bitmap artwork) {
        RemoteViews v = new RemoteViews(context.getPackageName(), R.layout.widget_music_large);
        v.setTextViewText(R.id.widgetLargeTitle, title);
        v.setTextViewText(R.id.widgetLargeArtist, artist);
        v.setTextViewText(R.id.widgetLargePlayPause, playing ? "Ⅱ" : "▶");
        v.setTextViewText(R.id.widgetLargeShuffle, shuffle ? "⤨•" : "⤨");
        v.setTextViewText(R.id.widgetLargeRepeat, repeat == Player.REPEAT_MODE_ONE ? "↻1" : repeat == Player.REPEAT_MODE_ALL ? "↻•" : "↻");
        v.setTextViewText(R.id.widgetLargeLike, liked ? "♥" : "♡");
        if (artwork != null) v.setImageViewBitmap(R.id.widgetLargeArtwork, artwork); else v.setImageViewResource(R.id.widgetLargeArtwork, R.drawable.ic_music_note);

        Intent open = new Intent(context, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        v.setOnClickPendingIntent(R.id.widgetLargeRoot, PendingIntent.getActivity(context, 4100, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        v.setOnClickPendingIntent(R.id.widgetLargePrevious, new PlaybackPendingIntentBuilder(context, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, PlaybackService.class).build());
        v.setOnClickPendingIntent(R.id.widgetLargePlayPause, new PlaybackPendingIntentBuilder(context, Player.COMMAND_PLAY_PAUSE, PlaybackService.class).setStartAsForegroundService(!playing).build());
        v.setOnClickPendingIntent(R.id.widgetLargeNext, new PlaybackPendingIntentBuilder(context, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, PlaybackService.class).build());
        v.setOnClickPendingIntent(R.id.widgetLargeShuffle, broadcast(context, ACTION_SHUFFLE, 4101));
        v.setOnClickPendingIntent(R.id.widgetLargeRepeat, broadcast(context, ACTION_REPEAT, 4102));
        v.setOnClickPendingIntent(R.id.widgetLargeLike, broadcast(context, ACTION_LIKE, 4103));
        return v;
    }

    private static PendingIntent broadcast(Context context, String action, int code) {
        Intent i = new Intent(context, LargeMusicWidgetProvider.class).setAction(action);
        return PendingIntent.getBroadcast(context, code, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
