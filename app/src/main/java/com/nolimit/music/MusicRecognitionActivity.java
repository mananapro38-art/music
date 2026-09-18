package com.nolimit.music;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.nolimit.music.data.DownloadQueueManager;
import com.nolimit.music.data.YoutubeRepository;
import com.nolimit.music.model.SearchResult;

import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MusicRecognitionActivity extends AppCompatActivity {
    private static final String PREFS = "settings";
    private static final String KEY_AUDD_TOKEN = "audd_api_token";
    private static final int SAMPLE_MS = 9000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private ActivityResultLauncher<String> micPermission;
    private MediaRecorder recorder;
    private File sampleFile;
    private Button listenButton;
    private TextView status;
    private LinearLayout resultBox;
    private TextView resultTitle;
    private TextView resultArtist;
    private TextView resultAlbum;
    private Button saveButton;
    private LinearLayout servicePanel;
    private EditText tokenInput;
    private SearchResult matchedResult;
    private int pulse = 0;

    private final Runnable pulseRunnable = new Runnable() {
        @Override public void run() {
            if (recorder == null) return;
            pulse = (pulse + 1) % 4;
            String dots = pulse == 0 ? "" : pulse == 1 ? "." : pulse == 2 ? ".." : "...";
            status.setText("주변 음악을 듣는 중" + dots);
            handler.postDelayed(this, 450);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        micPermission = registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            if (granted) beginCapture();
            else status.setText("마이크 권한이 있어야 주변 음악을 들을 수 있습니다.");
        });
        buildUi();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(14), dp(20), dp(20));
        root.setBackgroundColor(ContextCompat.getColor(this, R.color.app_bg));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView close = text("‹", 32, true);
        close.setGravity(Gravity.CENTER);
        close.setOnClickListener(v -> finish());
        header.addView(close, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(text("노래 찾기", 28, true));
        titles.addView(text("주변에서 재생되는 음악을 몇 초만 들려주세요", 11, false));
        header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(header);

        TextView privacy = text("인식할 때만 약 9초의 오디오 샘플을 AudD 서버로 전송합니다. 녹음 파일은 인식 후 이 기기에서 삭제합니다.", 11, false);
        privacy.setPadding(dp(15), dp(12), dp(15), dp(12));
        privacy.setBackgroundResource(R.drawable.bg_smart_card);
        LinearLayout.LayoutParams privacyLp = new LinearLayout.LayoutParams(-1, -2);
        privacyLp.topMargin = dp(20);
        root.addView(privacy, privacyLp);

        TextView orb = text("≈", 62, true);
        orb.setGravity(Gravity.CENTER);
        orb.setTextColor(ContextCompat.getColor(this, R.color.accent));
        orb.setBackgroundResource(R.drawable.bg_recognition_orb);
        LinearLayout.LayoutParams orbLp = new LinearLayout.LayoutParams(dp(188), dp(188));
        orbLp.gravity = Gravity.CENTER_HORIZONTAL;
        orbLp.topMargin = dp(34);
        root.addView(orb, orbLp);

        status = text("버튼을 누르면 음악을 듣기 시작합니다.", 13, false);
        status.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(-1, -2);
        statusLp.topMargin = dp(18);
        root.addView(status, statusLp);

        listenButton = new Button(this);
        listenButton.setText("음악 듣고 찾기");
        listenButton.setAllCaps(false);
        listenButton.setTextSize(15f);
        listenButton.setOnClickListener(v -> startRecognition());
        LinearLayout.LayoutParams listenLp = new LinearLayout.LayoutParams(-1, dp(56));
        listenLp.topMargin = dp(18);
        root.addView(listenButton, listenLp);

        resultBox = new LinearLayout(this);
        resultBox.setOrientation(LinearLayout.VERTICAL);
        resultBox.setPadding(dp(16), dp(14), dp(16), dp(14));
        resultBox.setBackgroundResource(R.drawable.bg_glass_panel);
        resultBox.setVisibility(View.GONE);
        resultTitle = text("", 21, true);
        resultArtist = text("", 13, false);
        resultAlbum = text("", 11, false);
        resultBox.addView(resultTitle);
        resultBox.addView(resultArtist);
        resultBox.addView(resultAlbum);

        saveButton = new Button(this);
        saveButton.setAllCaps(false);
        saveButton.setText("No Limit Music에서 저장");
        saveButton.setEnabled(false);
        saveButton.setOnClickListener(v -> {
            if (matchedResult == null) return;
            DownloadQueueManager.get(this).enqueue(matchedResult);
            Toast.makeText(this, "다운로드 대기열에 추가했습니다.", Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams sbLp = new LinearLayout.LayoutParams(-1, dp(50));
        sbLp.topMargin = dp(12);
        resultBox.addView(saveButton, sbLp);
        LinearLayout.LayoutParams resultLp = new LinearLayout.LayoutParams(-1, -2);
        resultLp.topMargin = dp(18);
        root.addView(resultBox, resultLp);

        TextView serviceHeader = text("인식 서비스 설정  ›", 12, true);
        serviceHeader.setPadding(dp(2), dp(14), dp(2), dp(10));
        serviceHeader.setOnClickListener(v -> {
            boolean open = servicePanel.getVisibility() != View.VISIBLE;
            servicePanel.setVisibility(open ? View.VISIBLE : View.GONE);
            serviceHeader.setText(open ? "인식 서비스 설정  ⌄" : "인식 서비스 설정  ›");
        });
        root.addView(serviceHeader);

        servicePanel = new LinearLayout(this);
        servicePanel.setOrientation(LinearLayout.VERTICAL);
        servicePanel.setVisibility(View.GONE);
        tokenInput = new EditText(this);
        tokenInput.setSingleLine(true);
        tokenInput.setHint("AudD API token (비워두면 제한된 체험 토큰)");
        tokenInput.setText(getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_AUDD_TOKEN, ""));
        tokenInput.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        tokenInput.setHintTextColor(ContextCompat.getColor(this, R.color.muted));
        tokenInput.setBackgroundResource(R.drawable.bg_search);
        tokenInput.setPadding(dp(14), 0, dp(14), 0);
        servicePanel.addView(tokenInput, new LinearLayout.LayoutParams(-1, dp(52)));
        Button saveToken = new Button(this);
        saveToken.setText("토큰 저장");
        saveToken.setAllCaps(false);
        saveToken.setOnClickListener(v -> {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_AUDD_TOKEN, tokenInput.getText().toString().trim()).apply();
            Toast.makeText(this, "인식 서비스 설정을 저장했습니다.", Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(-1, dp(48));
        stLp.topMargin = dp(6);
        servicePanel.addView(saveToken, stLp);
        TextView hint = text("체험 토큰은 요청 수가 제한됩니다. 자주 사용한다면 개인 AudD 토큰을 입력하는 편이 안정적입니다.", 10, false);
        servicePanel.addView(hint);
        root.addView(servicePanel);

        setContentView(root);
    }

    private void startRecognition() {
        if (recorder != null) return;
        resultBox.setVisibility(View.GONE);
        matchedResult = null;
        saveButton.setEnabled(false);
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO);
        } else {
            beginCapture();
        }
    }

    private void beginCapture() {
        try {
            sampleFile = new File(getCacheDir(), "recognition_sample.m4a");
            if (sampleFile.exists()) sampleFile.delete();
            recorder = new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioSamplingRate(44100);
            recorder.setAudioEncodingBitRate(128000);
            recorder.setOutputFile(sampleFile.getAbsolutePath());
            recorder.prepare();
            recorder.start();
            listenButton.setEnabled(false);
            listenButton.setText("듣는 중…");
            pulse = 0;
            handler.post(pulseRunnable);
            handler.postDelayed(this::finishCaptureAndRecognize, SAMPLE_MS);
        } catch (Exception e) {
            releaseRecorder();
            status.setText("녹음을 시작하지 못했습니다 · " + compact(e));
            listenButton.setEnabled(true);
            listenButton.setText("다시 시도");
        }
    }

    private void finishCaptureAndRecognize() {
        handler.removeCallbacks(pulseRunnable);
        try {
            if (recorder != null) recorder.stop();
        } catch (Exception ignored) { }
        releaseRecorder();
        listenButton.setText("분석 중…");
        status.setText("곡을 확인하고 있습니다.");
        if (sampleFile == null || !sampleFile.exists() || sampleFile.length() < 1000) {
            status.setText("충분한 소리를 녹음하지 못했습니다.");
            resetButton();
            return;
        }
        io.execute(this::recognizeSample);
    }

    private void recognizeSample() {
        try {
            SharedPreferences prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String token = prefs.getString(KEY_AUDD_TOKEN, "").trim();
            if (token.isEmpty()) token = "test";
            JSONObject response = postToAudd(sampleFile, token);
            if (!"success".equals(response.optString("status"))) {
                throw new IllegalStateException(response.optString("error", "인식 서비스 오류"));
            }
            JSONObject result = response.optJSONObject("result");
            if (result == null) {
                runOnUiThread(() -> {
                    status.setText("곡을 찾지 못했습니다. 스피커 가까이에서 다시 시도해 보세요.");
                    resetButton();
                });
                return;
            }
            String title = result.optString("title", "").trim();
            String artist = result.optString("artist", "").trim();
            String album = result.optString("album", "").trim();
            SearchResult appMatch = findAppMatch(title, artist);
            matchedResult = appMatch;
            runOnUiThread(() -> showResult(title, artist, album, appMatch));
        } catch (Exception e) {
            runOnUiThread(() -> {
                status.setText("인식 실패 · " + compact(e));
                resetButton();
            });
        } finally {
            if (sampleFile != null) sampleFile.delete();
        }
    }

    private SearchResult findAppMatch(String title, String artist) {
        try {
            YoutubeRepository repo = new YoutubeRepository(this);
            repo.init();
            List<SearchResult> results = repo.search((title + " " + artist).trim());
            return results.isEmpty() ? null : results.get(0);
        } catch (Exception ignored) {
            return null;
        }
    }

    private void showResult(String title, String artist, String album, SearchResult match) {
        status.setText("찾았습니다.");
        resultTitle.setText(title.isEmpty() ? "제목 정보 없음" : title);
        resultArtist.setText(artist);
        resultAlbum.setText(album.isEmpty() ? "" : album);
        resultBox.setVisibility(View.VISIBLE);
        saveButton.setEnabled(match != null);
        saveButton.setText(match == null ? "앱 검색 결과 없음" : "No Limit Music에서 저장");
        resetButton();
    }

    private static JSONObject postToAudd(File file, String token) throws Exception {
        String boundary = "----NoLimitMusic" + System.currentTimeMillis();
        HttpURLConnection conn = (HttpURLConnection) new URL("https://api.audd.io/").openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(25000);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        try (OutputStream raw = new BufferedOutputStream(conn.getOutputStream())) {
            writeField(raw, boundary, "api_token", token);
            writeField(raw, boundary, "return", "apple_music,spotify,deezer");
            String head = "--" + boundary + "\r\n" +
                    "Content-Disposition: form-data; name=\"file\"; filename=\"sample.m4a\"\r\n" +
                    "Content-Type: audio/mp4\r\n\r\n";
            raw.write(head.getBytes(StandardCharsets.UTF_8));
            java.nio.file.Files.copy(file.toPath(), raw);
            raw.write("\r\n".getBytes(StandardCharsets.UTF_8));
            raw.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        }
        int code = conn.getResponseCode();
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream(), StandardCharsets.UTF_8));
        StringBuilder body = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) body.append(line);
        reader.close();
        conn.disconnect();
        if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code);
        return new JSONObject(body.toString());
    }

    private static void writeField(OutputStream out, String boundary, String name, String value) throws Exception {
        String part = "--" + boundary + "\r\n" +
                "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" +
                value + "\r\n";
        out.write(part.getBytes(StandardCharsets.UTF_8));
    }

    private void releaseRecorder() {
        if (recorder != null) {
            try { recorder.release(); } catch (Exception ignored) { }
            recorder = null;
        }
    }

    private void resetButton() {
        listenButton.setEnabled(true);
        listenButton.setText("다시 듣고 찾기");
    }

    private TextView text(String value, int sp, boolean bold) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(sp);
        v.setTextColor(ContextCompat.getColor(this, bold ? R.color.text_primary : R.color.muted));
        if (bold) v.setTypeface(v.getTypeface(), android.graphics.Typeface.BOLD);
        return v;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private static String compact(Throwable e) {
        String m = e == null ? "" : e.getMessage();
        if (m == null || m.trim().isEmpty()) return e == null ? "알 수 없는 오류" : e.getClass().getSimpleName();
        m = m.replace('\n', ' ').replace('\r', ' ').trim();
        return m.length() > 120 ? m.substring(0, 120) : m;
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        releaseRecorder();
        if (sampleFile != null) sampleFile.delete();
        io.shutdownNow();
        super.onDestroy();
    }
}
