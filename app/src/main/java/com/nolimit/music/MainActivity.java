package com.nolimit.music;

import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.common.util.concurrent.ListenableFuture;
import com.nolimit.music.data.LibraryStore;
import com.nolimit.music.data.LocalBackupStore;
import com.nolimit.music.data.PlaylistStore;
import com.nolimit.music.data.YoutubeChartsRepository;
import com.nolimit.music.data.YoutubeRepository;
import com.nolimit.music.model.Playlist;
import com.nolimit.music.model.SearchResult;
import com.nolimit.music.model.Track;
import com.nolimit.music.playback.PlaybackService;
import com.nolimit.music.ui.PlaylistAdapter;
import com.nolimit.music.ui.SearchResultAdapter;
import com.nolimit.music.util.NetworkUtil;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends AppCompatActivity {
    private static final String SMART_RECENT = "smart_recent";
    private static final String SMART_LIKED = "smart_liked";
    private static final String SMART_MOST = "smart_most";

    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private YoutubeRepository youtube;
    private YoutubeChartsRepository charts;
    private LibraryStore library;
    private PlaylistStore playlists;
    private LocalBackupStore backupStore;
    private SharedPreferences settings;

    private SearchResultAdapter resultsAdapter;
    private SearchResultAdapter chartsAdapter;
    private PlaylistAdapter playlistAdapter;

    private ListenableFuture<MediaController> controllerFuture;
    private MediaController controller;
    private ActivityResultLauncher<Intent> backupCreateLauncher;
    private ActivityResultLauncher<Intent> backupOpenLauncher;

    private EditText searchInput;
    private Button searchButton;
    private ProgressBar searchProgress;
    private TextView engineStatus;
    private TextView playlistCount;
    private TextView playlistTitle;
    private LinearLayout playlistFolders;
    private LinearLayout playerBar;
    private TextView nowPlaying;
    private TextView nowArtist;
    private TextView playPause;
    private TextView chartStatus;
    private TextView recentCount;
    private TextView likedCount;
    private TextView mostPlayedCount;
    private MaterialSwitch autoplaySwitch;

    private View sectionHome;
    private View sectionSearch;
    private View sectionPlaylist;
    private View sectionSettings;
    private TextView iconHome;
    private TextView iconSearch;
    private TextView iconPlaylist;
    private TextView iconSettings;

    private String activePlaylistId = PlaylistStore.DEFAULT_ID;
    private String activeSmart = null;
    private volatile boolean engineReady = false;
    private volatile boolean downloadRunning = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SharedPreferences earlySettings = getSharedPreferences("settings", MODE_PRIVATE);
        applyThemeMode(earlySettings.getString("theme", "dark"));
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        settings = getSharedPreferences("settings", MODE_PRIVATE);
        youtube = new YoutubeRepository(this);
        charts = new YoutubeChartsRepository();
        library = new LibraryStore(this);
        playlists = new PlaylistStore(this, library);
        backupStore = new LocalBackupStore(this);

        setupBackupLaunchers();
        bindViews();
        setupLists();
        setupPlaybackController();
        setupActions();
        switchTab("home");
        refreshAll();
        loadCharts();
        showFirstRunNotice();
        initializeEngine();
    }

    private void setupBackupLaunchers() {
        backupCreateLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() != RESULT_OK || result.getData() == null || result.getData().getData() == null) return;
            Uri uri = result.getData().getData();
            io.execute(() -> {
                try {
                    backupStore.exportTo(uri);
                    runOnUiThread(() -> toast("플레이리스트 백업을 저장했습니다."));
                } catch (Exception e) {
                    runOnUiThread(() -> toast("백업 실패 · " + compactError(e)));
                }
            });
        });

        backupOpenLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() != RESULT_OK || result.getData() == null || result.getData().getData() == null) return;
            Uri uri = result.getData().getData();
            io.execute(() -> {
                try {
                    backupStore.importFrom(uri);
                    runOnUiThread(() -> {
                        settings = getSharedPreferences("settings", MODE_PRIVATE);
                        playlists = new PlaylistStore(this, library);
                        activeSmart = null;
                        activePlaylistId = PlaylistStore.DEFAULT_ID;
                        autoplaySwitch.setChecked(settings.getBoolean("autoplay", true));
                        refreshAll();
                        toast("백업 복원 완료 · 없는 음악 파일은 곡을 누르면 다시 저장합니다.");
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> toast("복원 실패 · " + compactError(e)));
                }
            });
        });
    }

    private void bindViews() {
        searchInput = findViewById(R.id.etSearch);
        searchButton = findViewById(R.id.btnSearch);
        searchProgress = findViewById(R.id.progressSearch);
        engineStatus = findViewById(R.id.tvEngineStatus);
        playlistCount = findViewById(R.id.tvPlaylistCount);
        playlistTitle = findViewById(R.id.tvPlaylistTitle);
        playlistFolders = findViewById(R.id.playlistFolders);
        playerBar = findViewById(R.id.playerBar);
        nowPlaying = findViewById(R.id.tvNowPlaying);
        nowArtist = findViewById(R.id.tvNowArtist);
        playPause = findViewById(R.id.btnPlayPause);
        chartStatus = findViewById(R.id.tvChartStatus);
        recentCount = findViewById(R.id.tvRecentCount);
        likedCount = findViewById(R.id.tvLikedCount);
        mostPlayedCount = findViewById(R.id.tvMostPlayedCount);
        autoplaySwitch = findViewById(R.id.switchAutoplay);

        sectionHome = findViewById(R.id.sectionHome);
        sectionSearch = findViewById(R.id.sectionSearch);
        sectionPlaylist = findViewById(R.id.sectionPlaylist);
        sectionSettings = findViewById(R.id.sectionSettings);
        iconHome = findViewById(R.id.iconHome);
        iconSearch = findViewById(R.id.iconSearch);
        iconPlaylist = findViewById(R.id.iconPlaylist);
        iconSettings = findViewById(R.id.iconSettings);
    }

    private void setupLists() {
        RecyclerView results = findViewById(R.id.rvResults);
        RecyclerView chartList = findViewById(R.id.rvCharts);
        RecyclerView playlist = findViewById(R.id.rvPlaylist);
        results.setLayoutManager(new LinearLayoutManager(this));
        chartList.setLayoutManager(new LinearLayoutManager(this));
        chartList.setNestedScrollingEnabled(false);
        playlist.setLayoutManager(new LinearLayoutManager(this));

        resultsAdapter = new SearchResultAdapter(this::download);
        chartsAdapter = new SearchResultAdapter(this::download);
        playlistAdapter = new PlaylistAdapter(new PlaylistAdapter.Listener() {
            @Override public void onPlay(Track track) { playFromActiveList(track); }
            @Override public void onMore(Track track) { showTrackOptions(track); }
            @Override public void onLike(Track track, boolean liked) {
                library.setLiked(track.id, liked);
                refreshAll();
            }
            @Override public void onMove(int from, int to) {
                if (activeSmart == null) playlists.move(activePlaylistId, from, to);
            }
        });
        results.setAdapter(resultsAdapter);
        chartList.setAdapter(chartsAdapter);
        playlist.setAdapter(playlistAdapter);

        ItemTouchHelper helper = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {
            @Override
            public int getMovementFlags(RecyclerView recyclerView, RecyclerView.ViewHolder viewHolder) {
                return activeSmart == null ? makeMovementFlags(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) : 0;
            }

            @Override
            public boolean onMove(RecyclerView recyclerView, RecyclerView.ViewHolder source, RecyclerView.ViewHolder target) {
                return playlistAdapter.moveItem(source.getBindingAdapterPosition(), target.getBindingAdapterPosition());
            }

            @Override public void onSwiped(RecyclerView.ViewHolder viewHolder, int direction) { }
        });
        helper.attachToRecyclerView(playlist);
    }

    private void setupPlaybackController() {
        SessionToken token = new SessionToken(this, new ComponentName(this, PlaybackService.class));
        controllerFuture = new MediaController.Builder(this, token).buildAsync();
        controllerFuture.addListener(() -> {
            try {
                controller = controllerFuture.get();
                controller.addListener(new Player.Listener() {
                    @Override public void onIsPlayingChanged(boolean isPlaying) { syncPlayerUi(); }
                    @Override public void onMediaItemTransition(MediaItem mediaItem, int reason) {
                        syncPlayerUi();
                        refreshDashboard();
                        renderActivePlaylist();
                    }
                });
                syncPlayerUi();
            } catch (Exception e) {
                toast("플레이어 연결 실패 · " + compactError(e));
            }
        }, ContextCompat.getMainExecutor(this));

        playPause.setOnClickListener(v -> {
            if (controller == null) return;
            if (controller.isPlaying()) controller.pause(); else controller.play();
        });
        findViewById(R.id.btnPrevious).setOnClickListener(v -> {
            if (controller != null) controller.seekToPreviousMediaItem();
        });
        findViewById(R.id.btnNext).setOnClickListener(v -> {
            if (controller != null) controller.seekToNextMediaItem();
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

        findViewById(R.id.tabHome).setOnClickListener(v -> switchTab("home"));
        findViewById(R.id.tabSearch).setOnClickListener(v -> switchTab("search"));
        findViewById(R.id.tabPlaylist).setOnClickListener(v -> switchTab("playlist"));
        findViewById(R.id.tabSettings).setOnClickListener(v -> switchTab("settings"));

        findViewById(R.id.cardRecent).setOnClickListener(v -> openSmartPlaylist(SMART_RECENT));
        findViewById(R.id.cardLiked).setOnClickListener(v -> openSmartPlaylist(SMART_LIKED));
        findViewById(R.id.cardMostPlayed).setOnClickListener(v -> openSmartPlaylist(SMART_MOST));
        findViewById(R.id.btnCreatePlaylist).setOnClickListener(v -> showCreatePlaylistDialog());
        findViewById(R.id.btnPlayAll).setOnClickListener(v -> playQueue(getActiveTracks(), null));
        findViewById(R.id.btnExportBackup).setOnClickListener(v -> exportBackup());
        findViewById(R.id.btnImportBackup).setOnClickListener(v -> importBackup());

        autoplaySwitch.setChecked(settings.getBoolean("autoplay", true));
        autoplaySwitch.setOnCheckedChangeListener((button, checked) -> settings.edit().putBoolean("autoplay", checked).apply());
        findViewById(R.id.btnLightTheme).setOnClickListener(v -> setThemePreference("light"));
        findViewById(R.id.btnDarkTheme).setOnClickListener(v -> setThemePreference("dark"));
    }

    private void exportBackup() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, "NoLimitMusic-playlists-backup.json");
        backupCreateLauncher.launch(intent);
    }

    private void importBackup() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        backupOpenLauncher.launch(intent);
    }

    private void switchTab(String tab) {
        sectionHome.setVisibility("home".equals(tab) ? View.VISIBLE : View.GONE);
        sectionSearch.setVisibility("search".equals(tab) ? View.VISIBLE : View.GONE);
        sectionPlaylist.setVisibility("playlist".equals(tab) ? View.VISIBLE : View.GONE);
        sectionSettings.setVisibility("settings".equals(tab) ? View.VISIBLE : View.GONE);

        setNavActive(iconHome, "home".equals(tab));
        setNavActive(iconSearch, "search".equals(tab));
        setNavActive(iconPlaylist, "playlist".equals(tab));
        setNavActive(iconSettings, "settings".equals(tab));
        if ("playlist".equals(tab)) {
            renderPlaylistFolders();
            renderActivePlaylist();
        }
    }

    private void setNavActive(TextView icon, boolean active) {
        icon.setBackgroundResource(active ? R.drawable.bg_nav_active : 0);
        icon.setAlpha(active ? 1f : 0.62f);
    }

    private void initializeEngine() {
        io.execute(() -> {
            try {
                youtube.init();
                engineReady = true;
                runOnUiThread(() -> engineStatus.setText("음악 엔진 준비됨 · Wi‑Fi에서 저장 · 백그라운드 재생"));
            } catch (Exception e) {
                runOnUiThread(() -> engineStatus.setText("엔진 준비 실패 · " + compactError(e)));
            }
        });
    }

    private void loadCharts() {
        chartStatus.setText("불러오는 중");
        io.execute(() -> {
            try {
                List<SearchResult> list = charts.loadKoreaTopSongs(20);
                runOnUiThread(() -> {
                    chartsAdapter.submit(list);
                    chartStatus.setText(list.isEmpty() ? "표시할 차트 없음" : "주간 Top " + list.size());
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    chartsAdapter.submit(Collections.emptyList());
                    chartStatus.setText("차트 오류 · " + compactError(e));
                });
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
                    engineStatus.setText(list.isEmpty() ? "검색 결과가 없습니다." : "검색 완료 · 앨범/공식 오디오 후보 우선");
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
        Track existing = library.find(item.id);
        if (existing != null && new File(existing.path).exists()) {
            playlists.addTrack(PlaylistStore.DEFAULT_ID, existing.id);
            refreshAll();
            engineStatus.setText("이미 저장된 곡 · 재생합니다.");
            playDownloaded(existing);
            return;
        }
        if (downloadRunning) {
            toast("현재 다른 곡을 저장 중입니다.");
            return;
        }
        if (!NetworkUtil.isWifiConnected(this)) {
            new AlertDialog.Builder(this)
                    .setTitle("Wi‑Fi가 필요합니다")
                    .setMessage("저장은 Wi‑Fi 연결 상태에서만 시작합니다. 이미 저장된 곡의 재생은 오프라인에서도 가능합니다.")
                    .setPositiveButton("확인", null)
                    .show();
            return;
        }

        downloadRunning = true;
        resultsAdapter.setProgress(item.id, 0);
        chartsAdapter.setProgress(item.id, 0);
        engineStatus.setText("저장 시작 · " + item.title);

        io.execute(() -> {
            try {
                File file = youtube.downloadAudio(item, (percent, line) -> runOnUiThread(() -> {
                    int p = Math.max(0, Math.min(100, Math.round(percent)));
                    resultsAdapter.setProgress(item.id, p);
                    chartsAdapter.setProgress(item.id, p);
                    engineStatus.setText("저장 중 " + p + "%");
                }));

                Track track = new Track(item.id, item.title, item.channel, file.getAbsolutePath(), item.durationSeconds, System.currentTimeMillis());
                library.upsert(track);
                playlists.addTrack(PlaylistStore.DEFAULT_ID, track.id);
                runOnUiThread(() -> {
                    downloadRunning = false;
                    resultsAdapter.clearProgress();
                    chartsAdapter.clearProgress();
                    refreshAll();
                    engineStatus.setText("저장 완료 · 내 플레이리스트에 추가됨");
                    playDownloaded(track);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    downloadRunning = false;
                    resultsAdapter.clearProgress();
                    chartsAdapter.clearProgress();
                    engineStatus.setText("저장 실패 · " + compactError(e));
                });
            }
        });
    }

    private void playDownloaded(Track track) {
        if (settings.getBoolean("autoplay", true)) {
            playQueue(playlists.tracks(PlaylistStore.DEFAULT_ID), track.id);
        } else {
            playQueue(Collections.singletonList(track), track.id);
        }
    }

    private void playFromActiveList(Track track) {
        if (!new File(track.path).exists()) {
            if (!engineReady) {
                toast("음악 엔진을 준비하는 중입니다.");
                return;
            }
            toast("백업에서 복원된 곡입니다. 음악 파일을 다시 저장합니다.");
            SearchResult recovery = new SearchResult(
                    track.id,
                    track.title,
                    track.artist,
                    "https://www.youtube.com/watch?v=" + track.id,
                    track.durationSeconds,
                    "",
                    0,
                    "복원 곡"
            );
            download(recovery, -1);
            return;
        }
        if (settings.getBoolean("autoplay", true)) playQueue(getActiveTracks(), track.id);
        else playQueue(Collections.singletonList(track), track.id);
    }

    private void playQueue(List<Track> source, String startId) {
        if (controller == null) {
            toast("플레이어를 연결하는 중입니다.");
            return;
        }
        List<MediaItem> items = new ArrayList<>();
        int startIndex = 0;
        for (Track track : source) {
            File file = new File(track.path);
            if (!file.exists()) continue;
            if (startId != null && startId.equals(track.id)) startIndex = items.size();
            MediaMetadata metadata = new MediaMetadata.Builder()
                    .setTitle(track.title)
                    .setArtist(track.artist)
                    .build();
            items.add(new MediaItem.Builder()
                    .setMediaId(track.id)
                    .setUri(Uri.fromFile(file))
                    .setMediaMetadata(metadata)
                    .build());
        }
        if (items.isEmpty()) {
            toast("재생할 저장 곡이 없습니다.");
            return;
        }
        controller.setMediaItems(items, Math.min(startIndex, items.size() - 1), 0L);
        controller.prepare();
        controller.play();
        syncPlayerUi();
    }

    private void syncPlayerUi() {
        if (controller == null || controller.getMediaItemCount() == 0) {
            playerBar.setVisibility(View.GONE);
            return;
        }
        playerBar.setVisibility(View.VISIBLE);
        MediaMetadata metadata = controller.getMediaMetadata();
        nowPlaying.setText(metadata.title == null ? "재생 중" : metadata.title);
        nowArtist.setText(metadata.artist == null ? "" : metadata.artist);
        playPause.setText(controller.isPlaying() ? "Ⅱ" : "▶");
    }

    private void refreshAll() {
        refreshDashboard();
        renderPlaylistFolders();
        renderActivePlaylist();
    }

    private void refreshDashboard() {
        recentCount.setText(library.recent(Integer.MAX_VALUE).size() + "곡");
        likedCount.setText(library.liked().size() + "곡");
        mostPlayedCount.setText(library.mostPlayed(Integer.MAX_VALUE).size() + "곡");
    }

    private List<Track> getActiveTracks() {
        if (SMART_RECENT.equals(activeSmart)) return library.recent(Integer.MAX_VALUE);
        if (SMART_LIKED.equals(activeSmart)) return library.liked();
        if (SMART_MOST.equals(activeSmart)) return library.mostPlayed(Integer.MAX_VALUE);
        return playlists.tracks(activePlaylistId);
    }

    private void openSmartPlaylist(String type) {
        activeSmart = type;
        switchTab("playlist");
        renderActivePlaylist();
    }

    private void openCustomPlaylist(String id) {
        activeSmart = null;
        activePlaylistId = id;
        switchTab("playlist");
        renderPlaylistFolders();
        renderActivePlaylist();
    }

    private void renderActivePlaylist() {
        if (playlistAdapter == null) return;
        List<Track> tracks = getActiveTracks();
        playlistAdapter.submit(tracks);
        playlistCount.setText(tracks.size() + "곡" + (activeSmart == null ? " · 길게 눌러 끌어서 순서 변경" : " · 자동 스마트 플리"));
        if (SMART_RECENT.equals(activeSmart)) playlistTitle.setText("최근 추가한 곡");
        else if (SMART_LIKED.equals(activeSmart)) playlistTitle.setText("좋아요한 곡");
        else if (SMART_MOST.equals(activeSmart)) playlistTitle.setText("많이 재생한 곡");
        else {
            Playlist p = playlists.get(activePlaylistId);
            playlistTitle.setText(p == null ? "플레이리스트" : p.name);
        }
    }

    private void renderPlaylistFolders() {
        if (playlistFolders == null) return;
        playlistFolders.removeAllViews();
        for (Playlist p : playlists.load()) {
            TextView chip = new TextView(this);
            chip.setText("♫  " + p.name);
            chip.setGravity(Gravity.CENTER);
            chip.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
            chip.setTextSize(12f);
            chip.setBackgroundResource(R.drawable.bg_smart_card);
            chip.setPadding(dp(15), 0, dp(15), 0);
            chip.setAlpha(activeSmart == null && p.id.equals(activePlaylistId) ? 1f : 0.62f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42));
            lp.setMarginEnd(dp(8));
            chip.setLayoutParams(lp);
            chip.setOnClickListener(v -> openCustomPlaylist(p.id));
            chip.setOnLongClickListener(v -> {
                showPlaylistFolderMenu(p);
                return true;
            });
            playlistFolders.addView(chip);
        }
    }

    private void showCreatePlaylistDialog() {
        EditText input = new EditText(this);
        input.setHint("플레이리스트 이름");
        new AlertDialog.Builder(this)
                .setTitle("새 플레이리스트")
                .setView(input)
                .setNegativeButton("취소", null)
                .setPositiveButton("만들기", (d, w) -> {
                    Playlist p = playlists.create(input.getText().toString());
                    openCustomPlaylist(p.id);
                })
                .show();
    }

    private void showPlaylistFolderMenu(Playlist playlist) {
        if (PlaylistStore.DEFAULT_ID.equals(playlist.id)) {
            toast("기본 플레이리스트는 삭제할 수 없습니다.");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(playlist.name)
                .setItems(new String[]{"이름 바꾸기", "플레이리스트 삭제"}, (d, which) -> {
                    if (which == 0) showRenamePlaylistDialog(playlist);
                    else {
                        playlists.delete(playlist.id);
                        activePlaylistId = PlaylistStore.DEFAULT_ID;
                        activeSmart = null;
                        refreshAll();
                    }
                })
                .show();
    }

    private void showRenamePlaylistDialog(Playlist playlist) {
        EditText input = new EditText(this);
        input.setText(playlist.name);
        new AlertDialog.Builder(this)
                .setTitle("이름 바꾸기")
                .setView(input)
                .setNegativeButton("취소", null)
                .setPositiveButton("저장", (d, w) -> {
                    playlists.rename(playlist.id, input.getText().toString());
                    refreshAll();
                })
                .show();
    }

    private void showTrackOptions(Track track) {
        List<String> options = new ArrayList<>();
        options.add("다른 플레이리스트에 추가");
        if (activeSmart == null) options.add("현재 플레이리스트에서 제거");
        options.add("저장 파일 삭제");
        new AlertDialog.Builder(this)
                .setTitle(track.title)
                .setItems(options.toArray(new String[0]), (d, which) -> {
                    String selected = options.get(which);
                    if (selected.startsWith("다른")) showAddToPlaylistDialog(track);
                    else if (selected.startsWith("현재")) {
                        playlists.removeTrack(activePlaylistId, track.id);
                        refreshAll();
                    } else confirmDeleteTrack(track);
                })
                .show();
    }

    private void showAddToPlaylistDialog(Track track) {
        List<Playlist> all = playlists.load();
        String[] names = new String[all.size()];
        for (int i = 0; i < all.size(); i++) names[i] = all.get(i).name;
        new AlertDialog.Builder(this)
                .setTitle("플레이리스트에 추가")
                .setItems(names, (d, which) -> {
                    playlists.addTrack(all.get(which).id, track.id);
                    toast(all.get(which).name + "에 추가했습니다.");
                    refreshAll();
                })
                .show();
    }

    private void confirmDeleteTrack(Track track) {
        new AlertDialog.Builder(this)
                .setTitle("저장 파일 삭제")
                .setMessage("이 곡을 기기에서 삭제하고 모든 플레이리스트에서도 제거할까요?")
                .setNegativeButton("취소", null)
                .setPositiveButton("삭제", (d, w) -> {
                    if (controller != null && track.id.equals(controller.getCurrentMediaItem() == null ? "" : controller.getCurrentMediaItem().mediaId)) {
                        controller.stop();
                        controller.clearMediaItems();
                    }
                    playlists.removeTrackEverywhere(track.id);
                    library.remove(track.id);
                    refreshAll();
                    syncPlayerUi();
                })
                .show();
    }

    private void setThemePreference(String theme) {
        settings.edit().putString("theme", theme).apply();
        applyThemeMode(theme);
    }

    private static void applyThemeMode(String theme) {
        AppCompatDelegate.setDefaultNightMode("light".equals(theme)
                ? AppCompatDelegate.MODE_NIGHT_NO
                : AppCompatDelegate.MODE_NIGHT_YES);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
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
        return m.length() > 140 ? m.substring(0, 140) + "…" : m;
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (library != null) refreshAll();
        syncPlayerUi();
    }

    @Override
    protected void onDestroy() {
        if (controllerFuture != null) MediaController.releaseFuture(controllerFuture);
        controller = null;
        io.shutdownNow();
        super.onDestroy();
    }
}
