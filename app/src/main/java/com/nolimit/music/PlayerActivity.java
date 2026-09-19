package com.nolimit.music;

import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.audiofx.AudioEffect;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
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
import com.nolimit.music.util.DisplayText;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class PlayerActivity extends AppCompatActivity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Runnable ticker = new Runnable() {
        @Override public void run() { syncProgressAndLyrics(); handler.postDelayed(this, 200L); }
    };

    private LibraryStore library;
    private SubtitleStore subtitles;
    private SharedPreferences settings;
    private ListenableFuture<MediaController> controllerFuture;
    private MediaController controller;
    private ActivityResultLauncher<Intent> lyricsImportLauncher;
    private LinearLayout root;
    private View artworkFrame;
    private ScrollView fullLyricsScroll;
    private LinearLayout fullLyricsContainer;
    private ImageView artwork;
    private TextView title, artist, previousLine, currentLine, nextLine, subtitleStatus, currentTime, totalTime;
    private ImageView playPause;
    private TextView shuffle, repeat, like, lyricsMode, speed;
    private SeekBar seek;
    private String loadedMediaId = "";
    private List<SubtitleStore.Cue> cues = Collections.emptyList();
    private final List<TextView> cueViews = new ArrayList<>();
    private boolean userSeeking = false;
    private boolean fullLyrics = false;
    private boolean darkTheme = true;

    @Override protected void onCreate(Bundle savedInstanceState) {
        settings = getSharedPreferences("settings", MODE_PRIVATE);
        darkTheme = !"light".equals(settings.getString("theme", "dark"));
        AppCompatDelegate.setDefaultNightMode(darkTheme ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_NO);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player);
        library = new LibraryStore(this);
        subtitles = new SubtitleStore(this);
        setupLyricsImportLauncher();
        bindViews(); applySystemInsets(); setupActions(); connectPlayer(); handler.post(ticker);
    }

    private void applySystemInsets() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(dp(14) + bars.left, dp(10) + bars.top,
                    dp(14) + bars.right, dp(10) + bars.bottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
    }

    private void setupLyricsImportLauncher() {
        lyricsImportLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() != RESULT_OK || result.getData() == null || result.getData().getData() == null) return;
            Uri uri = result.getData().getData();
            if (controller == null || controller.getCurrentMediaItem() == null) { toast("먼저 곡을 재생해 주세요."); return; }
            String mediaId = controller.getCurrentMediaItem().mediaId;
            io.execute(() -> {
                try {
                    subtitles.importLyrics(uri, mediaId);
                    runOnUiThread(() -> { loadedMediaId = ""; syncPlayerState(); toast("가사 파일을 연결했습니다."); });
                } catch (Exception e) {
                    runOnUiThread(() -> toast("가사 가져오기 실패 · " + compact(e)));
                }
            });
        });
    }

    private void bindViews() {
        root = findViewById(R.id.playerRoot); artworkFrame = findViewById(R.id.artworkFrame);
        fullLyricsScroll = findViewById(R.id.fullLyricsScroll); fullLyricsContainer = findViewById(R.id.fullLyricsContainer);
        artwork = findViewById(R.id.ivPlayerArtwork); title = findViewById(R.id.tvPlayerTitle); artist = findViewById(R.id.tvPlayerArtist);
        previousLine = findViewById(R.id.tvSubtitlePrevious); currentLine = findViewById(R.id.tvSubtitleCurrent); nextLine = findViewById(R.id.tvSubtitleNext);
        subtitleStatus = findViewById(R.id.tvSubtitleStatus); currentTime = findViewById(R.id.tvPlayerCurrentTime); totalTime = findViewById(R.id.tvPlayerDuration);
        playPause = findViewById(R.id.btnPlayerPlayPause); shuffle = findViewById(R.id.btnPlayerShuffle); repeat = findViewById(R.id.btnPlayerRepeat);
        like = findViewById(R.id.btnPlayerLike); lyricsMode = findViewById(R.id.btnLyricsMode); speed = findViewById(R.id.btnPlayerSpeed);
        seek = findViewById(R.id.playerFullSeek);
    }

    private void setupActions() {
        findViewById(R.id.btnPlayerClose).setOnClickListener(v -> finish());
        playPause.setOnClickListener(v -> { if (controller == null) return; if (controller.isPlaying()) controller.pause(); else controller.play(); });
        findViewById(R.id.btnPlayerPrevious).setOnClickListener(v -> { if (controller != null) controller.seekToPreviousMediaItem(); });
        findViewById(R.id.btnPlayerNext).setOnClickListener(v -> { if (controller != null) controller.seekToNextMediaItem(); });
        shuffle.setOnClickListener(v -> { if (controller != null) { controller.setShuffleModeEnabled(!controller.getShuffleModeEnabled()); syncModeButtons(); } });
        repeat.setOnClickListener(v -> cycleRepeat());
        like.setOnClickListener(v -> toggleLike());
        findViewById(R.id.btnPlayerQueue).setOnClickListener(v -> startActivity(new Intent(this, QueueActivity.class)));
        findViewById(R.id.btnPlayerSleep).setOnClickListener(v -> showSleepDialog());
        findViewById(R.id.btnPlayerEq).setOnClickListener(v -> openEq());
        speed.setOnClickListener(v -> cycleSpeed());
        lyricsMode.setOnClickListener(v -> toggleLyricsMode());
        lyricsMode.setOnLongClickListener(v -> { importLyricsFile(); return true; });
        currentLine.setOnClickListener(v -> toggleLyricsMode());

        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return; long duration = effectiveDurationMs(); if (duration > 0) currentTime.setText(formatTime(duration * progress / 1000L));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { userSeeking = true; }
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                if (controller != null) { long duration = effectiveDurationMs(); if (duration > 0) controller.seekTo(duration * seekBar.getProgress() / 1000L); }
                userSeeking = false; syncProgressAndLyrics();
            }
        });
    }

    private void importLyricsFile() {
        if (controller == null || controller.getCurrentMediaItem() == null) { toast("먼저 곡을 재생해 주세요."); return; }
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("text/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"text/plain", "text/vtt", "application/x-subrip", "application/octet-stream"});
        lyricsImportLauncher.launch(i);
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
                    @Override public void onMediaItemTransition(MediaItem mediaItem, int reason) { loadedMediaId = ""; syncPlayerState(); }
                    @Override public void onShuffleModeEnabledChanged(boolean enabled) { syncModeButtons(); }
                    @Override public void onRepeatModeChanged(int mode) { syncModeButtons(); }
                });
                syncPlayerState();
            } catch (Exception ignored) { subtitleStatus.setText("플레이어 연결 실패"); }
        }, ContextCompat.getMainExecutor(this));
    }

    private void syncPlayerState() {
        if (controller == null || controller.getCurrentMediaItem() == null) {
            title.setText("재생 중인 곡 없음"); artist.setText(""); playPause.setImageResource(R.drawable.ic_play_filled); cues = Collections.emptyList(); showLyrics(-1); return;
        }
        MediaItem mediaItem = controller.getCurrentMediaItem(); String mediaId = mediaItem.mediaId == null ? "" : mediaItem.mediaId;
        MediaMetadata metadata = controller.getMediaMetadata(); Track track = library.find(mediaId);
        title.setText(track != null ? track.title : metadata.title == null ? "재생 중" : DisplayText.cleanTitle(metadata.title.toString()));
        artist.setText(track != null ? track.artist : metadata.artist == null ? "" : DisplayText.cleanArtist(metadata.artist.toString()));
        playPause.setImageResource(controller.isPlaying() ? R.drawable.ic_pause_filled : R.drawable.ic_play_filled);
        if (!mediaId.equals(loadedMediaId)) {
            loadedMediaId = mediaId; cues = subtitles.load(mediaId);
            if (track != null) { ArtworkLoader.load(artwork, this, track.id, track.thumbnailUrl); applyArtworkTone(track); }
            else artwork.setImageResource(R.drawable.ic_music_note);
            subtitleStatus.setText(cues.isEmpty() ? "자막 없음 · ‘가사 전체’ 버튼을 길게 눌러 LRC/VTT/SRT 가져오기" : "가사/자막 " + cues.size() + "줄 · 줄을 눌러 이동");
            renderFullLyrics();
        }
        syncModeButtons(); syncLike(); syncProgressAndLyrics();
    }

    private void syncModeButtons() {
        if (controller == null) return;
        shuffle.setText(controller.getShuffleModeEnabled() ? "⤨•" : "⤨");
        int mode = controller.getRepeatMode(); repeat.setText(mode == Player.REPEAT_MODE_ONE ? "↻1" : mode == Player.REPEAT_MODE_ALL ? "↻•" : "↻");
        float s = controller.getPlaybackParameters().speed; speed.setText(String.format(Locale.ROOT, "%.2g×", s));
    }

    private void cycleRepeat() {
        if (controller == null) return; int mode = controller.getRepeatMode();
        controller.setRepeatMode(mode == Player.REPEAT_MODE_OFF ? Player.REPEAT_MODE_ALL : mode == Player.REPEAT_MODE_ALL ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
        syncModeButtons();
    }

    private void cycleSpeed() {
        if (controller == null) return; float now = controller.getPlaybackParameters().speed;
        float next = now < 1.1f ? 1.25f : now < 1.4f ? 1.5f : now < 1.8f ? 2f : 1f;
        controller.setPlaybackSpeed(next); syncModeButtons();
    }

    private void toggleLike() {
        if (controller == null || controller.getCurrentMediaItem() == null) return;
        Track track = library.find(controller.getCurrentMediaItem().mediaId); if (track == null) return;
        library.setLiked(track.id, !track.liked); syncLike();
    }

    private void syncLike() {
        if (controller == null || controller.getCurrentMediaItem() == null) { like.setText("♡"); return; }
        Track track = library.find(controller.getCurrentMediaItem().mediaId); like.setText(track != null && track.liked ? "♥" : "♡");
    }

    private void showSleepDialog() {
        String[] options = {"15분", "30분", "60분", "현재 곡이 끝나면", "현재 대기열이 끝나면", "타이머 끄기"};
        new AlertDialog.Builder(this).setTitle("수면 타이머").setItems(options, (d, which) -> {
            long deadline = 0L; long now = System.currentTimeMillis(); boolean queueEnd = false;
            if (which == 0) deadline = now + 15 * 60000L;
            else if (which == 1) deadline = now + 30 * 60000L;
            else if (which == 2) deadline = now + 60 * 60000L;
            else if (which == 3) { long duration = effectiveDurationMs(); long remain = controller == null ? 0 : Math.max(0, duration - controller.getCurrentPosition()); deadline = remain > 0 ? now + remain : 0L; }
            else if (which == 4) queueEnd = true;
            settings.edit().putLong("sleep_deadline", deadline).putBoolean("sleep_at_queue_end", queueEnd).apply();
            String message = queueEnd ? "현재 대기열이 끝나면 재생을 멈춥니다." : deadline == 0 ? "수면 타이머를 껐습니다." : "수면 타이머를 설정했습니다.";
            toast(message);
        }).show();
    }

    private void openEq() {
        Intent intent = new Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL);
        intent.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, getPackageName()); intent.putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC);
        if (intent.resolveActivity(getPackageManager()) != null) startActivity(intent);
        else new AlertDialog.Builder(this).setTitle("EQ").setMessage("이 기기에는 시스템 오디오 효과 패널이 없습니다.").setPositiveButton("확인", null).show();
    }

    private void toggleLyricsMode() {
        fullLyrics = !fullLyrics; artworkFrame.setVisibility(fullLyrics ? View.GONE : View.VISIBLE); fullLyricsScroll.setVisibility(fullLyrics ? View.VISIBLE : View.GONE);
        lyricsMode.setText(fullLyrics ? "앨범 아트" : "가사 전체"); syncProgressAndLyrics();
    }

    private void renderFullLyrics() {
        cueViews.clear(); fullLyricsContainer.removeAllViews();
        if (cues.isEmpty()) {
            TextView empty = lyricView("표시할 가사가 없습니다. ‘가사 전체’ 버튼을 길게 눌러 LRC/VTT/SRT 파일을 연결할 수 있습니다.", false); fullLyricsContainer.addView(empty); return;
        }
        for (SubtitleStore.Cue cue : cues) {
            TextView line = lyricView(cue.text, false); line.setOnClickListener(v -> { if (controller != null) controller.seekTo(cue.startMs); });
            cueViews.add(line); fullLyricsContainer.addView(line);
        }
    }

    private TextView lyricView(String text, boolean active) {
        TextView v = new TextView(this); v.setText(text); v.setTextColor(ContextCompat.getColor(this, R.color.text_primary)); v.setTextSize(active ? 20 : 17); v.setPadding(dp(8), dp(10), dp(8), dp(10)); v.setAlpha(active ? 1f : .55f); if (active) v.setTypeface(v.getTypeface(), android.graphics.Typeface.BOLD); return v;
    }

    private void syncProgressAndLyrics() {
        if (controller == null || controller.getCurrentMediaItem() == null) return;
        long position = Math.max(0L, controller.getCurrentPosition()); long duration = effectiveDurationMs(); totalTime.setText(formatTime(duration));
        if (!userSeeking) { currentTime.setText(formatTime(position)); seek.setProgress(duration > 0 ? (int) Math.min(1000L, position * 1000L / duration) : 0); }
        int index = findCueIndex(position); showLyrics(index); highlightFullLyrics(index); playPause.setImageResource(controller.isPlaying() ? R.drawable.ic_pause_filled : R.drawable.ic_play_filled);
    }

    private int findCueIndex(long positionMs) {
        if (cues.isEmpty()) return -1; int lo = 0, hi = cues.size() - 1, answer = -1;
        while (lo <= hi) { int mid = (lo + hi) >>> 1; if (cues.get(mid).startMs <= positionMs) { answer = mid; lo = mid + 1; } else hi = mid - 1; }
        if (answer >= 0 && positionMs > cues.get(answer).endMs) return -1;
        return answer;
    }

    private void showLyrics(int index) {
        if (cues.isEmpty()) { previousLine.setText(""); currentLine.setText("♪"); nextLine.setText(""); return; }
        if (index < 0) {
            int next = 0; long pos = controller == null ? 0L : controller.getCurrentPosition();
            while (next < cues.size() && cues.get(next).startMs <= pos) next++;
            previousLine.setText(""); currentLine.setText("♪"); nextLine.setText(next < cues.size() ? cues.get(next).text : ""); return;
        }
        previousLine.setText(index > 0 ? cues.get(index - 1).text : ""); currentLine.setText(cues.get(index).text); nextLine.setText(index + 1 < cues.size() ? cues.get(index + 1).text : "");
    }

    private void highlightFullLyrics(int index) {
        if (!fullLyrics || cueViews.isEmpty()) return;
        for (int i = 0; i < cueViews.size(); i++) { TextView v = cueViews.get(i); boolean active = i == index; v.setAlpha(active ? 1f : .48f); v.setTextSize(active ? 20 : 17); v.setTypeface(v.getTypeface(), active ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL); }
        if (index >= 0 && index < cueViews.size()) { View target = cueViews.get(index); fullLyricsScroll.smoothScrollTo(0, Math.max(0, target.getTop() - dp(110))); }
    }

    private void applyArtworkTone(Track track) {
        String source = ArtworkLoader.bestSource(this, track.id, track.thumbnailUrl);
        io.execute(() -> {
            Bitmap bitmap = ArtworkLoader.loadBitmapBlocking(this, source); if (bitmap == null) return;
            Bitmap tiny = Bitmap.createScaledBitmap(bitmap, 1, 1, true); int c = tiny.getPixel(0, 0); if (tiny != bitmap) tiny.recycle();
            int base = darkTheme ? Color.BLACK : Color.WHITE; float amount = darkTheme ? .28f : .16f;
            int blended = blend(base, c, amount); runOnUiThread(() -> root.setBackgroundColor(blended));
        });
    }

    private static int blend(int base, int accent, float a) { return Color.rgb((int)(Color.red(base)*(1-a)+Color.red(accent)*a),(int)(Color.green(base)*(1-a)+Color.green(accent)*a),(int)(Color.blue(base)*(1-a)+Color.blue(accent)*a)); }
    private long effectiveDurationMs() { if (controller == null) return 0L; long d = controller.getDuration(); if (d != C.TIME_UNSET && d > 0) return d; MediaItem current = controller.getCurrentMediaItem(); if (current != null) { Track t = library.find(current.mediaId); if (t != null && t.durationSeconds > 0) return t.durationSeconds * 1000L; } return 0L; }
    private static String formatTime(long ms) { if (ms <= 0) return "0:00"; long seconds=ms/1000L, minutes=seconds/60L, rem=seconds%60L; if(minutes>=60){long h=minutes/60L;return String.format(Locale.ROOT,"%d:%02d:%02d",h,minutes%60L,rem);} return String.format(Locale.ROOT,"%d:%02d",minutes,rem); }
    private static String compact(Throwable e) { String m=e==null?"":e.getMessage(); if(m==null||m.trim().isEmpty())return e==null?"오류":e.getClass().getSimpleName(); m=m.replace('\n',' ').replace('\r',' ').trim(); return m.length()>120?m.substring(0,120):m; }
    private void toast(String text){Toast.makeText(this,text,Toast.LENGTH_SHORT).show();}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}

    @Override protected void onDestroy() { handler.removeCallbacks(ticker); if (controllerFuture != null) MediaController.releaseFuture(controllerFuture); controller=null; io.shutdownNow(); super.onDestroy(); }
}
