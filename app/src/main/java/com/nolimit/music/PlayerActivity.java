package com.nolimit.music;

import android.content.ComponentName;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;

import com.google.common.util.concurrent.ListenableFuture;
import com.nolimit.music.data.LibraryStore;
import com.nolimit.music.data.SubtitleStore;
import com.nolimit.music.model.Track;
import com.nolimit.music.playback.PlaybackService;
import com.nolimit.music.util.ArtworkLoader;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class PlayerActivity extends AppCompatActivity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            syncProgressAndLyrics();
            handler.postDelayed(this, 200L);
        }
    };

    private LibraryStore library;
    private SubtitleStore subtitles;
    private ListenableFuture<MediaController> controllerFuture;
    private MediaController controller;

    private ImageView artwork;
    private TextView title;
    private TextView artist;
    private TextView previousLine;
    private TextView currentLine;
    private TextView nextLine;
    private TextView subtitleStatus;
    private TextView currentTime;
    private TextView totalTime;
    private TextView playPause;
    private SeekBar seek;

    private String loadedMediaId = "";
    private List<SubtitleStore.Cue> cues = Collections.emptyList();
    private boolean userSeeking = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SharedPreferences prefs = getSharedPreferences("settings", MODE_PRIVATE);
        AppCompatDelegate.setDefaultNightMode("light".equals(prefs.getString("theme", "dark"))
                ? AppCompatDelegate.MODE_NIGHT_NO : AppCompatDelegate.MODE_NIGHT_YES);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player);

        library = new LibraryStore(this);
        subtitles = new SubtitleStore(this);
        bindViews();
        setupActions();
        connectPlayer();
        handler.post(ticker);
    }

    private void bindViews() {
        artwork = findViewById(R.id.ivPlayerArtwork);
        title = findViewById(R.id.tvPlayerTitle);
        artist = findViewById(R.id.tvPlayerArtist);
        previousLine = findViewById(R.id.tvSubtitlePrevious);
        currentLine = findViewById(R.id.tvSubtitleCurrent);
        nextLine = findViewById(R.id.tvSubtitleNext);
        subtitleStatus = findViewById(R.id.tvSubtitleStatus);
        currentTime = findViewById(R.id.tvPlayerCurrentTime);
        totalTime = findViewById(R.id.tvPlayerDuration);
        playPause = findViewById(R.id.btnPlayerPlayPause);
        seek = findViewById(R.id.playerFullSeek);
    }

    private void setupActions() {
        findViewById(R.id.btnPlayerClose).setOnClickListener(v -> finish());
        playPause.setOnClickListener(v -> {
            if (controller == null) return;
            if (controller.isPlaying()) controller.pause(); else controller.play();
        });
        findViewById(R.id.btnPlayerPrevious).setOnClickListener(v -> {
            if (controller != null) controller.seekToPreviousMediaItem();
        });
        findViewById(R.id.btnPlayerNext).setOnClickListener(v -> {
            if (controller != null) controller.seekToNextMediaItem();
        });

        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                long duration = effectiveDurationMs();
                if (duration > 0) currentTime.setText(formatTime(duration * progress / 1000L));
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {
                userSeeking = true;
            }

            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                if (controller != null) {
                    long duration = effectiveDurationMs();
                    if (duration > 0) controller.seekTo(duration * seekBar.getProgress() / 1000L);
                }
                userSeeking = false;
                syncProgressAndLyrics();
            }
        });
    }

    private void connectPlayer() {
        SessionToken token = new SessionToken(this, new ComponentName(this, PlaybackService.class));
        controllerFuture = new MediaController.Builder(this, token).buildAsync();
        controllerFuture.addListener(() -> {
            try {
                controller = controllerFuture.get();
                controller.addListener(new Player.Listener() {
                    @Override public void onIsPlayingChanged(boolean isPlaying) { syncPlayerState(); }
                    @Override public void onPlaybackStateChanged(int playbackState) { syncPlayerState(); }
                    @Override public void onMediaItemTransition(MediaItem mediaItem, int reason) {
                        loadedMediaId = "";
                        syncPlayerState();
                    }
                });
                syncPlayerState();
            } catch (Exception ignored) {
                subtitleStatus.setText("플레이어 연결 실패");
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void syncPlayerState() {
        if (controller == null || controller.getCurrentMediaItem() == null) {
            title.setText("재생 중인 곡 없음");
            artist.setText("");
            playPause.setText("▶");
            cues = Collections.emptyList();
            showLyrics(-1);
            return;
        }

        MediaItem mediaItem = controller.getCurrentMediaItem();
        String mediaId = mediaItem.mediaId == null ? "" : mediaItem.mediaId;
        MediaMetadata metadata = controller.getMediaMetadata();
        Track track = library.find(mediaId);

        title.setText(track != null ? track.title : metadata.title == null ? "재생 중" : metadata.title);
        artist.setText(track != null ? track.artist : metadata.artist == null ? "" : metadata.artist);
        playPause.setText(controller.isPlaying() ? "Ⅱ" : "▶");

        if (!mediaId.equals(loadedMediaId)) {
            loadedMediaId = mediaId;
            cues = subtitles.load(mediaId);
            if (track != null) ArtworkLoader.load(artwork, this, track.id, track.thumbnailUrl);
            else artwork.setImageResource(R.drawable.ic_music_note);
            subtitleStatus.setText(cues.isEmpty() ? "이 곡에는 사용할 수 있는 자막이 없습니다." : "자막 " + cues.size() + "줄");
        }
        syncProgressAndLyrics();
    }

    private void syncProgressAndLyrics() {
        if (controller == null || controller.getCurrentMediaItem() == null) return;
        long position = Math.max(0L, controller.getCurrentPosition());
        long duration = effectiveDurationMs();
        totalTime.setText(formatTime(duration));
        if (!userSeeking) {
            currentTime.setText(formatTime(position));
            seek.setProgress(duration > 0 ? (int) Math.min(1000L, position * 1000L / duration) : 0);
        }
        showLyrics(findCueIndex(position));
        playPause.setText(controller.isPlaying() ? "Ⅱ" : "▶");
    }

    private int findCueIndex(long positionMs) {
        if (cues.isEmpty()) return -1;
        int lo = 0;
        int hi = cues.size() - 1;
        int answer = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (cues.get(mid).startMs <= positionMs) {
                answer = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return answer;
    }

    private void showLyrics(int index) {
        if (cues.isEmpty()) {
            previousLine.setText("");
            currentLine.setText("♪");
            nextLine.setText("");
            return;
        }
        if (index < 0) {
            previousLine.setText("");
            currentLine.setText("♪");
            nextLine.setText(cues.get(0).text);
            return;
        }
        previousLine.setText(index > 0 ? cues.get(index - 1).text : "");
        currentLine.setText(cues.get(index).text);
        nextLine.setText(index + 1 < cues.size() ? cues.get(index + 1).text : "");
    }

    private long effectiveDurationMs() {
        if (controller == null) return 0L;
        long duration = controller.getDuration();
        if (duration != C.TIME_UNSET && duration > 0) return duration;
        MediaItem current = controller.getCurrentMediaItem();
        if (current != null) {
            Track track = library.find(current.mediaId);
            if (track != null && track.durationSeconds > 0) return track.durationSeconds * 1000L;
        }
        return 0L;
    }

    private static String formatTime(long ms) {
        if (ms <= 0) return "0:00";
        long seconds = ms / 1000L;
        long minutes = seconds / 60L;
        long remainder = seconds % 60L;
        if (minutes >= 60) {
            long hours = minutes / 60L;
            return String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes % 60L, remainder);
        }
        return String.format(Locale.ROOT, "%d:%02d", minutes, remainder);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(ticker);
        if (controllerFuture != null) MediaController.releaseFuture(controllerFuture);
        controller = null;
        super.onDestroy();
    }
}
