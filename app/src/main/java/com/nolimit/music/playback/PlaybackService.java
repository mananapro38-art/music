package com.nolimit.music.playback;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;

import com.nolimit.music.data.LibraryStore;
import com.nolimit.music.widget.MusicWidgetProvider;

public final class PlaybackService extends MediaSessionService {
    private ExoPlayer player;
    private MediaSession session;
    private LibraryStore library;

    @Override
    public void onCreate() {
        super.onCreate();
        library = new LibraryStore(this);
        player = new ExoPlayer.Builder(this).build();
        player.setRepeatMode(Player.REPEAT_MODE_OFF);
        player.addListener(new Player.Listener() {
            @Override
            public void onMediaItemTransition(@Nullable MediaItem mediaItem, int reason) {
                if (mediaItem != null && mediaItem.mediaId != null && !mediaItem.mediaId.isEmpty()) {
                    library.incrementPlayCount(mediaItem.mediaId);
                }
                updateWidgetState();
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                updateWidgetState();
            }

            @Override
            public void onPlaybackStateChanged(int playbackState) {
                updateWidgetState();
            }
        });
        session = new MediaSession.Builder(this, player).build();
        updateWidgetState();
    }

    private void updateWidgetState() {
        if (player == null) return;
        MediaMetadata metadata = player.getMediaMetadata();
        String title = metadata.title == null ? "No Limit Music" : metadata.title.toString();
        String artist = metadata.artist == null ? "재생할 곡을 선택하세요" : metadata.artist.toString();
        SharedPreferences prefs = getSharedPreferences("widget_state", Context.MODE_PRIVATE);
        prefs.edit()
                .putString("title", title)
                .putString("artist", artist)
                .putBoolean("playing", player.isPlaying())
                .apply();
        MusicWidgetProvider.updateAll(this);
    }

    @Nullable
    @Override
    public MediaSession onGetSession(MediaSession.ControllerInfo controllerInfo) {
        return session;
    }

    @Override
    public void onTaskRemoved(@Nullable android.content.Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        if (session != null) session.release();
        if (player != null) player.release();
        session = null;
        player = null;
        super.onDestroy();
    }
}
