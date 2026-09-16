package com.example.mediacollector;

import android.Manifest;
import android.annotation.SuppressLint;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.offline.Download;
import androidx.media3.exoplayer.offline.DownloadManager;
import androidx.media3.exoplayer.offline.DownloadRequest;
import androidx.media3.exoplayer.offline.DownloadService;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;

import org.json.JSONArray;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@UnstableApi
public final class MainActivity extends AppCompatActivity implements DownloadManager.Listener {
    private EditText urlInput;
    private TextView statusText;
    private LinearLayout candidateList;
    private LinearLayout libraryList;
    private TextView libraryEmpty;
    private View findPanel;
    private View libraryPanel;
    private PlayerView playerView;

    private WebView probeWebView;
    private final Set<String> seenCandidates = new HashSet<>();
    private final Map<String, DownloadCard> downloadCards = new HashMap<>();

    private DownloadManager downloadManager;
    private LibraryRepository library;
    private ExoPlayer player;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable progressPoller = new Runnable() {
        @Override public void run() {
            refreshActiveProgress();
            handler.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        urlInput = findViewById(R.id.urlInput);
        statusText = findViewById(R.id.statusText);
        candidateList = findViewById(R.id.candidateList);
        libraryList = findViewById(R.id.libraryList);
        libraryEmpty = findViewById(R.id.libraryEmpty);
        findPanel = findViewById(R.id.findPanel);
        libraryPanel = findViewById(R.id.libraryPanel);
        playerView = findViewById(R.id.playerView);
        Button openButton = findViewById(R.id.openButton);
        Button tabFind = findViewById(R.id.tabFind);
        Button tabLibrary = findViewById(R.id.tabLibrary);

        MediaCollectorApp app = (MediaCollectorApp) getApplication();
        downloadManager = app.downloads();
        downloadManager.addListener(this);
        downloadManager.resumeDownloads();
        library = new LibraryRepository(this);

        requestNotificationPermissionIfNeeded();
        DefaultMediaSourceFactory mediaSourceFactory = new DefaultMediaSourceFactory(app.cacheDataSourceFactory());
        player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(mediaSourceFactory)
                .build();
        playerView.setPlayer(player);

        setupProbeWebView();
        openButton.setOnClickListener(v -> beginProbe());
        tabFind.setOnClickListener(v -> showFind());
        tabLibrary.setOnClickListener(v -> showLibrary());

        renderLibrary();
        handler.post(progressPoller);
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 100);
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupProbeWebView() {
        probeWebView = new WebView(this);
        probeWebView.getSettings().setJavaScriptEnabled(true);
        probeWebView.getSettings().setDomStorageEnabled(true);
        probeWebView.getSettings().setMediaPlaybackRequiresUserGesture(false);
        probeWebView.getSettings().setAllowFileAccess(false);
        probeWebView.getSettings().setAllowContentAccess(false);

        probeWebView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String target = request.getUrl().toString();
                if (!UrlPolicy.isAllowed(target)) {
                    runOnUiThread(() -> statusText.setText("차단된 주소로의 이동을 중단했습니다."));
                    return true;
                }
                return false;
            }

            @Override
            public android.webkit.WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String u = request.getUrl().toString();
                if (UrlPolicy.isAllowed(u) && MediaTypes.looksLikeMedia(u)) {
                    runOnUiThread(() -> addCandidate(new MediaCandidate(
                            u, MediaTypes.infer(u), MediaTypes.titleFromUrl(u))));
                }
                return null;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                statusText.setText("페이지에서 미디어 요청을 찾는 중…");
                String js = "Array.from(document.querySelectorAll('video[src],audio[src],source[src]'))" +
                        ".map(function(e){return e.src;}).filter(Boolean)";
                view.evaluateJavascript(js, MainActivity.this::consumeDomSources);
                handler.postDelayed(() -> {
                    if (seenCandidates.isEmpty()) {
                        statusText.setText("자동 감지된 미디어가 없습니다. 재생 버튼이 필요한 페이지는 직접 미디어 URL을 입력해 보세요.");
                    } else {
                        statusText.setText("감지 완료 · " + seenCandidates.size() + "개");
                    }
                }, 3500);
            }
        });
    }

    private void consumeDomSources(String value) {
        try {
            JSONArray arr = new JSONArray(value);
            for (int i = 0; i < arr.length(); i++) {
                String u = arr.optString(i, "");
                if (!u.isEmpty() && UrlPolicy.isAllowed(u) && MediaTypes.looksLikeMedia(u)) {
                    addCandidate(new MediaCandidate(u, MediaTypes.infer(u), MediaTypes.titleFromUrl(u)));
                }
            }
        } catch (Exception ignored) {}
    }

    private void beginProbe() {
        String raw = urlInput.getText().toString().trim();
        if (!UrlPolicy.isAllowed(raw)) {
            statusText.setText("허용되지 않는 URL입니다. HTTPS 공개 주소만 지원하며 YouTube 계열과 로컬/사설 주소는 제외됩니다.");
            return;
        }
        seenCandidates.clear();
        candidateList.removeAllViews();
        if (MediaTypes.looksLikeMedia(raw)) {
            addCandidate(new MediaCandidate(raw, MediaTypes.infer(raw), MediaTypes.titleFromUrl(raw)));
            statusText.setText("직접 미디어 URL을 감지했습니다.");
            return;
        }
        statusText.setText("페이지 여는 중…");
        probeWebView.loadUrl(raw);
    }

    private void addCandidate(MediaCandidate candidate) {
        if (!seenCandidates.add(candidate.url)) return;

        LinearLayout box = cardContainer();
        TextView title = text(candidate.title, 16, true);
        TextView detail = text(labelForMime(candidate.mimeType) + "\n" + abbreviate(candidate.url, 90), 12, false);
        Button save = new Button(this);
        save.setText("플레이리스트에 저장");
        ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setVisibility(View.GONE);
        TextView progressText = text("", 12, false);

        box.addView(title);
        box.addView(detail);
        box.addView(save);
        box.addView(progress);
        box.addView(progressText);
        candidateList.addView(box);

        save.setOnClickListener(v -> startDownload(candidate, save, progress, progressText));
    }

    private void startDownload(MediaCandidate candidate, Button save, ProgressBar progress, TextView progressText) {
        if (!UrlPolicy.isAllowed(candidate.url)) {
            Toast.makeText(this, "허용되지 않는 주소입니다.", Toast.LENGTH_SHORT).show();
            return;
        }
        String id = stableId(candidate.url);
        DownloadRequest.Builder builder = new DownloadRequest.Builder(id, Uri.parse(candidate.url));
        if (candidate.mimeType != null) builder.setMimeType(candidate.mimeType);
        builder.setData(DownloadMetadata.encode(candidate.title, candidate.mimeType));
        DownloadService.sendAddDownload(
                this, MediaDownloadService.class, builder.build(), true);

        save.setEnabled(false);
        save.setText("저장 중");
        progress.setVisibility(View.VISIBLE);
        progress.setIndeterminate(true);
        progressText.setText("Wi‑Fi에서 다운로드 준비 중…");
        downloadCards.put(id, new DownloadCard(save, progress, progressText));
    }

    private void refreshActiveProgress() {
        List<Download> active = downloadManager.getCurrentDownloads();
        for (Download d : active) updateDownloadCard(d, null);
    }

    @Override
    public void onDownloadChanged(DownloadManager manager, Download download, @Nullable Exception finalException) {
        updateDownloadCard(download, finalException);
        if (download.state == Download.STATE_COMPLETED) {
            MediaCandidate meta = DownloadMetadata.decode(download.request);
            LibraryItem item = new LibraryItem();
            item.id = download.request.id;
            item.url = download.request.uri.toString();
            item.title = meta.title;
            item.mimeType = meta.mimeType;
            item.bytes = download.getBytesDownloaded();
            item.savedAt = System.currentTimeMillis();
            library.upsert(item);
            renderLibrary();
            statusText.setText("✓ 플레이리스트에 저장 완료 · " + item.title);
        }
    }

    private void updateDownloadCard(Download d, @Nullable Exception finalException) {
        DownloadCard card = downloadCards.get(d.request.id);
        if (card == null) return;
        switch (d.state) {
            case Download.STATE_QUEUED:
                card.progress.setIndeterminate(true);
                card.text.setText("대기 중 · Wi‑Fi 연결을 확인하세요.");
                break;
            case Download.STATE_DOWNLOADING:
                float p = d.getPercentDownloaded();
                if (p >= 0) {
                    card.progress.setIndeterminate(false);
                    card.progress.setProgress(Math.max(0, Math.min(100, Math.round(p))));
                    card.text.setText(String.format(Locale.KOREA, "저장 중 %.0f%% · %s", p, humanBytes(d.getBytesDownloaded())));
                } else {
                    card.progress.setIndeterminate(true);
                    card.text.setText("저장 중 · " + humanBytes(d.getBytesDownloaded()));
                }
                break;
            case Download.STATE_COMPLETED:
                card.progress.setIndeterminate(false);
                card.progress.setProgress(100);
                card.text.setText("✓ 플레이리스트에 저장 완료 · " + humanBytes(d.getBytesDownloaded()));
                card.button.setText("저장 완료");
                card.button.setEnabled(false);
                break;
            case Download.STATE_FAILED:
                card.progress.setVisibility(View.GONE);
                card.text.setText("저장 실패" + (finalException == null ? "" : " · " + finalException.getMessage()));
                card.button.setText("다시 저장");
                card.button.setEnabled(true);
                break;
            case Download.STATE_STOPPED:
                card.text.setText("일시 중지됨");
                break;
            default:
                break;
        }
    }

    private void showFind() {
        findPanel.setVisibility(View.VISIBLE);
        libraryPanel.setVisibility(View.GONE);
    }

    private void showLibrary() {
        findPanel.setVisibility(View.GONE);
        libraryPanel.setVisibility(View.VISIBLE);
        renderLibrary();
    }

    private void renderLibrary() {
        if (libraryList == null) return;
        List<LibraryItem> items = library.all();
        libraryList.removeAllViews();
        libraryEmpty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        for (LibraryItem item : items) {
            LinearLayout box = cardContainer();
            TextView title = text(item.title, 16, true);
            TextView detail = text(labelForMime(item.mimeType) + " · 오프라인 · " + humanBytes(item.bytes), 12, false);
            LinearLayout actions = new LinearLayout(this);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            Button play = new Button(this);
            play.setText("▶ 재생");
            Button remove = new Button(this);
            remove.setText("삭제");
            actions.addView(play, new LinearLayout.LayoutParams(0, dp(48), 1));
            actions.addView(remove, new LinearLayout.LayoutParams(0, dp(48), 1));
            box.addView(title);
            box.addView(detail);
            box.addView(actions);
            libraryList.addView(box);

            play.setOnClickListener(v -> playItem(item));
            remove.setOnClickListener(v -> {
                player.stop();
                DownloadService.sendRemoveDownload(
                        this, MediaDownloadService.class, item.id, false);
                library.remove(item.id);
                renderLibrary();
            });
        }
    }

    private void playItem(LibraryItem item) {
        MediaItem.Builder builder = new MediaItem.Builder().setUri(item.url);
        if (item.mimeType != null) builder.setMimeType(item.mimeType);
        player.setMediaItem(builder.build());
        player.prepare();
        player.play();
        Toast.makeText(this, item.title, Toast.LENGTH_SHORT).show();
    }

    private LinearLayout cardContainer() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(10));
        box.setLayoutParams(lp);
        box.setBackgroundResource(android.R.drawable.dialog_holo_light_frame);
        return box;
    }

    private TextView text(String value, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(getColor(bold ? R.color.black : R.color.muted));
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        t.setPadding(0, dp(3), 0, dp(3));
        return t;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String stableId(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 12; i++) b.append(String.format(Locale.US, "%02x", digest[i]));
            return b.toString();
        } catch (Exception e) {
            return Integer.toHexString(text.hashCode());
        }
    }

    private static String humanBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.KOREA, "%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.KOREA, "%.1f MB", mb);
        return String.format(Locale.KOREA, "%.2f GB", mb / 1024.0);
    }

    private static String labelForMime(String mime) {
        if (mime == null) return "MEDIA";
        if (mime.contains("mpegurl")) return "HLS";
        if (mime.contains("dash")) return "DASH";
        if (mime.startsWith("audio")) return "AUDIO";
        if (mime.startsWith("video")) return "VIDEO";
        return mime;
    }

    private static String abbreviate(String text, int max) {
        if (text.length() <= max) return text;
        return text.substring(0, max - 1) + "…";
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(progressPoller);
        if (downloadManager != null) downloadManager.removeListener(this);
        if (probeWebView != null) {
            probeWebView.stopLoading();
            probeWebView.destroy();
        }
        if (player != null) player.release();
        super.onDestroy();
    }

    private static final class DownloadCard {
        final Button button;
        final ProgressBar progress;
        final TextView text;
        DownloadCard(Button button, ProgressBar progress, TextView text) {
            this.button = button;
            this.progress = progress;
            this.text = text;
        }
    }
}
