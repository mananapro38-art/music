package com.nolimit.music;

import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.nolimit.music.data.LibraryStore;
import com.nolimit.music.data.YoutubeRepository;
import com.nolimit.music.model.SearchResult;
import com.nolimit.music.model.Track;
import com.nolimit.music.ui.PlaylistAdapter;
import com.nolimit.music.ui.SearchResultAdapter;
import com.nolimit.music.util.NetworkUtil;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends AppCompatActivity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private YoutubeRepository youtube;
    private LibraryStore library;
    private SearchResultAdapter resultsAdapter;
    private PlaylistAdapter playlistAdapter;
    private ExoPlayer player;

    private EditText searchInput;
    private Button searchButton;
    private ProgressBar searchProgress;
    private TextView engineStatus;
    private TextView playlistCount;
    private LinearLayout playerBar;
    private TextView nowPlaying;
    private TextView nowArtist;
    private Button playPause;

    private volatile boolean engineReady = false;
    private volatile boolean downloadRunning = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        youtube = new YoutubeRepository(this);
        library = new LibraryStore(this);
        player = new ExoPlayer.Builder(this).build();

        bindViews();
        setupLists();
        setupPlayer();
        setupActions();
        refreshPlaylist();
        showFirstRunNotice();
        initializeEngine();
    }

    private void bindViews() {
        searchInput = findViewById(R.id.etSearch);
        searchButton = findViewById(R.id.btnSearch);
        searchProgress = findViewById(R.id.progressSearch);
        engineStatus = findViewById(R.id.tvEngineStatus);
        playlistCount = findViewById(R.id.tvPlaylistCount);
        playerBar = findViewById(R.id.playerBar);
        nowPlaying = findViewById(R.id.tvNowPlaying);
        nowArtist = findViewById(R.id.tvNowArtist);
        playPause = findViewById(R.id.btnPlayPause);
    }

    private void setupLists() {
        RecyclerView results = findViewById(R.id.rvResults);
        RecyclerView playlist = findViewById(R.id.rvPlaylist);
        results.setLayoutManager(new LinearLayoutManager(this));
        playlist.setLayoutManager(new LinearLayoutManager(this));

        resultsAdapter = new SearchResultAdapter(this::download);
        playlistAdapter = new PlaylistAdapter(new PlaylistAdapter.Listener() {
            @Override public void onPlay(Track track) { play(track); }
            @Override public void onRemove(Track track) {
                library.remove(track.id);
                refreshPlaylist();
            }
        });
        results.setAdapter(resultsAdapter);
        playlist.setAdapter(playlistAdapter);
    }

    private void setupPlayer() {
        player.addListener(new Player.Listener() {
            @Override public void onIsPlayingChanged(boolean isPlaying) {
                playPause.setText(isPlaying ? "Ⅱ" : "▶");
            }
        });
        playPause.setOnClickListener(v -> {
            if (player.isPlaying()) player.pause(); else player.play();
        });
    }

    private void setupActions() {
        searchButton.setOnClickListener(v -> runSearch());
        searchInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                runSearch();
                return true;
            }
            return false;
        });
    }

    private void initializeEngine() {
        io.execute(() -> {
            try {
                youtube.init();
                engineReady = true;
                runOnUiThread(() -> engineStatus.setText("음악 엔진 준비됨 · QuickJS/EJS · Wi‑Fi에서만 저장"));
            } catch (Exception e) {
                runOnUiThread(() -> engineStatus.setText("엔진 준비 실패 · " + compactError(e)));
            }
        });
    }

    private void runSearch() {
        String query = searchInput.getText().toString().trim();
        if (TextUtils.isEmpty(query)) {
            searchInput.setError("검색어를 입력하세요.");
            return;
        }
        if (!engineReady) {
            toast("음악 엔진을 준비하는 중입니다.");
            return;
        }
        searchProgress.setVisibility(View.VISIBLE);
        searchButton.setEnabled(false);
        engineStatus.setText("검색 중… 공식 앨범 음원을 우선 정렬합니다.");

        io.execute(() -> {
            try {
                List<SearchResult> list = youtube.search(query);
                runOnUiThread(() -> {
                    resultsAdapter.submit(list);
                    searchProgress.setVisibility(View.GONE);
                    searchButton.setEnabled(true);
                    engineStatus.setText(list.isEmpty() ? "검색 결과가 없습니다." : "검색 완료 · 앨범/공식 오디오 후보를 위에 표시했습니다.");
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    searchProgress.setVisibility(View.GONE);
                    searchButton.setEnabled(true);
                    engineStatus.setText("검색 실패 · " + compactError(e));
                });
            }
        });
    }

    private void download(SearchResult item, int position) {
        if (downloadRunning) {
            toast("현재 다른 곡을 저장 중입니다.");
            return;
        }
        if (!NetworkUtil.isWifiConnected(this)) {
            new AlertDialog.Builder(this)
                    .setTitle("Wi‑Fi가 필요합니다")
                    .setMessage("이 테스트판은 Wi‑Fi 연결 상태에서만 음악을 저장합니다.")
                    .setPositiveButton("확인", null)
                    .show();
            return;
        }

        downloadRunning = true;
        resultsAdapter.setProgress(item.id, 0);
        engineStatus.setText("저장 시작 · " + item.title);

        io.execute(() -> {
            try {
                File file = youtube.downloadAudio(item, (percent, line) -> runOnUiThread(() -> {
                    resultsAdapter.setProgress(item.id, Math.max(0, Math.min(100, Math.round(percent))));
                    if (line != null && !line.trim().isEmpty()) engineStatus.setText("저장 중 " + Math.round(percent) + "%");
                }));

                Track track = new Track(item.id, item.title, item.channel, file.getAbsolutePath(), item.durationSeconds, System.currentTimeMillis());
                library.upsert(track);
                runOnUiThread(() -> {
                    downloadRunning = false;
                    resultsAdapter.clearProgress();
                    refreshPlaylist();
                    engineStatus.setText("저장 완료 · 내 플레이리스트에 추가됨");
                    play(track);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    downloadRunning = false;
                    resultsAdapter.clearProgress();
                    engineStatus.setText("저장 실패 · " + compactError(e));
                });
            }
        });
    }

    private void play(Track track) {
        File file = new File(track.path);
        if (!file.exists()) {
            toast("저장 파일을 찾을 수 없습니다.");
            library.remove(track.id);
            refreshPlaylist();
            return;
        }
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)));
        player.prepare();
        player.play();
        nowPlaying.setText(track.title);
        nowArtist.setText(track.artist);
        playerBar.setVisibility(View.VISIBLE);
    }

    private void refreshPlaylist() {
        List<Track> tracks = library.load();
        playlistAdapter.submit(tracks);
        playlistCount.setText(tracks.size() + "곡");
    }

    private void showFirstRunNotice() {
        if (getPreferences(MODE_PRIVATE).getBoolean("notice_seen", false)) return;
        new AlertDialog.Builder(this)
                .setTitle("테스트판 안내")
                .setMessage("다운로드 기능은 본인이 권리를 보유하거나 다운로드 허가를 받은 콘텐츠에만 사용하세요. 이 앱은 DRM 우회 기능을 포함하지 않습니다.")
                .setPositiveButton("확인", (d, w) -> getPreferences(MODE_PRIVATE).edit().putBoolean("notice_seen", true).apply())
                .show();
    }

    private String compactError(Exception e) {
        String m = e.getMessage();
        if (m == null || m.trim().isEmpty()) return e.getClass().getSimpleName();
        m = m.replace('\n', ' ');
        return m.length() > 100 ? m.substring(0, 100) + "…" : m;
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        if (player != null) player.release();
        io.shutdownNow();
        super.onDestroy();
    }
}
