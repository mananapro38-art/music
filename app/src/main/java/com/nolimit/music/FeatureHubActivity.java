package com.nolimit.music;

import android.content.ComponentName;
import android.content.Intent;
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
import com.nolimit.music.data.AutoBackupManager;
import com.nolimit.music.data.DownloadTaskStore;
import com.nolimit.music.data.HistoryStore;
import com.nolimit.music.data.LibraryStore;
import com.nolimit.music.data.PlaylistStore;
import com.nolimit.music.data.TrackStorage;
import com.nolimit.music.model.Playlist;
import com.nolimit.music.model.Track;
import com.nolimit.music.playback.PlaybackService;
import com.nolimit.music.util.ArtworkLoader;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class FeatureHubActivity extends AppCompatActivity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private LibraryStore library;
    private PlaylistStore playlists;
    private DownloadTaskStore downloads;
    private HistoryStore history;
    private LinearLayout content;
    private ListenableFuture<MediaController> controllerFuture;
    private MediaController controller;

    @Override protected void onCreate(Bundle savedInstanceState) {
        SharedPreferences prefs = getSharedPreferences("settings", MODE_PRIVATE);
        AppCompatDelegate.setDefaultNightMode("light".equals(prefs.getString("theme", "dark"))
                ? AppCompatDelegate.MODE_NIGHT_NO : AppCompatDelegate.MODE_NIGHT_YES);
        super.onCreate(savedInstanceState);
        library = new LibraryStore(this);
        playlists = new PlaylistStore(this, library);
        downloads = new DownloadTaskStore(this);
        history = new HistoryStore(this);
        buildUi();
        connectPlayer();
        renderLibrary();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(18));
        root.setBackgroundColor(ContextCompat.getColor(this, R.color.app_bg));

        LinearLayout header = row();
        TextView back = text("‹", 32, true);
        back.setGravity(Gravity.CENTER);
        back.setOnClickListener(v -> finish());
        header.addView(back, new LinearLayout.LayoutParams(dp(44), dp(44)));
        TextView title = text("라이브러리+", 24, true);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(44), 1f));
        root.addView(header);

        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout tabs = row();
        addTab(tabs, "라이브러리", this::renderLibrary);
        addTab(tabs, "다운로드", this::renderDownloads);
        addTab(tabs, "기록/통계", this::renderHistory);
        addTab(tabs, "스마트 플리", this::renderSmartRules);
        scroll.addView(tabs);
        LinearLayout.LayoutParams tabsLp = new LinearLayout.LayoutParams(-1, dp(48));
        tabsLp.topMargin = dp(8);
        root.addView(scroll, tabsLp);

        ScrollView body = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, dp(10), 0, dp(40));
        body.addView(content);
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);
    }

    private void connectPlayer() {
        SessionToken token = new SessionToken(this, new ComponentName(this, PlaybackService.class));
        controllerFuture = new MediaController.Builder(this, token).buildAsync();
        controllerFuture.addListener(() -> {
            try { controller = controllerFuture.get(); } catch (Exception ignored) { }
        }, ContextCompat.getMainExecutor(this));
    }

    private void renderLibrary() {
        content.removeAllViews();
        List<Track> tracks = library.load();
        content.addView(sectionTitle("내 라이브러리 · " + tracks.size() + "곡"));

        LinearLayout actions = row();
        actions.addView(button("공용 폴더 스캔", v -> scanShared()), new LinearLayout.LayoutParams(0, dp(48), 1f));
        actions.addView(button("자동 백업", v -> autoBackup()), new LinearLayout.LayoutParams(0, dp(48), 1f));
        content.addView(actions);

        content.addView(label("곡", 15, true));
        for (Track track : tracks) content.addView(trackRow(track, tracks));

        renderGroups("아티스트", group(tracks, "artist"), tracks);
        renderGroups("앨범", group(tracks, "album"), tracks);
        renderGroups("자동 태그", group(tracks, "tag"), tracks);
    }

    private void renderGroups(String title, Map<String, List<Track>> groups, List<Track> all) {
        TextView heading = label(title, 17, true);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, -2);
        hp.topMargin = dp(24);
        content.addView(heading, hp);
        for (Map.Entry<String, List<Track>> entry : groups.entrySet()) {
            if (entry.getKey().trim().isEmpty()) continue;
            TextView item = card(entry.getKey() + "  ·  " + entry.getValue().size() + "곡");
            item.setOnClickListener(v -> showGroup(entry.getKey(), entry.getValue()));
            content.addView(item);
        }
    }

    private void showGroup(String name, List<Track> tracks) {
        content.removeAllViews();
        TextView back = card("‹  라이브러리로");
        back.setOnClickListener(v -> renderLibrary());
        content.addView(back);
        content.addView(sectionTitle(name + " · " + tracks.size() + "곡"));
        for (Track track : tracks) content.addView(trackRow(track, tracks));
    }

    private View trackRow(Track track, List<Track> queue) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(10), dp(14), dp(10));
        box.setBackgroundResource(R.drawable.bg_smart_card);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(6);
        box.setLayoutParams(lp);
        TextView title = text(track.title, 14, true);
        TextView meta = text(track.artist + " · " + (track.album.isEmpty() ? "싱글/기타" : track.album)
                + " · " + track.playCount + "회", 11, false);
        TextView tags = text(track.tags == null ? "" : track.tags.replace(',', ' · '), 10, false);
        box.addView(title);
        box.addView(meta);
        box.addView(tags);
        box.setOnClickListener(v -> play(queue, track.id));
        return box;
    }

    private void play(List<Track> source, String startId) {
        if (controller == null) return;
        List<MediaItem> items = new ArrayList<>();
        int start = 0;
        for (Track t : source) {
            if (!TrackStorage.exists(this, t.path)) continue;
            if (t.id.equals(startId)) start = items.size();
            MediaMetadata.Builder md = new MediaMetadata.Builder().setTitle(t.title).setArtist(t.artist).setAlbumTitle(t.album);
            Uri art = ArtworkLoader.bestArtworkUri(this, t.id, t.thumbnailUrl);
            if (art != null) md.setArtworkUri(art);
            items.add(new MediaItem.Builder().setMediaId(t.id).setUri(TrackStorage.uri(t.path)).setMediaMetadata(md.build()).build());
        }
        if (items.isEmpty()) return;
        controller.setMediaItems(items, Math.min(start, items.size() - 1), 0L);
        controller.prepare();
        controller.play();
    }

    private void scanShared() {
        io.execute(() -> {
            int added = library.importSharedMusic();
            runOnUiThread(() -> {
                renderLibrary();
                toast(added > 0 ? "공용 Music 폴더에서 " + added + "곡을 복구했습니다." : "새로 복구할 곡이 없습니다.");
            });
        });
    }

    private void autoBackup() {
        io.execute(() -> {
            try {
                Uri uri = new AutoBackupManager(this).backupNow();
                runOnUiThread(() -> toast(uri == null ? "이 Android 버전에서는 수동 백업을 사용해 주세요." : "Downloads/No Limit Music에 자동 백업했습니다."));
            } catch (Exception e) {
                runOnUiThread(() -> toast("자동 백업 실패 · " + e.getMessage()));
            }
        });
    }

    private void renderDownloads() {
        content.removeAllViews();
        content.addView(sectionTitle("다운로드 관리자"));
        TextView hint = text("대기열은 한 곡씩 순서대로 저장됩니다. 실패한 항목은 다시 대기시킬 수 있습니다.", 11, false);
        content.addView(hint);
        Button clear = button("완료 항목 정리", v -> { downloads.clearFinished(); renderDownloads(); });
        content.addView(clear);
        for (DownloadTaskStore.Task task : downloads.load()) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(14), dp(10), dp(14), dp(10));
            row.setBackgroundResource(R.drawable.bg_smart_card);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(7); row.setLayoutParams(lp);
            row.addView(text(task.item.title, 14, true));
            String state = stateLabel(task.state) + ("running".equals(task.state) ? " " + task.progress + "%" : "");
            if (!task.error.isEmpty()) state += " · " + task.error;
            row.addView(text(state, 11, false));
            LinearLayout buttons = row();
            if ("failed".equals(task.state)) buttons.addView(button("재시도", v -> { downloads.retry(task.item.id); renderDownloads(); }));
            if (!"running".equals(task.state)) buttons.addView(button("목록에서 제거", v -> { downloads.remove(task.item.id); renderDownloads(); }));
            row.addView(buttons);
            content.addView(row);
        }
    }

    private void renderHistory() {
        content.removeAllViews();
        List<HistoryStore.Entry> entries = history.load();
        long total = history.totalListeningMs();
        content.addView(sectionTitle("재생 기록 · " + entries.size() + "회"));
        content.addView(card("누적 청취 시간  " + formatDuration(total)));

        Map<String, Integer> artistCounts = new LinkedHashMap<>();
        for (HistoryStore.Entry e : entries) artistCounts.put(e.artist, artistCounts.getOrDefault(e.artist, 0) + 1);
        List<Map.Entry<String, Integer>> ranking = new ArrayList<>(artistCounts.entrySet());
        ranking.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        content.addView(label("많이 들은 아티스트", 17, true));
        for (int i = 0; i < Math.min(10, ranking.size()); i++) {
            Map.Entry<String, Integer> e = ranking.get(i);
            content.addView(card((i + 1) + ".  " + e.getKey() + " · " + e.getValue() + "회"));
        }

        content.addView(label("최근 재생", 17, true));
        SimpleDateFormat df = new SimpleDateFormat("M/d HH:mm", Locale.KOREA);
        for (int i = 0; i < Math.min(100, entries.size()); i++) {
            HistoryStore.Entry e = entries.get(i);
            content.addView(card(e.title + "\n" + e.artist + " · " + df.format(new Date(e.playedAt))));
        }
        Button clear = button("재생 기록 지우기", v -> { history.clear(); renderHistory(); });
        content.addView(clear);
    }

    private void renderSmartRules() {
        content.removeAllViews();
        content.addView(sectionTitle("조건형 스마트 플레이리스트"));
        MaterialSwitch liked = new MaterialSwitch(this);
        liked.setText("좋아요한 곡만");
        liked.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        content.addView(liked);
        EditText minPlays = input("최소 재생 횟수 (예: 5)", true);
        EditText artist = input("아티스트 이름 포함", false);
        EditText tag = input("태그 포함 (예: 드라이브)", false);
        EditText days = input("최근 N일 동안 안 들은 곡 (예: 14)", true);
        EditText name = input("저장할 플레이리스트 이름", false);
        content.addView(minPlays); content.addView(artist); content.addView(tag); content.addView(days); content.addView(name);
        LinearLayout actions = row();
        actions.addView(button("미리보기", v -> previewRules(liked, minPlays, artist, tag, days, null)), new LinearLayout.LayoutParams(0, dp(48), 1f));
        actions.addView(button("플리로 저장", v -> previewRules(liked, minPlays, artist, tag, days, name)), new LinearLayout.LayoutParams(0, dp(48), 1f));
        content.addView(actions);
    }

    private void previewRules(MaterialSwitch liked, EditText minPlays, EditText artist, EditText tag, EditText days, EditText saveName) {
        int min = intValue(minPlays, 0);
        int day = intValue(days, 0);
        String artistQ = artist.getText().toString().trim().toLowerCase(Locale.ROOT);
        String tagQ = tag.getText().toString().trim().toLowerCase(Locale.ROOT);
        long cutoff = day > 0 ? System.currentTimeMillis() - day * 86400000L : 0L;
        List<Track> matches = new ArrayList<>();
        for (Track t : library.load()) {
            if (liked.isChecked() && !t.liked) continue;
            if (t.playCount < min) continue;
            if (!artistQ.isEmpty() && !t.artist.toLowerCase(Locale.ROOT).contains(artistQ)) continue;
            if (!tagQ.isEmpty() && !t.tags.toLowerCase(Locale.ROOT).contains(tagQ)) continue;
            if (day > 0 && t.lastPlayedAt >= cutoff) continue;
            matches.add(t);
        }
        if (saveName != null) {
            String n = saveName.getText().toString().trim();
            if (n.isEmpty()) n = "스마트 플리 " + new SimpleDateFormat("M-d", Locale.KOREA).format(new Date());
            Playlist p = playlists.create(n);
            for (int i = matches.size() - 1; i >= 0; i--) playlists.addTrack(p.id, matches.get(i).id);
            toast(n + " · " + matches.size() + "곡 저장");
            return;
        }
        content.removeAllViews();
        TextView back = card("‹  조건 다시 설정"); back.setOnClickListener(v -> renderSmartRules()); content.addView(back);
        content.addView(sectionTitle("조건 결과 · " + matches.size() + "곡"));
        for (Track t : matches) content.addView(trackRow(t, matches));
    }

    private Map<String, List<Track>> group(List<Track> tracks, String type) {
        Map<String, List<Track>> out = new LinkedHashMap<>();
        for (Track t : tracks) {
            if ("tag".equals(type)) {
                String[] tags = (t.tags == null ? "" : t.tags).split(",");
                for (String tag : tags) addGroup(out, tag.trim(), t);
            } else {
                String key = "artist".equals(type) ? t.artist : (t.album == null || t.album.isEmpty() ? "싱글/기타" : t.album);
                addGroup(out, key, t);
            }
        }
        return out;
    }

    private static void addGroup(Map<String, List<Track>> out, String key, Track track) {
        if (key == null || key.trim().isEmpty()) return;
        out.computeIfAbsent(key, k -> new ArrayList<>()).add(track);
    }

    private void addTab(LinearLayout tabs, String name, Runnable action) {
        TextView tab = card(name);
        tab.setGravity(Gravity.CENTER);
        tab.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(40)); lp.setMarginEnd(dp(7));
        tabs.addView(tab, lp);
    }

    private TextView sectionTitle(String s) { TextView v = text(s, 21, true); v.setPadding(0, dp(8), 0, dp(10)); return v; }
    private TextView label(String s, int sp, boolean bold) { TextView v = text(s, sp, bold); v.setPadding(0, dp(12), 0, dp(5)); return v; }
    private TextView card(String s) { TextView v = text(s, 13, true); v.setPadding(dp(14), dp(11), dp(14), dp(11)); v.setBackgroundResource(R.drawable.bg_smart_card); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(6); v.setLayoutParams(lp); return v; }
    private TextView text(String s, int sp, boolean bold) { TextView v = new TextView(this); v.setText(s); v.setTextSize(sp); v.setTextColor(ContextCompat.getColor(this, bold ? R.color.text_primary : R.color.muted)); if (bold) v.setTypeface(v.getTypeface(), Typeface.BOLD); return v; }
    private LinearLayout row() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER_VERTICAL); return l; }
    private Button button(String text, View.OnClickListener click) { Button b = new Button(this); b.setText(text); b.setOnClickListener(click); return b; }
    private EditText input(String hint, boolean number) { EditText e = new EditText(this); e.setHint(hint); e.setTextColor(ContextCompat.getColor(this, R.color.text_primary)); e.setHintTextColor(ContextCompat.getColor(this, R.color.muted)); e.setBackgroundResource(R.drawable.bg_search); e.setPadding(dp(14), 0, dp(14), 0); if (number) e.setInputType(InputType.TYPE_CLASS_NUMBER); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(50)); lp.topMargin = dp(7); e.setLayoutParams(lp); return e; }
    private int intValue(EditText e, int fallback) { try { return Integer.parseInt(e.getText().toString().trim()); } catch (Exception ignored) { return fallback; } }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private void toast(String s) { android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show(); }
    private static String stateLabel(String s) { if ("running".equals(s)) return "다운로드 중"; if ("done".equals(s)) return "완료"; if ("failed".equals(s)) return "실패"; return "대기 중"; }
    private static String formatDuration(long ms) { long sec = ms / 1000L; long h = sec / 3600L; long m = (sec % 3600L) / 60L; return h > 0 ? h + "시간 " + m + "분" : m + "분"; }

    @Override protected void onDestroy() {
        if (controllerFuture != null) MediaController.releaseFuture(controllerFuture);
        controller = null;
        io.shutdownNow();
        super.onDestroy();
    }
}
