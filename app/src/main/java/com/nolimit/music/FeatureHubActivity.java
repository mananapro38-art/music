package com.nolimit.music;

import android.content.ComponentName;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;

import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.common.util.concurrent.ListenableFuture;
import com.nolimit.music.ads.StartioBannerFactory;
import com.nolimit.music.data.AutoBackupManager;
import com.nolimit.music.data.DownloadTaskStore;
import com.nolimit.music.data.HistoryStore;
import com.nolimit.music.data.LibraryStore;
import com.nolimit.music.data.SmartPlaylistStore;
import com.nolimit.music.data.TrackStorage;
import com.nolimit.music.model.Track;
import com.nolimit.music.playback.PlaybackService;
import com.nolimit.music.util.ArtworkLoader;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class FeatureHubActivity extends AppCompatActivity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private LibraryStore library;
    private DownloadTaskStore downloads;
    private HistoryStore history;
    private SmartPlaylistStore smartPlaylists;
    private LinearLayout content;
    private ListenableFuture<MediaController> controllerFuture;
    private MediaController controller;

    @Override protected void onCreate(Bundle savedInstanceState) {
        SharedPreferences prefs = getSharedPreferences("settings", MODE_PRIVATE);
        AppCompatDelegate.setDefaultNightMode("light".equals(prefs.getString("theme", "dark")) ? AppCompatDelegate.MODE_NIGHT_NO : AppCompatDelegate.MODE_NIGHT_YES);
        super.onCreate(savedInstanceState);
        library = new LibraryStore(this);
        downloads = new DownloadTaskStore(this);
        history = new HistoryStore(this);
        smartPlaylists = new SmartPlaylistStore(this, library);
        buildUi();
        connectPlayer();
        renderLibrary();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(16), dp(18), dp(12));
        root.setBackgroundColor(ContextCompat.getColor(this, R.color.app_bg));

        LinearLayout header = row();
        TextView back = text("‹", 32, true); back.setGravity(Gravity.CENTER); back.setOnClickListener(v -> finish());
        header.addView(back, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout titles = new LinearLayout(this); titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(text("라이브러리+", 25, true)); titles.addView(text("내 음악을 정리하고 자동화합니다", 11, false));
        header.addView(titles, new LinearLayout.LayoutParams(0, dp(52), 1f));
        root.addView(header);

        HorizontalScrollView scroll = new HorizontalScrollView(this); scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout tabs = row();
        addTab(tabs, "라이브러리", this::renderLibrary); addTab(tabs, "다운로드", this::renderDownloads);
        addTab(tabs, "리캡", this::renderHistory); addTab(tabs, "스마트 플리", this::renderSmartRules);
        scroll.addView(tabs); LinearLayout.LayoutParams tabsLp = new LinearLayout.LayoutParams(-1, dp(48)); tabsLp.topMargin = dp(8); root.addView(scroll, tabsLp);
        StartioBannerFactory.append(this, root, "library_hub");

        ScrollView body = new ScrollView(this); body.setFillViewport(true);
        content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(0, dp(8), 0, dp(42));
        body.addView(content); root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1f)); setContentView(root);
    }

    private void connectPlayer() {
        SessionToken token = new SessionToken(this, new ComponentName(this, PlaybackService.class));
        controllerFuture = new MediaController.Builder(this, token).buildAsync();
        controllerFuture.addListener(() -> { try { controller = controllerFuture.get(); } catch (Exception ignored) { } }, ContextCompat.getMainExecutor(this));
    }

    private void renderLibrary() {
        content.removeAllViews();
        List<Track> tracks = library.load();
        content.addView(sectionTitle("내 라이브러리 · " + tracks.size() + "곡"));
        content.addView(text("v1.0 이전 곡도 공용 Music 폴더에 보존본을 만들 수 있습니다.", 11, false));

        LinearLayout actions = row();
        actions.addView(button("기존 곡 공용 보존", v -> migrateShared()), new LinearLayout.LayoutParams(0, dp(50), 1f));
        actions.addView(button("공용 폴더 복구", v -> scanShared()), new LinearLayout.LayoutParams(0, dp(50), 1f));
        content.addView(actions);
        Button backup = button("지금 자동 백업 만들기", v -> autoBackup());
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, dp(48)); bp.topMargin = dp(8); content.addView(backup, bp);

        content.addView(label("곡", 16, true));
        for (Track track : tracks) content.addView(trackRow(track, tracks));
        renderGroups("아티스트", group(tracks, "artist"));
        renderGroups("앨범", group(tracks, "album"));
        renderGroups("자동 태그", group(tracks, "tag"));
    }

    private void migrateShared() {
        io.execute(() -> {
            int count = library.migrateAllToShared();
            runOnUiThread(() -> toast(count > 0 ? count + "곡을 Music/No Limit Music에 보존했습니다." : "보존할 기존 파일이 없습니다."));
        });
    }

    private void scanShared() {
        io.execute(() -> {
            int added = library.importSharedMusic();
            runOnUiThread(() -> { renderLibrary(); toast(added > 0 ? "공용 폴더에서 " + added + "곡을 복구했습니다." : "새로 복구할 곡이 없습니다."); });
        });
    }

    private void autoBackup() {
        io.execute(() -> {
            try {
                Uri uri = new AutoBackupManager(this).backupNow();
                runOnUiThread(() -> toast(uri == null ? "이 Android 버전에서는 수동 백업을 사용해 주세요." : "Downloads/No Limit Music에 백업했습니다."));
            } catch (Exception e) { runOnUiThread(() -> toast("자동 백업 실패 · " + e.getMessage())); }
        });
    }

    private void renderGroups(String title, Map<String, List<Track>> groups) {
        TextView heading = label(title, 17, true); LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, -2); hp.topMargin = dp(24); content.addView(heading, hp);
        for (Map.Entry<String, List<Track>> entry : groups.entrySet()) {
            if (entry.getKey().trim().isEmpty()) continue;
            TextView item = card(entry.getKey() + "  ·  " + entry.getValue().size() + "곡");
            item.setOnClickListener(v -> showGroup(entry.getKey(), entry.getValue())); content.addView(item);
        }
    }

    private void showGroup(String name, List<Track> tracks) {
        content.removeAllViews(); TextView back = card("‹  라이브러리로"); back.setOnClickListener(v -> renderLibrary()); content.addView(back);
        content.addView(sectionTitle(name + " · " + tracks.size() + "곡")); for (Track track : tracks) content.addView(trackRow(track, tracks));
    }

    private View trackRow(Track track, List<Track> queue) {
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(14), dp(11), dp(14), dp(11)); box.setBackgroundResource(R.drawable.bg_smart_card);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(6); box.setLayoutParams(lp);
        box.addView(text(track.title, 14, true));
        box.addView(text(track.artist + " · " + (track.album.isEmpty() ? "싱글/기타" : track.album) + " · " + track.playCount + "회", 11, false));
        box.addView(text(track.tags == null ? "" : track.tags.replace(",", " · "), 10, false));
        box.setOnClickListener(v -> play(queue, track.id)); return box;
    }

    private void play(List<Track> source, String startId) {
        if (controller == null) return;
        List<MediaItem> items = new ArrayList<>(); int start = 0;
        for (Track t : source) {
            if (!TrackStorage.exists(this, t.path)) continue;
            if (t.id.equals(startId)) start = items.size();
            MediaMetadata.Builder md = new MediaMetadata.Builder().setTitle(t.title).setArtist(t.artist).setAlbumTitle(t.album);
            Uri art = ArtworkLoader.bestArtworkUri(this, t.id, t.thumbnailUrl); if (art != null) md.setArtworkUri(art);
            items.add(new MediaItem.Builder().setMediaId(t.id).setUri(TrackStorage.uri(t.path)).setMediaMetadata(md.build()).build());
        }
        if (items.isEmpty()) return;
        controller.setMediaItems(items, Math.min(start, items.size() - 1), 0L); controller.prepare(); controller.play();
    }

    private void renderDownloads() {
        content.removeAllViews(); content.addView(sectionTitle("다운로드 관리자"));
        content.addView(text("여러 곡을 누르면 순서대로 대기열에 들어가고, 실패 항목은 다시 시도할 수 있습니다.", 11, false));
        Button clear = button("완료 항목 정리", v -> { downloads.clearFinished(); renderDownloads(); }); content.addView(clear);
        List<DownloadTaskStore.Task> tasks = downloads.load();
        if (tasks.isEmpty()) content.addView(card("아직 다운로드 작업이 없습니다."));
        for (DownloadTaskStore.Task task : tasks) {
            LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(14), dp(11), dp(14), dp(11)); box.setBackgroundResource(R.drawable.bg_smart_card);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(7); box.setLayoutParams(lp);
            box.addView(text(task.item.title, 14, true));
            String state = stateLabel(task.state) + ("running".equals(task.state) ? " · " + task.progress + "%" : "");
            if (!task.error.isEmpty()) state += " · " + task.error;
            box.addView(text(state, 11, false));
            LinearLayout buttons = row();
            if ("failed".equals(task.state)) buttons.addView(button("재시도", v -> { downloads.retry(task.item.id); renderDownloads(); }));
            if (!"running".equals(task.state)) buttons.addView(button("제거", v -> { downloads.remove(task.item.id); renderDownloads(); }));
            box.addView(buttons); content.addView(box);
        }
    }

    private void renderHistory() {
        content.removeAllViews(); List<HistoryStore.Entry> entries = history.load();
        content.addView(sectionTitle("재생 리캡"));
        content.addView(card("누적 청취 시간  " + formatDuration(history.totalListeningMs())));
        addRecapBlock("최근 7일", entries, 7); addRecapBlock("최근 30일", entries, 30);
        content.addView(label("최근 재생", 17, true));
        SimpleDateFormat df = new SimpleDateFormat("M/d HH:mm", Locale.KOREA);
        for (int i = 0; i < Math.min(80, entries.size()); i++) {
            HistoryStore.Entry e = entries.get(i); content.addView(card(e.title + "\n" + e.artist + " · " + df.format(new Date(e.playedAt))));
        }
        content.addView(button("재생 기록 지우기", v -> { history.clear(); renderHistory(); }));
    }

    private void addRecapBlock(String label, List<HistoryStore.Entry> entries, int days) {
        long cutoff = System.currentTimeMillis() - days * 86400000L; long listen = 0L; int plays = 0;
        Map<String, Integer> artists = new LinkedHashMap<>(); Map<String, Integer> songs = new LinkedHashMap<>();
        for (HistoryStore.Entry e : entries) {
            if (e.playedAt < cutoff) continue; plays++; listen += Math.max(0, e.listenedMs);
            artists.put(e.artist, artists.getOrDefault(e.artist, 0) + 1); songs.put(e.title, songs.getOrDefault(e.title, 0) + 1);
        }
        content.addView(label(label, 17, true));
        content.addView(card(plays + "회 재생 · " + formatDuration(listen) + " 청취"));
        String topArtist = maxKey(artists); String topSong = maxKey(songs);
        if (!topArtist.isEmpty()) content.addView(card("많이 들은 아티스트  ·  " + topArtist));
        if (!topSong.isEmpty()) content.addView(card("많이 들은 곡  ·  " + topSong));
    }

    private void renderSmartRules() {
        content.removeAllViews(); content.addView(sectionTitle("동적 스마트 플레이리스트"));
        content.addView(text("조건을 저장하면 라이브러리가 바뀔 때마다 결과도 자동으로 다시 계산됩니다.", 11, false));
        List<SmartPlaylistStore.Rule> rules = smartPlaylists.load();
        for (SmartPlaylistStore.Rule rule : rules) {
            List<Track> matches = smartPlaylists.evaluate(rule);
            TextView item = card(rule.name + "  ·  " + matches.size() + "곡\n" + describeRule(rule));
            item.setOnClickListener(v -> showSmartRule(rule));
            item.setOnLongClickListener(v -> { new androidx.appcompat.app.AlertDialog.Builder(this).setTitle(rule.name).setMessage("이 스마트 플레이리스트 조건을 삭제할까요?").setNegativeButton("취소", null).setPositiveButton("삭제", (d,w)->{ smartPlaylists.delete(rule.id); renderSmartRules(); }).show(); return true; });
            content.addView(item);
        }
        content.addView(label("새 조건 만들기", 17, true));
        MaterialSwitch liked = new MaterialSwitch(this); liked.setText("좋아요한 곡만"); liked.setTextColor(ContextCompat.getColor(this, R.color.text_primary)); content.addView(liked);
        EditText minPlays = input("최소 재생 횟수 (예: 5)", true); EditText artist = input("아티스트 이름 포함", false); EditText tag = input("태그 포함 (예: 드라이브)", false);
        EditText days = input("최근 N일 동안 안 들은 곡 (예: 14)", true); EditText name = input("스마트 플레이리스트 이름", false);
        content.addView(minPlays); content.addView(artist); content.addView(tag); content.addView(days); content.addView(name);
        LinearLayout actions = row();
        actions.addView(button("미리보기", v -> previewRule(liked, minPlays, artist, tag, days)), new LinearLayout.LayoutParams(0, dp(48), 1f));
        actions.addView(button("조건 저장", v -> saveRule(liked, minPlays, artist, tag, days, name)), new LinearLayout.LayoutParams(0, dp(48), 1f));
        content.addView(actions);
    }

    private void previewRule(MaterialSwitch liked, EditText minPlays, EditText artist, EditText tag, EditText days) {
        SmartPlaylistStore.Rule temp = new SmartPlaylistStore.Rule("preview", "미리보기", liked.isChecked(), intValue(minPlays, 0), artist.getText().toString(), tag.getText().toString(), intValue(days, 0));
        showRuleResults(temp, smartPlaylists.evaluate(temp), false);
    }

    private void saveRule(MaterialSwitch liked, EditText minPlays, EditText artist, EditText tag, EditText days, EditText name) {
        String n = name.getText().toString().trim(); if (n.isEmpty()) n = "스마트 플리 " + new SimpleDateFormat("M-d", Locale.KOREA).format(new Date());
        smartPlaylists.save(n, liked.isChecked(), intValue(minPlays, 0), artist.getText().toString(), tag.getText().toString(), intValue(days, 0));
        toast("동적 스마트 플레이리스트를 저장했습니다."); renderSmartRules();
    }

    private void showSmartRule(SmartPlaylistStore.Rule rule) { showRuleResults(rule, smartPlaylists.evaluate(rule), true); }

    private void showRuleResults(SmartPlaylistStore.Rule rule, List<Track> tracks, boolean saved) {
        content.removeAllViews(); TextView back = card("‹  스마트 플리로"); back.setOnClickListener(v -> renderSmartRules()); content.addView(back);
        content.addView(sectionTitle(rule.name + " · " + tracks.size() + "곡")); content.addView(text(describeRule(rule), 11, false));
        if (!tracks.isEmpty()) content.addView(button("전체 재생", v -> play(tracks, tracks.get(0).id)));
        for (Track t : tracks) content.addView(trackRow(t, tracks));
    }

    private String describeRule(SmartPlaylistStore.Rule r) {
        List<String> bits = new ArrayList<>(); if (r.likedOnly) bits.add("좋아요"); if (r.minPlays > 0) bits.add(r.minPlays + "회 이상");
        if (!r.artistContains.isEmpty()) bits.add("아티스트: " + r.artistContains); if (!r.tagContains.isEmpty()) bits.add("태그: " + r.tagContains); if (r.notPlayedDays > 0) bits.add(r.notPlayedDays + "일 미재생");
        return bits.isEmpty() ? "모든 곡" : android.text.TextUtils.join(" · ", bits);
    }

    private Map<String, List<Track>> group(List<Track> tracks, String type) {
        Map<String, List<Track>> out = new LinkedHashMap<>();
        for (Track t : tracks) {
            if ("tag".equals(type)) {
                for (String raw : (t.tags == null ? "" : t.tags).split(",")) { String key = raw.trim(); if (!key.isEmpty()) out.computeIfAbsent(key, k -> new ArrayList<>()).add(t); }
            } else {
                String key = "album".equals(type) ? t.album : t.artist; if (key == null || key.trim().isEmpty()) key = "기타"; out.computeIfAbsent(key, k -> new ArrayList<>()).add(t);
            }
        }
        return out;
    }

    private String maxKey(Map<String, Integer> values) { String best = ""; int max = 0; for (Map.Entry<String,Integer> e : values.entrySet()) if (e.getValue() > max && e.getKey() != null && !e.getKey().trim().isEmpty()) { max = e.getValue(); best = e.getKey(); } return best; }
    private String stateLabel(String state) { if ("pending".equals(state)) return "대기 중"; if ("running".equals(state)) return "다운로드 중"; if ("done".equals(state)) return "완료"; if ("failed".equals(state)) return "실패"; return state; }
    private String formatDuration(long ms) { long total = Math.max(0, ms) / 1000L; long h = total / 3600L; long m = (total % 3600L) / 60L; return h > 0 ? h + "시간 " + m + "분" : Math.max(1, m) + "분"; }
    private int intValue(EditText e, int fallback) { try { String s = e.getText().toString().trim(); return s.isEmpty() ? fallback : Integer.parseInt(s); } catch (Exception ex) { return fallback; } }

    private void addTab(LinearLayout tabs, String title, Runnable action) { TextView t = card(title); t.setGravity(Gravity.CENTER); t.setOnClickListener(v -> action.run()); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(40)); lp.setMarginEnd(dp(7)); tabs.addView(t, lp); }
    private LinearLayout row() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.HORIZONTAL); v.setGravity(Gravity.CENTER_VERTICAL); return v; }
    private TextView sectionTitle(String s) { TextView t = text(s, 21, true); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1,-2); lp.topMargin = dp(10); lp.bottomMargin = dp(8); t.setLayoutParams(lp); return t; }
    private TextView card(String s) { TextView v = text(s, 13, true); v.setGravity(Gravity.CENTER_VERTICAL); v.setPadding(dp(14), dp(11), dp(14), dp(11)); v.setBackgroundResource(R.drawable.bg_smart_card); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1,-2); lp.topMargin = dp(7); v.setLayoutParams(lp); return v; }
    private TextView label(String s, int sp, boolean bold) { TextView v = text(s, sp, bold); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1,-2); lp.topMargin = dp(18); lp.bottomMargin = dp(5); v.setLayoutParams(lp); return v; }
    private TextView text(String s, int sp, boolean bold) { TextView v = new TextView(this); v.setText(s); v.setTextSize(sp); v.setTextColor(ContextCompat.getColor(this, bold ? R.color.text_primary : R.color.muted)); if (bold) v.setTypeface(v.getTypeface(), Typeface.BOLD); return v; }
    private Button button(String s, View.OnClickListener l) { Button b = new Button(this); b.setText(s); b.setAllCaps(false); b.setOnClickListener(l); return b; }
    private EditText input(String hint, boolean numeric) { EditText e = new EditText(this); e.setHint(hint); e.setTextColor(ContextCompat.getColor(this, R.color.text_primary)); e.setHintTextColor(ContextCompat.getColor(this, R.color.muted)); e.setBackgroundResource(R.drawable.bg_search); e.setPadding(dp(14),0,dp(14),0); if (numeric) e.setInputType(InputType.TYPE_CLASS_NUMBER); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1,dp(50)); lp.topMargin=dp(7); e.setLayoutParams(lp); return e; }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private void toast(String s) { android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show(); }

    @Override protected void onDestroy() { if (controllerFuture != null) MediaController.releaseFuture(controllerFuture); controller = null; io.shutdownNow(); super.onDestroy(); }
}
