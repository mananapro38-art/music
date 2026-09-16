package com.nolimit.music.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.RemoteViews;

import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.PlaybackPendingIntentBuilder;

import com.nolimit.music.MainActivity;
import com.nolimit.music.R;
import com.nolimit.music.playback.PlaybackService;

@UnstableApi
public final class MusicWidgetProvider extends AppWidgetProvider {
    private static final String PREFS = "widget_state";

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        updateWidgets(context, appWidgetManager, appWidgetIds);
    }

    public static void updateAll(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        ComponentName name = new ComponentName(context, MusicWidgetProvider.class);
        int[] ids = manager.getAppWidgetIds(name);
        updateWidgets(context, manager, ids);
    }

    private static void updateWidgets(Context context, AppWidgetManager manager, int[] ids) {
        SharedPreferences state = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String title = state.getString("title", "No Limit Music");
        String artist = state.getString("artist", "재생할 곡을 선택하세요");
        boolean playing = state.getBoolean("playing", false);

        for (int id : ids) {
            RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_music);
            views.setTextViewText(R.id.widgetTitle, title);
            views.setTextViewText(R.id.widgetArtist, artist);
            views.setTextViewText(R.id.widgetPlayPause, playing ? "Ⅱ" : "▶");

            Intent openApp = new Intent(context, MainActivity.class);
            openApp.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent openPending = PendingIntent.getActivity(
                    context, 2001, openApp, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            views.setOnClickPendingIntent(R.id.widgetRoot, openPending);

            views.setOnClickPendingIntent(R.id.widgetPrevious,
                    new PlaybackPendingIntentBuilder(context, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, PlaybackService.class).build());
            views.setOnClickPendingIntent(R.id.widgetPlayPause,
                    new PlaybackPendingIntentBuilder(context, Player.COMMAND_PLAY_PAUSE, PlaybackService.class)
                            .setStartAsForegroundService(!playing)
                            .build());
            views.setOnClickPendingIntent(R.id.widgetNext,
                    new PlaybackPendingIntentBuilder(context, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, PlaybackService.class).build());

            manager.updateAppWidget(id, views);
        }
    }
}
