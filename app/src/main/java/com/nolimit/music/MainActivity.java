package com.nolimit.music;

import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;
import androidx.media3.common.C;
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
import com.nolimit.music.data.DjPlanner;
import com.nolimit.music.data.DownloadQueueManager;
import com.nolimit.music.data.LibraryStore;
import com.nolimit.music.data.LocalBackupStore;
import com.nolimit.music.data.PlaylistStore;
import com.nolimit.music.data.TrackStorage;
import com.nolimit.music.data.YoutubeChartsRepository;
import com.nolimit.music.data.YoutubeRepository;
import com.nolimit.music.model.Playlist;
import com.nolimit.music.model.SearchResult;
import com.nolimit.music.model.Track;
import com.nolimit.music.playback.PlaybackService;
import com.nolimit.music.ui.PlaylistAdapter;
import com.nolimit.music.ui.SearchResultAdapter;
import com.nolimit.music.util.ArtworkLoader;
import com.nolimit.music.util.NetworkUtil;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends AppCompatActivity {
    private static final String SMART_RECENT = "smart_recent";
    private static final String SMART_LIKED = "smart_liked";
    private static final String SMART_MOST = "smart_most";
    private static final String KEY_ALLOW_MOBILE_DOWNLOAD = "allow_mobile_download";

    private static final String[] CHART_CATEGORY_LABELS = {"주간 인기곡", "주간 인기 아티스트", "주간 인기 뮤직비디오", "급상승 음악"};
    private static final YoutubeChartsRepository.Category[] CHART_CATEGORIES = {
            YoutubeChartsRepository.Category.TOP_SONGS, YoutubeChartsRepository.Category.TOP_ARTISTS,
            YoutubeChartsRepository.Category.TOP_VIDEOS, YoutubeChartsRepository.Category.TRENDING
    };
    private static final String[] CHART_COUNTRY_LABELS = {"대한민국", "글로벌", "미국", "일본", "영국", "캐나다", "호주", "독일", "프랑스", "대만", "싱가포르"};
    private static final String[] CHART_COUNTRY_CODES = {"kr", "", "us", "jp", "gb", "ca", "au", "de", "fr", "tw", "sg"};

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable positionTicker = new Runnable() {
        @Override public void run() { updatePlayerProgress(); mainHandler.postDelayed(this, 500L); }
    };

    private YoutubeRepository youtube;
    private YoutubeChartsRepository charts;
    private LibraryStore library;
    private PlaylistStore playlists;
    private LocalBackupStore backupStore;
    private SharedPreferences settings;
    private DownloadQueueManager downloadQueue;

    private SearchResultAdapter resultsAdapter, chartsAdapter, chartBrowserAdapter, djAdapter;
    private PlaylistAdapter playlistAdapter;
    private ListenableFuture<MediaController> controllerFuture;
    private MediaController controller;
    private ActivityResultLauncher<Intent> backupCreateLauncher, backupOpenLauncher;

    private EditText searchInput, djPrompt;
    private Button searchButton, djGenerate;
    private ProgressBar searchProgress;
    private TextView engineStatus, playlistCount, playlistTitle, nowPlaying, nowArtist, playPause, currentTime, totalTime;
    private TextView chartStatus, chartBrowserStatus, djStatus, recentCount, likedCount, mostPlayedCount;
    private LinearLayout playlistFolders;
    private View playerBar;
    private ImageView nowArtwork;
    private SeekBar playerSeek;
    private Spinner chartCategorySpinner, chartCountrySpinner;
    private MaterialSwitch autoplaySwitch, mobileDownloadSwitch;
    private View sectionHome, sectionSearch, sectionPlaylist, sectionSettings, sectionCharts, sectionDj;
    private TextView iconHome, iconSearch, iconPlaylist, iconSettings;

    private String activePlaylistId = PlaylistStore.DEFAULT_ID;
    private String activeSmart = null;
    private volatile boolean engineReady = false;
    private volatile boolean chartLoading = false;
    private volatile boolean djLoading = false;
    private boolean userSeeking = false;
    private boolean suppressSettingCallbacks = false;

    private final DownloadQueueManager.Listener downloadListener = new DownloadQueueManager.Listener() {
        @Override public void onQueueChanged() { refreshAll(); }
        @Override public void onProgress(String id, int progress, String title) {
            setAllDownloadProgress(id, progress);
            if (engineStatus != null) engineStatus.setText("저장 중 " + progress + "% · " + title);
        }
        @Override public void onCompleted(Track track) {
            clearDownloadProgress(track.id); refreshAll();
            if (engineStatus != null) engineStatus.setText("저장 완료 · " + track.title);
            if (controller != null && controller.getMediaItemCount() == 0 && settings.getBoolean("autoplay", true)) playDownloaded(track);
        }
        @Override public void onFailed(SearchResult item, String error) {
            clearDownloadProgress(item.id);
            if (engineStatus != null) engineStatus.setText("저장 실패 · " + error);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
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
        downloadQueue = DownloadQueueManager.get(this);
        setupBackupLaunchers(); bindViews(); setupLists(); setupPlaybackController(); setupActions(); setupChartControls();
        downloadQueue.addListener(downloadListener); downloadQueue.kick();
        switchTab("home"); refreshAll(); loadHomeCharts(); showFirstRunNotice(); initializeEngine(); mainHandler.post(positionTicker);
    }

    private void setupBackupLaunchers() {
        backupCreateLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() != RESULT_OK || result.getData() == null || result.getData().getData() == null) return;
            Uri uri = result.getData().getData(); io.execute(() -> {
                try { backupStore.exportTo(uri); runOnUiThread(() -> toast("플레이리스트 백업을 저장했습니다.")); }
                catch (Exception e) { runOnUiThread(() -> toast("백업 실패 · " + compactError(e))); }
            });
        });
        backupOpenLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() != RESULT_OK || result.getData() == null || result.getData().getData() == null) return;
            Uri uri = result.getData().getData(); io.execute(() -> {
                try {
                    backupStore.importFrom(uri);
                    runOnUiThread(() -> {
                        settings = getSharedPreferences("settings", MODE_PRIVATE); playlists = new PlaylistStore(this, library);
                        activeSmart = null; activePlaylistId = PlaylistStore.DEFAULT_ID; syncSettingsUi(); refreshAll(); updateReadyStatus();
                        toast("백업 복원 완료 · 공용 Music 보존본이 있으면 자동으로 다시 연결합니다.");
                    });
                } catch (Exception e) { runOnUiThread(() -> toast("복원 실패 · " + compactError(e))); }
            });
        });
    }

    private void bindViews() {
        searchInput=findViewById(R.id.etSearch); searchButton=findViewById(R.id.btnSearch); searchProgress=findViewById(R.id.progressSearch); engineStatus=findViewById(R.id.tvEngineStatus);
        playlistCount=findViewById(R.id.tvPlaylistCount); playlistTitle=findViewById(R.id.tvPlaylistTitle); playlistFolders=findViewById(R.id.playlistFolders); playerBar=findViewById(R.id.playerBar);
        nowArtwork=findViewById(R.id.ivNowArtwork); nowPlaying=findViewById(R.id.tvNowPlaying); nowArtist=findViewById(R.id.tvNowArtist); playPause=findViewById(R.id.btnPlayPause);
        playerSeek=findViewById(R.id.playerSeek); currentTime=findViewById(R.id.tvCurrentTime); totalTime=findViewById(R.id.tvDuration); chartStatus=findViewById(R.id.tvChartStatus);
        chartBrowserStatus=findViewById(R.id.tvChartBrowserStatus); djStatus=findViewById(R.id.tvDjStatus); recentCount=findViewById(R.id.tvRecentCount); likedCount=findViewById(R.id.tvLikedCount); mostPlayedCount=findViewById(R.id.tvMostPlayedCount);
        djPrompt=findViewById(R.id.etDjPrompt); djGenerate=findViewById(R.id.btnDjGenerate); chartCategorySpinner=findViewById(R.id.spinnerChartCategory); chartCountrySpinner=findViewById(R.id.spinnerChartCountry);
        autoplaySwitch=findViewById(R.id.switchAutoplay); mobileDownloadSwitch=findViewById(R.id.switchMobileDownload);
        sectionHome=findViewById(R.id.sectionHome); sectionSearch=findViewById(R.id.sectionSearch); sectionPlaylist=findViewById(R.id.sectionPlaylist); sectionSettings=findViewById(R.id.sectionSettings); sectionCharts=findViewById(R.id.sectionCharts); sectionDj=findViewById(R.id.sectionDj);
        iconHome=findViewById(R.id.iconHome); iconSearch=findViewById(R.id.iconSearch); iconPlaylist=findViewById(R.id.iconPlaylist); iconSettings=findViewById(R.id.iconSettings);
    }

    private void setupLists() {
        RecyclerView results=findViewById(R.id.rvResults), chartPreview=findViewById(R.id.rvCharts), chartBrowser=findViewById(R.id.rvChartBrowser), djResults=findViewById(R.id.rvDjResults), playlist=findViewById(R.id.rvPlaylist);
        results.setLayoutManager(new LinearLayoutManager(this)); chartPreview.setLayoutManager(new LinearLayoutManager(this)); chartPreview.setNestedScrollingEnabled(false); chartPreview.setHasFixedSize(false);
        chartBrowser.setLayoutManager(new LinearLayoutManager(this)); djResults.setLayoutManager(new LinearLayoutManager(this)); playlist.setLayoutManager(new LinearLayoutManager(this));
        resultsAdapter=new SearchResultAdapter(this::download); chartsAdapter=new SearchResultAdapter(this::onChartItem); chartBrowserAdapter=new SearchResultAdapter(this::onChartItem); djAdapter=new SearchResultAdapter(this::download);
        playlistAdapter=new PlaylistAdapter(new PlaylistAdapter.Listener() {
            @Override public void onPlay(Track track){playFromActiveList(track);} @Override public void onMore(Track track){showTrackOptions(track);}
            @Override public void onLike(Track track, boolean liked){library.setLiked(track.id,liked);refreshAll();}
            @Override public void onMove(int from,int to){if(activeSmart==null)playlists.move(activePlaylistId,from,to);}
        });
        results.setAdapter(resultsAdapter); chartPreview.setAdapter(chartsAdapter); chartBrowser.setAdapter(chartBrowserAdapter); djResults.setAdapter(djAdapter); playlist.setAdapter(playlistAdapter);
        new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP|ItemTouchHelper.DOWN,0){
            @Override public int getMovementFlags(RecyclerView rv, RecyclerView.ViewHolder vh){return activeSmart==null?makeMovementFlags(ItemTouchHelper.UP|ItemTouchHelper.DOWN,0):0;}
            @Override public boolean onMove(RecyclerView rv,RecyclerView.ViewHolder s,RecyclerView.ViewHolder t){return playlistAdapter.moveItem(s.getBindingAdapterPosition(),t.getBindingAdapterPosition());}
            @Override public void onSwiped(RecyclerView.ViewHolder vh,int d){}
        }).attachToRecyclerView(playlist);
    }

    private void setupPlaybackController() {
        SessionToken token=new SessionToken(this,new ComponentName(this,PlaybackService.class)); controllerFuture=new MediaController.Builder(this,token).buildAsync();
        controllerFuture.addListener(()->{try{controller=controllerFuture.get();controller.addListener(new Player.Listener(){
            @Override public void onIsPlayingChanged(boolean p){syncPlayerUi();} @Override public void onPlaybackStateChanged(int s){syncPlayerUi();}
            @Override public void onMediaItemTransition(MediaItem i,int r){syncPlayerUi();refreshDashboard();renderActivePlaylist();}
        });syncPlayerUi();}catch(Exception e){toast("플레이어 연결 실패 · "+compactError(e));}},ContextCompat.getMainExecutor(this));
        playPause.setOnClickListener(v->{if(controller==null)return;if(controller.isPlaying())controller.pause();else controller.play();});
        findViewById(R.id.btnPrevious).setOnClickListener(v->{if(controller!=null)controller.seekToPreviousMediaItem();}); findViewById(R.id.btnNext).setOnClickListener(v->{if(controller!=null)controller.seekToNextMediaItem();});
        playerSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(SeekBar s,int p,boolean from){if(from&&controller!=null){long d=effectiveDurationMs();if(d>0)currentTime.setText(formatTime(d*p/1000L));}}
            @Override public void onStartTrackingTouch(SeekBar s){userSeeking=true;} @Override public void onStopTrackingTouch(SeekBar s){if(controller!=null){long d=effectiveDurationMs();if(d>0)controller.seekTo(d*s.getProgress()/1000L);}userSeeking=false;updatePlayerProgress();}
        });
    }

    private void setupActions() {
        searchButton.setOnClickListener(v->runSearch()); searchInput.setOnEditorActionListener((v,a,e)->{if(a==EditorInfo.IME_ACTION_SEARCH){runSearch();return true;}return false;});
        findViewById(R.id.tabHome).setOnClickListener(v->switchTab("home")); findViewById(R.id.tabSearch).setOnClickListener(v->switchTab("search")); findViewById(R.id.tabPlaylist).setOnClickListener(v->switchTab("playlist")); findViewById(R.id.tabSettings).setOnClickListener(v->switchTab("settings"));
        findViewById(R.id.cardRecent).setOnClickListener(v->openSmartPlaylist(SMART_RECENT)); findViewById(R.id.cardLiked).setOnClickListener(v->openSmartPlaylist(SMART_LIKED)); findViewById(R.id.cardMostPlayed).setOnClickListener(v->openSmartPlaylist(SMART_MOST));
        findViewById(R.id.cardCharts).setOnClickListener(v->openCharts()); findViewById(R.id.cardDj).setOnClickListener(v->openDj()); findViewById(R.id.btnChartsBack).setOnClickListener(v->switchTab("home")); findViewById(R.id.btnDjBack).setOnClickListener(v->switchTab("home"));
        djGenerate.setOnClickListener(v->runDj()); djPrompt.setOnEditorActionListener((v,a,e)->{if(a==EditorInfo.IME_ACTION_SEARCH||a==EditorInfo.IME_ACTION_DONE){runDj();return true;}return false;});
        findViewById(R.id.btnCreatePlaylist).setOnClickListener(v->showCreatePlaylistDialog());
        View playAll=findViewById(R.id.btnPlayAll); playAll.setOnClickListener(v->playQueue(getActiveTracks(),null)); playAll.setOnLongClickListener(v->{int n=enqueueMissingTracks(getActiveTracks());toast(n>0?n+"곡을 다운로드 대기열에 추가했습니다.":"다시 받을 곡이 없습니다.");return true;});
        findViewById(R.id.btnExportBackup).setOnClickListener(v->exportBackup()); findViewById(R.id.btnImportBackup).setOnClickListener(v->importBackup());
        syncSettingsUi();
        autoplaySwitch.setOnCheckedChangeListener((b,c)->{if(!suppressSettingCallbacks)settings.edit().putBoolean("autoplay",c).apply();});
        mobileDownloadSwitch.setOnCheckedChangeListener((b,c)->{if(suppressSettingCallbacks)return;if(c)showMobileDataConfirmation();else{settings.edit().putBoolean(KEY_ALLOW_MOBILE_DOWNLOAD,false).apply();updateReadyStatus();}});
        findViewById(R.id.btnLightTheme).setOnClickListener(v->setThemePreference("light")); findViewById(R.id.btnDarkTheme).setOnClickListener(v->setThemePreference("dark"));
    }

    private void setupChartControls() {
        ArrayAdapter<String> ca=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,CHART_CATEGORY_LABELS); ca.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); chartCategorySpinner.setAdapter(ca);
        ArrayAdapter<String> co=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,CHART_COUNTRY_LABELS); co.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); chartCountrySpinner.setAdapter(co);
        AdapterView.OnItemSelectedListener l=new AdapterView.OnItemSelectedListener(){@Override public void onItemSelected(AdapterView<?>p,View v,int pos,long id){if(sectionCharts.getVisibility()==View.VISIBLE)loadChartBrowser();}@Override public void onNothingSelected(AdapterView<?>p){}};
        chartCategorySpinner.setOnItemSelectedListener(l); chartCountrySpinner.setOnItemSelectedListener(l);
    }

    private void syncSettingsUi(){suppressSettingCallbacks=true;autoplaySwitch.setChecked(settings.getBoolean("autoplay",true));mobileDownloadSwitch.setChecked(settings.getBoolean(KEY_ALLOW_MOBILE_DOWNLOAD,false));suppressSettingCallbacks=false;}
    private void showMobileDataConfirmation(){AlertDialog d=new AlertDialog.Builder(this).setTitle("모바일 데이터 다운로드 허용").setMessage("LTE/5G에서도 음악 파일을 저장합니다. 여러 곡을 대기열에 넣으면 데이터 사용량이 커질 수 있습니다.").setPositiveButton("허용",(x,w)->{settings.edit().putBoolean(KEY_ALLOW_MOBILE_DOWNLOAD,true).apply();updateReadyStatus();downloadQueue.kick();}).setNegativeButton("취소",(x,w)->setMobileSwitchWithoutCallback(false)).create();d.setOnCancelListener(x->setMobileSwitchWithoutCallback(false));d.show();}
    private void setMobileSwitchWithoutCallback(boolean v){suppressSettingCallbacks=true;mobileDownloadSwitch.setChecked(v);suppressSettingCallbacks=false;if(!v)settings.edit().putBoolean(KEY_ALLOW_MOBILE_DOWNLOAD,false).apply();updateReadyStatus();}

    private void exportBackup(){Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("application/json");i.putExtra(Intent.EXTRA_TITLE,"NoLimitMusic-backup.json");backupCreateLauncher.launch(i);}
    private void importBackup(){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("application/json");backupOpenLauncher.launch(i);}

    private void hideAllSections(){sectionHome.setVisibility(View.GONE);sectionSearch.setVisibility(View.GONE);sectionPlaylist.setVisibility(View.GONE);sectionSettings.setVisibility(View.GONE);sectionCharts.setVisibility(View.GONE);sectionDj.setVisibility(View.GONE);}
    private void switchTab(String tab){hideAllSections();sectionHome.setVisibility("home".equals(tab)?View.VISIBLE:View.GONE);sectionSearch.setVisibility("search".equals(tab)?View.VISIBLE:View.GONE);sectionPlaylist.setVisibility("playlist".equals(tab)?View.VISIBLE:View.GONE);sectionSettings.setVisibility("settings".equals(tab)?View.VISIBLE:View.GONE);setNavActive(iconHome,"home".equals(tab));setNavActive(iconSearch,"search".equals(tab));setNavActive(iconPlaylist,"playlist".equals(tab));setNavActive(iconSettings,"settings".equals(tab));if("playlist".equals(tab)){renderPlaylistFolders();renderActivePlaylist();}}
    private void openCharts(){hideAllSections();sectionCharts.setVisibility(View.VISIBLE);setNavActive(iconHome,true);setNavActive(iconSearch,false);setNavActive(iconPlaylist,false);setNavActive(iconSettings,false);loadChartBrowser();}
    private void openDj(){hideAllSections();sectionDj.setVisibility(View.VISIBLE);setNavActive(iconHome,true);setNavActive(iconSearch,false);setNavActive(iconPlaylist,false);setNavActive(iconSettings,false);}
    private void setNavActive(TextView icon,boolean active){
        icon.setBackgroundResource(active?R.drawable.bg_nav_active:0);
        icon.setAlpha(active?1f:.58f);
        android.graphics.drawable.Drawable[] ds=icon.getCompoundDrawables();
        for(android.graphics.drawable.Drawable d:ds){
            if(d!=null){
                d=d.mutate();
                d.setTint(ContextCompat.getColor(this,active?R.color.accent:R.color.nav_icon));
            }
        }
    }

    private void initializeEngine(){io.execute(()->{try{youtube.init();engineReady=true;runOnUiThread(this::updateReadyStatus);}catch(Exception e){runOnUiThread(()->engineStatus.setText("엔진 준비 실패 · "+compactError(e)));}});}
    private void updateReadyStatus(){if(!engineReady||engineStatus==null)return;boolean mobile=settings.getBoolean(KEY_ALLOW_MOBILE_DOWNLOAD,false);engineStatus.setText(mobile?"음악 엔진 준비됨 · Wi‑Fi/모바일 데이터 · 다중 저장 대기열":"음악 엔진 준비됨 · Wi‑Fi에서만 저장 · 다중 저장 대기열");}

    private void loadHomeCharts(){chartStatus.setText("불러오는 중");io.execute(()->{try{List<SearchResult>list=charts.loadChart(YoutubeChartsRepository.Category.TOP_SONGS,"kr",6);runOnUiThread(()->{chartsAdapter.submit(list);chartStatus.setText(list.isEmpty()?"표시할 차트 없음":"Top "+list.size()+" 미리보기");});}catch(Exception e){runOnUiThread(()->{chartsAdapter.submit(Collections.emptyList());chartStatus.setText("차트 오류 · "+compactError(e));});}});}
    private void loadChartBrowser(){if(chartLoading)return;int ci=Math.max(0,chartCategorySpinner.getSelectedItemPosition()),co=Math.max(0,chartCountrySpinner.getSelectedItemPosition());if(ci>=CHART_CATEGORIES.length)ci=0;if(co>=CHART_COUNTRY_CODES.length)co=0;YoutubeChartsRepository.Category cat=CHART_CATEGORIES[ci];String country=CHART_COUNTRY_CODES[co],label=CHART_COUNTRY_LABELS[co]+" · "+CHART_CATEGORY_LABELS[ci];int limit=cat==YoutubeChartsRepository.Category.TOP_ARTISTS?100:50;chartLoading=true;chartBrowserStatus.setText(label+" 불러오는 중…");io.execute(()->{try{List<SearchResult>list=charts.loadChart(cat,country,limit);runOnUiThread(()->{chartLoading=false;chartBrowserAdapter.submit(list);chartBrowserStatus.setText(label+" · "+list.size()+"개");});}catch(Exception e){runOnUiThread(()->{chartLoading=false;chartBrowserAdapter.submit(Collections.emptyList());chartBrowserStatus.setText("차트 오류 · "+compactError(e));});}});}
    private void onChartItem(SearchResult item,int pos){if(item.id.startsWith("artist:")){openSearchFor(item.title);return;}download(item,pos);}
    private void openSearchFor(String q){switchTab("search");searchInput.setText(q);searchInput.setSelection(searchInput.getText().length());runSearch();}

    private void runDj(){String prompt=djPrompt.getText().toString().trim();if(TextUtils.isEmpty(prompt)){djPrompt.setError("예: 새벽에 들을 잔잔한 한국 노래");return;}if(!engineReady){toast("음악 엔진을 준비하는 중입니다.");return;}if(djLoading)return;djLoading=true;djGenerate.setEnabled(false);djStatus.setText("DJ가 취향과 요청을 분석하는 중…");djAdapter.submit(Collections.emptyList());io.execute(()->{try{List<Track>local=library.load();List<String>queries=DjPlanner.buildQueries(prompt,local);List<List<SearchResult>>batches=new ArrayList<>();for(int i=0;i<queries.size();i++){String q=queries.get(i);int step=i+1;runOnUiThread(()->djStatus.setText("DJ 검색 "+step+"/"+queries.size()+" · "+q));try{batches.add(youtube.search(q));}catch(Exception x){batches.add(Collections.emptyList());}}List<SearchResult>merged=DjPlanner.merge(batches,local,30);runOnUiThread(()->{djLoading=false;djGenerate.setEnabled(true);djAdapter.submit(merged);djStatus.setText(merged.isEmpty()?"추천 후보를 찾지 못했습니다.":"AI DJ · "+merged.size()+"곡 후보 · 취향 반영");});}catch(Exception e){runOnUiThread(()->{djLoading=false;djGenerate.setEnabled(true);djStatus.setText("DJ 실패 · "+compactError(e));});}});}

    private void runSearch(){String q=searchInput.getText().toString().trim();if(TextUtils.isEmpty(q)){searchInput.setError("검색어를 입력하세요.");return;}if(!engineReady){toast("음악 엔진을 준비하는 중입니다.");return;}searchProgress.setVisibility(View.VISIBLE);searchButton.setEnabled(false);engineStatus.setText("검색 중… 음악 필터를 적용합니다.");io.execute(()->{try{List<SearchResult>list=youtube.search(q);runOnUiThread(()->{resultsAdapter.submit(list);searchProgress.setVisibility(View.GONE);searchButton.setEnabled(true);engineStatus.setText(list.isEmpty()?"검색 결과가 없습니다.":"검색 완료 · "+list.size()+"곡");});}catch(Exception e){runOnUiThread(()->{searchProgress.setVisibility(View.GONE);searchButton.setEnabled(true);engineStatus.setText("검색 실패 · "+compactError(e));});}});}

    private void download(SearchResult item,int position){
        if(item.id.startsWith("artist:")){openSearchFor(item.title);return;}
        Track existing=library.find(item.id);if(existing!=null&&TrackStorage.exists(this,existing.path)){playlists.addTrack(PlaylistStore.DEFAULT_ID,existing.id);refreshAll();engineStatus.setText("이미 저장된 곡 · 재생합니다.");playDownloaded(existing);return;}
        boolean allowMobile=settings.getBoolean(KEY_ALLOW_MOBILE_DOWNLOAD,false);if(!NetworkUtil.canDownload(this,allowMobile)){new AlertDialog.Builder(this).setTitle(allowMobile?"인터넷 연결이 필요합니다":"Wi‑Fi가 필요합니다").setMessage(allowMobile?"현재 인터넷에 연결되어 있지 않습니다.":"Wi‑Fi 전용 저장이 켜져 있습니다.").setPositiveButton("확인",null).show();return;}
        boolean added=downloadQueue.enqueue(item);if(added){setAllDownloadProgress(item.id,0);engineStatus.setText("대기열에 추가 · "+item.title);}else toast("이미 대기열에 있거나 저장된 곡입니다.");
    }

    private int enqueueMissingTracks(List<Track> tracks){int n=0;for(Track t:tracks){if(TrackStorage.exists(this,t.path))continue;String thumb=t.thumbnailUrl==null||t.thumbnailUrl.isEmpty()?ArtworkLoader.fallbackUrl(t.id):t.thumbnailUrl;SearchResult r=new SearchResult(t.id,t.title,t.artist,"https://www.youtube.com/watch?v="+t.id,t.durationSeconds,thumb,0,"복원",t.album);if(downloadQueue.enqueue(r))n++;}return n;}
    private void setAllDownloadProgress(String id,int p){resultsAdapter.setProgress(id,p);chartsAdapter.setProgress(id,p);chartBrowserAdapter.setProgress(id,p);djAdapter.setProgress(id,p);}
    private void clearDownloadProgress(String id){resultsAdapter.clearProgress(id);chartsAdapter.clearProgress(id);chartBrowserAdapter.clearProgress(id);djAdapter.clearProgress(id);}

    private void playDownloaded(Track t){if(settings.getBoolean("autoplay",true))playQueue(playlists.tracks(PlaylistStore.DEFAULT_ID),t.id);else playQueue(Collections.singletonList(t),t.id);}
    private void playFromActiveList(Track t){if(!TrackStorage.exists(this,t.path)){String thumb=t.thumbnailUrl==null||t.thumbnailUrl.isEmpty()?ArtworkLoader.fallbackUrl(t.id):t.thumbnailUrl;downloadQueue.enqueue(new SearchResult(t.id,t.title,t.artist,"https://www.youtube.com/watch?v="+t.id,t.durationSeconds,thumb,0,"복원",t.album));toast("공용 보존본이 없어서 다운로드 대기열에 추가했습니다.");return;}if(settings.getBoolean("autoplay",true))playQueue(getActiveTracks(),t.id);else playQueue(Collections.singletonList(t),t.id);}

    private MediaItem toMediaItem(Track t){MediaMetadata.Builder md=new MediaMetadata.Builder().setTitle(t.title).setArtist(t.artist).setAlbumTitle(t.album);Uri art=ArtworkLoader.bestArtworkUri(this,t.id,t.thumbnailUrl);if(art!=null)md.setArtworkUri(art);return new MediaItem.Builder().setMediaId(t.id).setUri(TrackStorage.uri(t.path)).setMediaMetadata(md.build()).build();}
    private void playQueue(List<Track> source,String startId){if(controller==null){toast("플레이어를 연결하는 중입니다.");return;}List<MediaItem>items=new ArrayList<>();int start=0;for(Track t:source){if(!TrackStorage.exists(this,t.path))continue;if(startId!=null&&startId.equals(t.id))start=items.size();items.add(toMediaItem(t));}if(items.isEmpty()){toast("재생할 저장 곡이 없습니다.");return;}controller.setMediaItems(items,Math.min(start,items.size()-1),0L);controller.prepare();controller.play();syncPlayerUi();}
    private void addToQueue(Track t,boolean next){if(controller==null||!TrackStorage.exists(this,t.path)){toast("먼저 곡을 저장해 주세요.");return;}MediaItem item=toMediaItem(t);if(next){int pos=Math.max(0,controller.getCurrentMediaItemIndex()+1);controller.addMediaItem(Math.min(pos,controller.getMediaItemCount()),item);toast("다음 곡으로 추가했습니다.");}else{controller.addMediaItem(item);toast("대기열 마지막에 추가했습니다.");}}

    private void syncPlayerUi(){if(controller==null||controller.getMediaItemCount()==0){playerBar.setVisibility(View.GONE);playerSeek.setProgress(0);currentTime.setText("0:00");totalTime.setText("0:00");nowArtwork.setImageResource(R.drawable.ic_music_note);return;}playerBar.setVisibility(View.VISIBLE);MediaMetadata m=controller.getMediaMetadata();nowPlaying.setText(m.title==null?"재생 중":m.title);nowArtist.setText(m.artist==null?"":m.artist);playPause.setText(controller.isPlaying()?"Ⅱ":"▶");MediaItem item=controller.getCurrentMediaItem();Track t=item==null?null:library.find(item.mediaId);if(t!=null)ArtworkLoader.load(nowArtwork,this,t.id,t.thumbnailUrl);else nowArtwork.setImageResource(R.drawable.ic_music_note);updatePlayerProgress();}
    private long effectiveDurationMs(){if(controller==null)return 0L;long d=controller.getDuration();if(d!=C.TIME_UNSET&&d>0)return d;MediaItem i=controller.getCurrentMediaItem();if(i!=null){Track t=library.find(i.mediaId);if(t!=null&&t.durationSeconds>0)return t.durationSeconds*1000L;}return 0L;}
    private void updatePlayerProgress(){if(controller==null||playerBar==null||playerBar.getVisibility()!=View.VISIBLE)return;long d=effectiveDurationMs(),p=Math.max(0L,controller.getCurrentPosition());totalTime.setText(formatTime(d));if(!userSeeking){currentTime.setText(formatTime(p));playerSeek.setProgress(d>0?(int)Math.min(1000L,p*1000L/d):0);}}
    private static String formatTime(long ms){if(ms<=0)return"0:00";long s=ms/1000L,h=s/3600L,m=(s%3600L)/60L,sec=s%60L;return h>0?String.format(Locale.ROOT,"%d:%02d:%02d",h,m,sec):String.format(Locale.ROOT,"%d:%02d",m,sec);}

    private void refreshAll(){refreshDashboard();renderPlaylistFolders();renderActivePlaylist();}
    private void refreshDashboard(){recentCount.setText(library.recent(Integer.MAX_VALUE).size()+"곡");likedCount.setText(library.liked().size()+"곡");mostPlayedCount.setText(library.mostPlayed(Integer.MAX_VALUE).size()+"곡");}
    private List<Track> getActiveTracks(){if(SMART_RECENT.equals(activeSmart))return library.recent(Integer.MAX_VALUE);if(SMART_LIKED.equals(activeSmart))return library.liked();if(SMART_MOST.equals(activeSmart))return library.mostPlayed(Integer.MAX_VALUE);return playlists.tracks(activePlaylistId);}
    private void openSmartPlaylist(String type){activeSmart=type;switchTab("playlist");renderActivePlaylist();}
    private void openCustomPlaylist(String id){activeSmart=null;activePlaylistId=id;switchTab("playlist");renderPlaylistFolders();renderActivePlaylist();}
    private void renderActivePlaylist(){if(playlistAdapter==null)return;List<Track>tracks=getActiveTracks();playlistAdapter.submit(tracks);playlistCount.setText(tracks.size()+"곡"+(activeSmart==null?" · 길게 눌러 순서 변경":" · 자동 스마트 플리"));if(SMART_RECENT.equals(activeSmart))playlistTitle.setText("최근 추가한 곡");else if(SMART_LIKED.equals(activeSmart))playlistTitle.setText("좋아요한 곡");else if(SMART_MOST.equals(activeSmart))playlistTitle.setText("많이 재생한 곡");else{Playlist p=playlists.get(activePlaylistId);playlistTitle.setText(p==null?"플레이리스트":p.name);}}
    private void renderPlaylistFolders(){if(playlistFolders==null)return;playlistFolders.removeAllViews();for(Playlist p:playlists.load()){TextView chip=new TextView(this);chip.setText("♫  "+p.name);chip.setGravity(Gravity.CENTER);chip.setTextColor(ContextCompat.getColor(this,R.color.text_primary));chip.setTextSize(12f);chip.setBackgroundResource(R.drawable.bg_smart_card);chip.setPadding(dp(15),0,dp(15),0);chip.setAlpha(activeSmart==null&&p.id.equals(activePlaylistId)?1f:.62f);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,dp(42));lp.setMarginEnd(dp(8));chip.setLayoutParams(lp);chip.setOnClickListener(v->openCustomPlaylist(p.id));chip.setOnLongClickListener(v->{showPlaylistFolderMenu(p);return true;});playlistFolders.addView(chip);}}

    private void showCreatePlaylistDialog(){EditText in=new EditText(this);in.setHint("플레이리스트 이름");new AlertDialog.Builder(this).setTitle("새 플레이리스트").setView(in).setNegativeButton("취소",null).setPositiveButton("만들기",(d,w)->{Playlist p=playlists.create(in.getText().toString());openCustomPlaylist(p.id);}).show();}
    private void showPlaylistFolderMenu(Playlist p){if(PlaylistStore.DEFAULT_ID.equals(p.id)){toast("기본 플레이리스트는 삭제할 수 없습니다.");return;}new AlertDialog.Builder(this).setTitle(p.name).setItems(new String[]{"이름 바꾸기","플레이리스트 삭제"},(d,w)->{if(w==0)showRenamePlaylistDialog(p);else{playlists.delete(p.id);activePlaylistId=PlaylistStore.DEFAULT_ID;activeSmart=null;refreshAll();}}).show();}
    private void showRenamePlaylistDialog(Playlist p){EditText in=new EditText(this);in.setText(p.name);new AlertDialog.Builder(this).setTitle("이름 바꾸기").setView(in).setNegativeButton("취소",null).setPositiveButton("저장",(d,w)->{playlists.rename(p.id,in.getText().toString());refreshAll();}).show();}

    private void showTrackOptions(Track t){List<String> opts=new ArrayList<>();opts.add("다음에 재생");opts.add("대기열 마지막에 추가");opts.add("다른 플레이리스트에 추가");if(activeSmart==null)opts.add("현재 플레이리스트에서 제거");opts.add("저장 파일 삭제");new AlertDialog.Builder(this).setTitle(t.title).setItems(opts.toArray(new String[0]),(d,w)->{String s=opts.get(w);if(s.startsWith("다음"))addToQueue(t,true);else if(s.startsWith("대기열"))addToQueue(t,false);else if(s.startsWith("다른"))showAddToPlaylistDialog(t);else if(s.startsWith("현재")){playlists.removeTrack(activePlaylistId,t.id);refreshAll();}else confirmDeleteTrack(t);}).show();}
    private void showAddToPlaylistDialog(Track t){List<Playlist>all=playlists.load();String[]names=new String[all.size()];for(int i=0;i<all.size();i++)names[i]=all.get(i).name;new AlertDialog.Builder(this).setTitle("플레이리스트에 추가").setItems(names,(d,w)->{playlists.addTrack(all.get(w).id,t.id);toast(all.get(w).name+"에 추가했습니다.");refreshAll();}).show();}
    private void confirmDeleteTrack(Track t){new AlertDialog.Builder(this).setTitle("저장 파일 삭제").setMessage("공용 Music 보존본까지 삭제하고 모든 플레이리스트에서도 제거할까요?").setNegativeButton("취소",null).setPositiveButton("삭제",(d,w)->{if(controller!=null&&t.id.equals(controller.getCurrentMediaItem()==null?"":controller.getCurrentMediaItem().mediaId)){controller.stop();controller.clearMediaItems();}playlists.removeTrackEverywhere(t.id);library.remove(t.id);File art=ArtworkLoader.localArtworkFile(this,t.id);if(art.exists())art.delete();refreshAll();syncPlayerUi();}).show();}

    private void setThemePreference(String theme){settings.edit().putString("theme",theme).apply();applyThemeMode(theme);}
    private static void applyThemeMode(String theme){AppCompatDelegate.setDefaultNightMode("light".equals(theme)?AppCompatDelegate.MODE_NIGHT_NO:AppCompatDelegate.MODE_NIGHT_YES);}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private void showFirstRunNotice(){if(getPreferences(MODE_PRIVATE).getBoolean("notice_seen",false))return;new AlertDialog.Builder(this).setTitle("테스트판 안내").setMessage("다운로드 기능은 본인이 권리를 보유하거나 다운로드 허가를 받은 콘텐츠에만 사용하세요. 이 앱은 DRM 우회 기능을 포함하지 않습니다.").setPositiveButton("확인",(d,w)->getPreferences(MODE_PRIVATE).edit().putBoolean("notice_seen",true).apply()).show();}
    private String compactError(Exception e){String m=e.getMessage();if(m==null||m.trim().isEmpty())return e.getClass().getSimpleName();m=m.replace('\n',' ');return m.length()>140?m.substring(0,140)+"…":m;}
    private void toast(String t){Toast.makeText(this,t,Toast.LENGTH_SHORT).show();}

    @Override protected void onResume(){super.onResume();if(library!=null)refreshAll();syncPlayerUi();if(downloadQueue!=null)downloadQueue.kick();}
    @Override protected void onDestroy(){mainHandler.removeCallbacks(positionTicker);if(downloadQueue!=null)downloadQueue.removeListener(downloadListener);if(controllerFuture!=null)MediaController.releaseFuture(controllerFuture);controller=null;io.shutdownNow();super.onDestroy();}
}
