package com.nolimit.music.playback;

import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;

import com.nolimit.music.data.LibraryStore;

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
            }
        });
        session = new MediaSession.Builder(this, player).build();
    }

    @Nullable
    @Override
    public MediaSession onGetSession(MediaSession.ControllerInfo controllerInfo) {
        return session;
    }

    @Override
    public void onTaskRemoved(@Nullable android.content.Intent rootIntent) {
        // Keep playback alive when the app UI is dismissed. MediaSessionService owns the notification.
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
