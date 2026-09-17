package com.nolimit.music.playback;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;

import com.nolimit.music.data.HistoryStore;
import com.nolimit.music.data.LibraryStore;
import com.nolimit.music.data.TrackStorage;
import com.nolimit.music.model.Track;
import com.nolimit.music.util.ArtworkLoader;
import com.nolimit.music.widget.LargeMusicWidgetProvider;
import com.nolimit.music.widget.MusicWidgetProvider;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class PlaybackService extends MediaSessionService {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ExoPlayer player;
    private MediaSession session;
    private LibraryStore library;
    private HistoryStore history;
    private SharedPreferences settings;
    private String listeningTrackId = "";
    private long lastListeningTick = 0L;
    private long fadeInStartedAt = 0L;

    private final Runnable serviceTicker = new Runnable() {
        @Override public void run() {
            tickListeningTime();
            tickSleepTimer();
            tickFade();
            handler.postDelayed(this, 250L);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        library = new LibraryStore(this);
        history = new HistoryStore(this);
        settings = getSharedPreferences("settings", Context.MODE_PRIVATE);
        player = new ExoPlayer.Builder(this).build();
        player.setRepeatMode(settings.getInt("repeat_mode", Player.REPEAT_MODE_OFF));
        player.setShuffleModeEnabled(settings.getBoolean("shuffle", false));
        player.addListener(new Player.Listener() {
            @Override public void onMediaItemTransition(@Nullable MediaItem mediaItem, int reason) {
                flushListeningTime();
                if (mediaItem != null && mediaItem.mediaId != null && !mediaItem.mediaId.isEmpty()) {
                    listeningTrackId = mediaItem.mediaId;
                    lastListeningTick = System.currentTimeMillis();
                    library.incrementPlayCount(mediaItem.mediaId);
                    Track track = library.find(mediaItem.mediaId);
                    MediaMetadata md = mediaItem.mediaMetadata;
                    String title = track != null ? track.title : md.title == null ? "" : md.title.toString();
                    String artist = track != null ? track.artist : md.artist == null ? "" : md.artist.toString();
                    history.record(mediaItem.mediaId, title, artist);
                    if (smoothFadeMs() > 0) {
                        fadeInStartedAt = System.currentTimeMillis();
                        player.setVolume(0f);
                    }
                }
                updateWidgetState();
            }

            @Override public void onIsPlayingChanged(boolean isPlaying) {
                if (isPlaying) lastListeningTick = System.currentTimeMillis(); else flushListeningTime();
                updateWidgetState();
            }

            @Override public void onPlaybackStateChanged(int playbackState) {
                if (playbackState == Player.STATE_ENDED) maybeSmartContinue();
                updateWidgetState();
            }

            @Override public void onRepeatModeChanged(int repeatMode) {
                settings.edit().putInt("repeat_mode", repeatMode).apply();
                updateWidgetState();
            }

            @Override public void onShuffleModeEnabledChanged(boolean shuffleModeEnabled) {
                settings.edit().putBoolean("shuffle", shuffleModeEnabled).apply();
                updateWidgetState();
            }
        });
        session = new MediaSession.Builder(this, player).build();
        updateWidgetState();
        handler.post(serviceTicker);
    }

    private void maybeSmartContinue() {
        if (player == null || !settings.getBoolean("smart_continue", true)) return;
        if (player.getRepeatMode() != Player.REPEAT_MODE_OFF) return;
        List<Track> all = library.load();
        if (all.isEmpty()) return;
        Set<String> queued = new HashSet<>();
        for (int i = 0; i < player.getMediaItemCount(); i++) queued.add(player.getMediaItemAt(i).mediaId);
        all.removeIf(t -> queued.contains(t.id) || !TrackStorage.exists(this, t.path));
        all.sort((a, b) -> Integer.compare(score(b), score(a)));
        List<MediaItem> next = new ArrayList<>();
        for (Track t : all) {
            next.add(toMediaItem(t));
            if (next.size() >= 12) break;
        }
        if (next.isEmpty()) return;
        player.addMediaItems(next);
        if (player.getPlaybackState() == Player.STATE_ENDED) {
            player.seekToNextMediaItem();
            player.prepare();
            player.play();
        }
    }

    private static int score(Track t) {
        int score = t.playCount * 6 + (t.liked ? 100 : 0);
        long ageDays = t.lastPlayedAt <= 0 ? 999 : (System.currentTimeMillis() - t.lastPlayedAt) / 86400000L;
        score += (int) Math.min(40, ageDays);
        return score;
    }

    private MediaItem toMediaItem(Track t) {
        MediaMetadata.Builder md = new MediaMetadata.Builder()
                .setTitle(t.title)
                .setArtist(t.artist)
                .setAlbumTitle(t.album);
        Uri art = ArtworkLoader.bestArtworkUri(this, t.id, t.thumbnailUrl);
        if (art != null) md.setArtworkUri(art);
        return new MediaItem.Builder()
                .setMediaId(t.id)
                .setUri(TrackStorage.uri(t.path))
                .setMediaMetadata(md.build())
                .build();
    }

    private void tickSleepTimer() {
        long deadline = settings.getLong("sleep_deadline", 0L);
        if (deadline > 0 && System.currentTimeMillis() >= deadline) {
            if (player != null) player.pause();
            settings.edit().putLong("sleep_deadline", 0L).apply();
            updateWidgetState();
        }
    }

    private void tickListeningTime() {
        if (player == null || !player.isPlaying() || listeningTrackId.isEmpty()) return;
        long now = System.currentTimeMillis();
        if (lastListeningTick <= 0) lastListeningTick = now;
        long delta = now - lastListeningTick;
        if (delta >= 5000L) {
            history.addListeningTime(listeningTrackId, delta);
            lastListeningTick = now;
        }
    }

    private void flushListeningTime() {
        if (listeningTrackId.isEmpty() || lastListeningTick <= 0) return;
        long delta = System.currentTimeMillis() - lastListeningTick;
        if (delta > 0 && delta < 120000L) history.addListeningTime(listeningTrackId, delta);
        lastListeningTick = 0L;
    }

    private int smoothFadeMs() { return settings.getInt("smooth_transition_ms", 0); }

    private void tickFade() {
        if (player == null || !player.isPlaying()) return;
        int fade = smoothFadeMs();
        if (fade <= 0) {
            if (player.getVolume() != 1f) player.setVolume(1f);
            return;
        }
        long now = System.currentTimeMillis();
        if (fadeInStartedAt > 0 && now - fadeInStartedAt < fade) {
            player.setVolume(Math.max(0f, Math.min(1f, (float) (now - fadeInStartedAt) / fade)));
            return;
        }
        fadeInStartedAt = 0L;
        long duration = player.getDuration();
        if (duration != C.TIME_UNSET && duration > 0 && player.hasNextMediaItem()) {
            long remain = duration - player.getCurrentPosition();
            if (remain >= 0 && remain < fade) {
                player.setVolume(Math.max(.05f, Math.min(1f, (float) remain / fade)));
                return;
            }
        }
        if (player.getVolume() != 1f) player.setVolume(1f);
    }

    private void updateWidgetState() {
        if (player == null) return;
        MediaMetadata metadata = player.getMediaMetadata();
        MediaItem current = player.getCurrentMediaItem();
        String mediaId = current == null || current.mediaId == null ? "" : current.mediaId;
        String title = metadata.title == null ? "No Limit Music" : metadata.title.toString();
        String artist = metadata.artist == null ? "재생할 곡을 선택하세요" : metadata.artist.toString();
        String artwork = metadata.artworkUri == null ? "" : metadata.artworkUri.toString();
        SharedPreferences prefs = getSharedPreferences("widget_state", Context.MODE_PRIVATE);
        prefs.edit()
                .putString("mediaId", mediaId)
                .putString("title", title)
                .putString("artist", artist)
                .putString("artwork", artwork)
                .putBoolean("playing", player.isPlaying())
                .putBoolean("shuffle", player.getShuffleModeEnabled())
                .putInt("repeat", player.getRepeatMode())
                .apply();
        MusicWidgetProvider.updateAll(this);
        LargeMusicWidgetProvider.updateAll(this);
    }

    @Nullable @Override public MediaSession onGetSession(MediaSession.ControllerInfo controllerInfo) { return session; }
    @Override public void onTaskRemoved(@Nullable android.content.Intent rootIntent) { super.onTaskRemoved(rootIntent); }

    @Override public void onDestroy() {
        handler.removeCallbacks(serviceTicker);
        flushListeningTime();
        if (session != null) session.release();
        if (player != null) player.release();
        session = null;
        player = null;
        super.onDestroy();
    }
}
