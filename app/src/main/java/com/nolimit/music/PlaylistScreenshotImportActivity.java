package com.nolimit.music;

import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import com.nolimit.music.data.DownloadQueueManager;
import com.nolimit.music.data.LibraryStore;
import com.nolimit.music.data.PlaylistStore;
import com.nolimit.music.data.YoutubeRepository;
import com.nolimit.music.model.Playlist;
import com.nolimit.music.model.SearchResult;
import com.nolimit.music.model.Track;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class PlaylistScreenshotImportActivity extends AppCompatActivity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private ActivityResultLauncher<String> imagePicker;
    private EditText detectedText;
    private EditText playlistName;
    private TextView status;
    private LinearLayout matchesContainer;
    private Button matchButton;
    private Button importButton;
    private YoutubeRepository repository;
    private LibraryStore library;
    private PlaylistStore playlists;
    private DownloadQueueManager downloads;
    private final List<MatchedRow> matchedRows = new ArrayList<>();

    private static final class MatchedRow {
        final String query;
        final SearchResult result;
        final CheckBox checkBox;
        MatchedRow(String query, SearchResult result, CheckBox checkBox) {
            this.query = query; this.result = result; this.checkBox = checkBox;
        }
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        repository = new YoutubeRepository(this);
        library = new LibraryStore(this);
        playlists = new PlaylistStore(this, library);
        downloads = DownloadQueueManager.get(this);
        imagePicker = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri != null) recognizeScreenshot(uri);
        });
        buildUi();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(ContextCompat.getColor(this, R.color.app_bg));
        root.setPadding(dp(18), dp(12), dp(18), dp(14));

        LinearLayout header = row();
        TextView back = text("‹", 32, true); back.setGravity(Gravity.CENTER); back.setOnClickListener(v -> finish());
        header.addView(back, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout titles = new LinearLayout(this); titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(text("스크린샷에서 플리 가져오기", 22, true));
        titles.addView(text("이미지 안의 곡명을 인식하고 자동으로 검색합니다", 11, false));
        header.addView(titles, new LinearLayout.LayoutParams(0, dp(52), 1f));
        root.addView(header);

        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(0, dp(8), 0, dp(28));
        scroll.addView(body); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        TextView privacy = card("사진은 서버로 보내지 않고 이 기기에서 OCR합니다.\n인식 결과는 추가 전에 직접 확인·수정할 수 있습니다.");
        body.addView(privacy);

        Button pick = button("스크린샷 선택", v -> imagePicker.launch("image/*"));
        LinearLayout.LayoutParams pickLp = new LinearLayout.LayoutParams(-1, dp(50)); pickLp.topMargin = dp(10); body.addView(pick, pickLp);
        status = text("먼저 플레이리스트가 보이는 스크린샷을 선택하세요.", 11, false);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(-1, -2); statusLp.topMargin = dp(8); body.addView(status, statusLp);

        body.addView(section("인식된 곡 후보"));
        detectedText = new EditText(this);
        detectedText.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        detectedText.setHintTextColor(ContextCompat.getColor(this, R.color.muted));
        detectedText.setHint("한 줄에 곡명 또는 ‘곡명 - 아티스트’ 형태로 수정할 수 있습니다.");
        detectedText.setTextSize(13f); detectedText.setGravity(Gravity.TOP); detectedText.setMinLines(7); detectedText.setMaxLines(14);
        detectedText.setPadding(dp(14), dp(12), dp(14), dp(12)); detectedText.setBackgroundResource(R.drawable.bg_smart_card);
        body.addView(detectedText, new LinearLayout.LayoutParams(-1, dp(220)));

        matchButton = button("곡 찾기", v -> matchCandidates());
        LinearLayout.LayoutParams matchLp = new LinearLayout.LayoutParams(-1, dp(50)); matchLp.topMargin = dp(10); body.addView(matchButton, matchLp);

        body.addView(section("매칭 결과"));
        matchesContainer = new LinearLayout(this); matchesContainer.setOrientation(LinearLayout.VERTICAL); body.addView(matchesContainer);
        matchesContainer.addView(card("아직 매칭된 곡이 없습니다."));

        body.addView(section("플레이리스트 이름"));
        playlistName = new EditText(this);
        playlistName.setSingleLine(true); playlistName.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        playlistName.setText("캡처 가져오기 " + new SimpleDateFormat("M.d", Locale.KOREA).format(new Date()));
        playlistName.setPadding(dp(14), 0, dp(14), 0); playlistName.setBackgroundResource(R.drawable.bg_smart_card);
        body.addView(playlistName, new LinearLayout.LayoutParams(-1, dp(52)));

        importButton = button("선택한 곡을 플레이리스트에 추가", v -> importSelected());
        importButton.setEnabled(false);
        LinearLayout.LayoutParams importLp = new LinearLayout.LayoutParams(-1, dp(54)); importLp.topMargin = dp(12); body.addView(importButton, importLp);
        setContentView(root);
    }

    private void recognizeScreenshot(Uri uri) {
        status.setText("이미지 분석 중…");
        detectedText.setText(""); matchedRows.clear(); importButton.setEnabled(false);
        matchesContainer.removeAllViews(); matchesContainer.addView(card("OCR 처리 중…"));
        io.execute(() -> {
            TextRecognizer korean = null; TextRecognizer latin = null;
            try {
                InputImage image = InputImage.fromFilePath(this, uri);
                korean = TextRecognition.getClient(new KoreanTextRecognizerOptions.Builder().build());
                latin = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
                Text ko = Tasks.await(korean.process(image));
                Text en = Tasks.await(latin.process(image));
                List<String> lines = extractCandidates(ko, en);
                String joined = String.join("\n", lines);
                runOnUiThread(() -> {
                    detectedText.setText(joined);
                    status.setText(lines.isEmpty() ? "곡 후보를 찾지 못했습니다. 이미지가 선명한지 확인해 주세요." : lines.size() + "개 후보를 찾았습니다. 필요하면 수정한 뒤 ‘곡 찾기’를 누르세요.");
                    matchesContainer.removeAllViews(); matchesContainer.addView(card("후보를 확인한 뒤 ‘곡 찾기’를 누르세요."));
                });
            } catch (Exception e) {
                runOnUiThread(() -> status.setText("이미지 분석 실패 · " + compact(e)));
            } finally {
                if (korean != null) korean.close(); if (latin != null) latin.close();
            }
        });
    }

    private List<String> extractCandidates(Text... results) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (Text result : results) {
            if (result == null) continue;
            for (Text.TextBlock block : result.getTextBlocks()) {
                for (Text.Line line : block.getLines()) {
                    String clean = cleanCandidate(line.getText());
                    if (isUsefulCandidate(clean)) unique.add(clean);
                }
            }
        }
        List<String> list = new ArrayList<>(unique);
        if (list.size() > 80) return new ArrayList<>(list.subList(0, 80));
        return list;
    }

    private static String cleanCandidate(String raw) {
        if (raw == null) return "";
        return raw.replaceAll("^[\\s#•·▶▷♪♫✓✔]+", "")
                .replaceAll("^\\d{1,3}[.)\\-]?\\s+", "")
                .replaceAll("\\s+", " ").trim();
    }

    private static boolean isUsefulCandidate(String value) {
        if (value == null || value.length() < 2 || value.length() > 100) return false;
        String l = value.toLowerCase(Locale.ROOT);
        if (l.matches("^\\d{1,2}:\\d{2}(:\\d{2})?$") || l.matches("^[\\d,.]+$") || l.matches("^\\d+곡$") || l.matches("^\\d+ songs?$") ) return false;
        String[] junk = {"재생목록", "플레이리스트", "playlist", "좋아요", "공유", "저장", "다운로드", "shuffle", "셔플", "youtube music", "spotify", "apple music", "soundcloud", "bandcamp", "audius", "검색", "home", "홈"};
        for (String j : junk) if (l.equals(j) || (l.length() < 24 && l.contains(j))) return false;
        return value.matches(".*[\\p{L}].*");
    }

    private void matchCandidates() {
        String raw = detectedText.getText().toString();
        List<String> queries = new ArrayList<>(); Set<String> seen = new HashSet<>();
        for (String line : raw.split("\\r?\\n")) {
            String q = cleanCandidate(line);
            if (isUsefulCandidate(q) && seen.add(q.toLowerCase(Locale.ROOT))) queries.add(q);
        }
        if (queries.isEmpty()) { toast("검색할 곡 후보를 한 줄 이상 입력해 주세요."); return; }
        if (queries.size() > 60) queries = new ArrayList<>(queries.subList(0, 60));
        final List<String> targetQueries = queries;
        matchButton.setEnabled(false); importButton.setEnabled(false); status.setText("0 / " + targetQueries.size() + "곡 검색 중…");
        matchesContainer.removeAllViews(); matchedRows.clear();
        io.execute(() -> {
            try { repository.init(); }
            catch (Exception e) { runOnUiThread(() -> { matchButton.setEnabled(true); status.setText("검색 엔진 초기화 실패 · " + compact(e)); }); return; }
            List<Match> found = new ArrayList<>(); int done = 0;
            for (String query : targetQueries) {
                try {
                    SearchResult best = findBest(query);
                    if (best != null) found.add(new Match(query, best));
                } catch (Exception ignored) { }
                done++;
                int progress = done;
                runOnUiThread(() -> status.setText(progress + " / " + targetQueries.size() + "곡 검색 중…"));
            }
            runOnUiThread(() -> renderMatches(found, targetQueries.size()));
        });
    }

    private SearchResult findBest(String query) throws Exception {
        List<SearchResult> candidates = new ArrayList<>();
        try { candidates.addAll(repository.searchYoutubeMusicSongs(query, 5)); } catch (Exception ignored) { }
        try { candidates.addAll(repository.searchYoutube(query, 5)); } catch (Exception ignored) { }
        SearchResult best = null; int bestScore = Integer.MIN_VALUE;
        for (SearchResult r : candidates) {
            int score = r.score + titleOverlapScore(query, r.title) + artistOverlapScore(query, r.channel);
            if (score > bestScore) { bestScore = score; best = r; }
        }
        return bestScore >= 24 ? best : null;
    }

    private static int titleOverlapScore(String query, String title) {
        Set<String> q = tokens(query); Set<String> t = tokens(title);
        if (q.isEmpty() || t.isEmpty()) return 0;
        int hit = 0; for (String token : q) if (token.length() >= 2 && t.contains(token)) hit++;
        int score = hit * 18;
        String nq = normalized(query), nt = normalized(title);
        if (nq.length() >= 3 && (nt.contains(nq) || nq.contains(nt))) score += 45;
        return score;
    }

    private static int artistOverlapScore(String query, String artist) {
        Set<String> q = tokens(query); Set<String> a = tokens(artist); int hit = 0;
        for (String token : q) if (token.length() >= 2 && a.contains(token)) hit++;
        return hit * 7;
    }

    private static Set<String> tokens(String value) {
        Set<String> out = new HashSet<>();
        if (value == null) return out;
        for (String s : value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim().split("\\s+")) if (s.length() >= 2) out.add(s);
        return out;
    }

    private static String normalized(String value) { return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", ""); }

    private static final class Match { final String query; final SearchResult result; Match(String q, SearchResult r) { query=q; result=r; } }

    private void renderMatches(List<Match> found, int totalQueries) {
        matchesContainer.removeAllViews(); matchedRows.clear(); matchButton.setEnabled(true);
        if (found.isEmpty()) {
            matchesContainer.addView(card("확실하게 매칭된 곡을 찾지 못했습니다.\n인식된 텍스트를 ‘곡명 - 아티스트’ 형태로 조금 수정해 다시 시도해 보세요."));
            status.setText(totalQueries + "개 후보 검색 완료 · 매칭 0곡"); return;
        }
        for (Match m : found) {
            CheckBox box = new CheckBox(this); box.setChecked(true); box.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
            box.setText(m.result.title + "\n" + m.result.channel + "\n← " + m.query + " · " + m.result.badge);
            box.setTextSize(12f); box.setPadding(dp(10), dp(7), dp(10), dp(7)); box.setBackgroundResource(R.drawable.bg_smart_card);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(6); matchesContainer.addView(box, lp);
            matchedRows.add(new MatchedRow(m.query, m.result, box));
        }
        status.setText(totalQueries + "개 후보 검색 완료 · " + found.size() + "곡 매칭"); importButton.setEnabled(true);
    }

    private void importSelected() {
        List<SearchResult> selected = new ArrayList<>();
        for (MatchedRow row : matchedRows) if (row.checkBox.isChecked()) selected.add(row.result);
        if (selected.isEmpty()) { toast("추가할 곡을 하나 이상 선택해 주세요."); return; }
        String name = playlistName.getText().toString().trim();
        Playlist playlist = playlists.create(name.isEmpty() ? "캡처 가져오기" : name);
        int ready = 0, queued = 0;
        for (SearchResult result : selected) {
            Track existing = library.find(result.id);
            if (existing != null && com.nolimit.music.data.TrackStorage.exists(this, existing.path)) {
                playlists.addTrack(playlist.id, existing.id); ready++;
            } else {
                downloads.enqueue(result, playlist.id); queued++;
            }
        }
        status.setText("‘" + playlist.name + "’ 생성 완료 · 바로 추가 " + ready + "곡 · 다운로드 대기 " + queued + "곡");
        toast("플레이리스트를 만들었습니다. 다운로드가 끝난 곡도 자동으로 여기에 들어갑니다.");
        importButton.setEnabled(false);
    }

    private LinearLayout row() { LinearLayout r = new LinearLayout(this); r.setOrientation(LinearLayout.HORIZONTAL); r.setGravity(Gravity.CENTER_VERTICAL); return r; }
    private TextView section(String title) { TextView v=text(title,16,true); LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2); lp.topMargin=dp(22); lp.bottomMargin=dp(7); v.setLayoutParams(lp); return v; }
    private TextView card(String value) { TextView v=text(value,12,false); v.setPadding(dp(14),dp(12),dp(14),dp(12)); v.setBackgroundResource(R.drawable.bg_smart_card); return v; }
    private TextView text(String value, int sp, boolean bold) { TextView v=new TextView(this); v.setText(value); v.setTextSize(sp); v.setTextColor(ContextCompat.getColor(this,bold?R.color.text_primary:R.color.muted)); if(bold)v.setTypeface(v.getTypeface(),Typeface.BOLD); return v; }
    private Button button(String label, View.OnClickListener listener) { Button b=new Button(this); b.setText(label); b.setTextAllCaps(false); b.setOnClickListener(listener); return b; }
    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_SHORT).show(); }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private static String compact(Throwable e) { String m=e==null?"":e.getMessage(); if(m==null||m.trim().isEmpty())return e==null?"오류":e.getClass().getSimpleName(); m=m.replace('\n',' ').replace('\r',' ').trim(); return m.length()>140?m.substring(0,140):m; }

    @Override protected void onDestroy() { io.shutdownNow(); super.onDestroy(); }
}
