package com.nolimit.music;

import android.app.Activity;
import android.app.Application;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.Uri;
import android.os.Build;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.widget.NestedScrollView;

import com.google.android.material.materialswitch.MaterialSwitch;
import com.nolimit.music.data.DownloadQueueManager;
import com.nolimit.music.data.DownloadTaskStore;
import com.nolimit.music.data.LibraryStore;
import com.nolimit.music.data.YoutubeRepository;
import com.nolimit.music.model.Track;
import com.nolimit.music.util.NetworkUtil;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class V12BootstrapProvider extends ContentProvider {
    @Override public boolean onCreate() {
        Context context = getContext();
        if (context == null) return true;
        registerNetworkResume(context.getApplicationContext());
        if (context.getApplicationContext() instanceof Application) {
            ((Application) context.getApplicationContext()).registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                @Override public void onActivityResumed(Activity activity) {
                    if (activity instanceof MainActivity) inject(activity);
                }
                @Override public void onActivityCreated(Activity a, android.os.Bundle b) { }
                @Override public void onActivityStarted(Activity a) { }
                @Override public void onActivityPaused(Activity a) { }
                @Override public void onActivityStopped(Activity a) { }
                @Override public void onActivitySaveInstanceState(Activity a, @NonNull android.os.Bundle b) { }
                @Override public void onActivityDestroyed(Activity a) { }
            });
        }
        return true;
    }

    private void registerNetworkResume(Context context) {
        if (Build.VERSION.SDK_INT < 24) return;
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return;
            cm.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(@NonNull Network network) {
                    DownloadQueueManager.get(context).kick();
                }
            });
        } catch (Exception ignored) { }
    }

    private void inject(Activity activity) {
        // v1.3 keeps the home surface intentionally sparse; legacy tools live under More/Settings.
        injectSettings(activity);
    }

    private void injectImportCard(Activity activity) {
        NestedScrollView home = activity.findViewById(R.id.sectionHome);
        if (home == null || home.getChildCount() == 0 || !(home.getChildAt(0) instanceof LinearLayout)) return;
        LinearLayout container = (LinearLayout) home.getChildAt(0);
        if (container.findViewWithTag("v12_playlist_import") != null) return;
        TextView card = card(activity, "▣  캡처에서 플레이리스트 가져오기\n스크린샷 OCR → 곡 자동 매칭 → 다운로드·플리 추가");
        card.setTag("v12_playlist_import");
        card.setOnClickListener(v -> activity.startActivity(new Intent(activity, PlaylistScreenshotImportActivity.class)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 78));
        lp.topMargin = dp(activity, 8);
        container.addView(card, Math.min(3, container.getChildCount()), lp);
    }

    private void injectSettings(Activity activity) {
        NestedScrollView scroll = activity.findViewById(R.id.sectionSettings);
        if (scroll == null || scroll.getChildCount() == 0 || !(scroll.getChildAt(0) instanceof LinearLayout)) return;
        LinearLayout container = (LinearLayout) scroll.getChildAt(0);
        if (container.findViewWithTag("v12_tools") != null) return;
        DownloadQueueManager queue = DownloadQueueManager.get(activity);
        LinearLayout box = new LinearLayout(activity);
        box.setTag("v12_tools"); box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams boxLp = new LinearLayout.LayoutParams(-1, -2); boxLp.topMargin = dp(activity, 22); box.setLayoutParams(boxLp);
        box.addView(label(activity, "v1.2 도구", 16, true));

        MaterialSwitch pause = new MaterialSwitch(activity);
        pause.setText("다운로드 대기열 일시정지 (현재 곡 완료 후)");
        pause.setTextColor(ContextCompat.getColor(activity, R.color.text_primary));
        pause.setChecked(queue.isPaused());
        pause.setOnCheckedChangeListener((b, checked) -> queue.setPaused(checked));
        LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(-1, dp(activity, 54)); pLp.topMargin = dp(activity, 4); box.addView(pause, pLp);

        TextView importCard = card(activity, "플레이리스트 스크린샷 가져오기");
        importCard.setOnClickListener(v -> activity.startActivity(new Intent(activity, PlaylistScreenshotImportActivity.class)));
        box.addView(importCard);

        TextView diag = card(activity, "진단 정보 복사\n버전 · 다운로드 상태 · 검색원 · 저장공간을 클립보드에 복사");
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-1, dp(activity, 70)); dlp.topMargin = dp(activity, 8); diag.setLayoutParams(dlp);
        diag.setOnClickListener(v -> copyDiagnostics(activity));
        box.addView(diag);
        container.addView(box);
    }

    private void copyDiagnostics(Activity activity) {
        StringBuilder s = new StringBuilder();
        try {
            android.content.pm.PackageInfo pi = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0);
            s.append("No Limit Music ").append(pi.versionName).append(" (").append(pi.getLongVersionCode()).append(")\n");
        } catch (Exception ignored) { }
        s.append("Android ").append(Build.VERSION.RELEASE).append(" / SDK ").append(Build.VERSION.SDK_INT).append('\n');
        s.append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        SharedPreferences settings = activity.getSharedPreferences(YoutubeRepository.SETTINGS_PREFS, Context.MODE_PRIVATE);
        s.append("Search source: ").append(settings.getString(YoutubeRepository.KEY_SEARCH_SOURCE, "music_first")).append('\n');
        String lastYtm = new YoutubeRepository(activity).getLastYtmError();
        if (lastYtm != null && !lastYtm.trim().isEmpty()) {
            s.append("Last YTM error: ").append(lastYtm.replace('\n', ' ').replace('\r', ' ')).append('\n');
        }
        boolean allowMobile = settings.getBoolean("allow_mobile_download", false);
        s.append("Mobile download: ").append(allowMobile).append('\n');
        s.append("Can download now: ").append(NetworkUtil.canDownload(activity, allowMobile)).append('\n');
        DownloadQueueManager queue = DownloadQueueManager.get(activity);
        s.append("Download paused: ").append(queue.isPaused()).append('\n');
        s.append("Free storage: ").append(String.format(Locale.ROOT, "%.1f GB", queue.freeBytes() / 1073741824d)).append('\n');
        List<DownloadTaskStore.Task> tasks = new DownloadTaskStore(activity).load();
        Map<String,Integer> states = new HashMap<>();
        for (DownloadTaskStore.Task t : tasks) states.put(t.state, states.getOrDefault(t.state, 0) + 1);
        s.append("Downloads: ").append(states).append('\n');
        List<Track> tracks = new LibraryStore(activity).load();
        s.append("Library tracks: ").append(tracks.size()).append('\n');
        Map<String,Integer> sources = new HashMap<>();
        for (Track t : tracks) {
            String source = t.sourceName == null || t.sourceName.isEmpty() ? "legacy" : t.sourceName;
            sources.put(source, sources.getOrDefault(source, 0) + 1);
        }
        s.append("Sources: ").append(sources).append('\n');
        ClipboardManager cb = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cb != null) cb.setPrimaryClip(ClipData.newPlainText("No Limit Music diagnostics", s.toString()));
        Toast.makeText(activity, "진단 정보를 복사했습니다.", Toast.LENGTH_SHORT).show();
    }

    private static TextView card(Activity a, String text) {
        TextView v = label(a, text, 13, true); v.setGravity(Gravity.CENTER_VERTICAL);
        v.setPadding(dp(a, 16), dp(a, 8), dp(a, 16), dp(a, 8)); v.setBackgroundResource(R.drawable.bg_smart_card); return v;
    }
    private static TextView label(Activity a, String text, int sp, boolean bold) {
        TextView v = new TextView(a); v.setText(text); v.setTextSize(sp); v.setTextColor(ContextCompat.getColor(a, bold ? R.color.text_primary : R.color.muted));
        if (bold) v.setTypeface(v.getTypeface(), android.graphics.Typeface.BOLD); return v;
    }
    private static int dp(Activity a, int n) { return Math.round(n * a.getResources().getDisplayMetrics().density); }

    @Nullable @Override public Cursor query(@NonNull Uri uri, @Nullable String[] projection, @Nullable String selection, @Nullable String[] selectionArgs, @Nullable String sortOrder) { return null; }
    @Nullable @Override public String getType(@NonNull Uri uri) { return null; }
    @Nullable @Override public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) { return null; }
    @Override public int delete(@NonNull Uri uri, @Nullable String selection, @Nullable String[] selectionArgs) { return 0; }
    @Override public int update(@NonNull Uri uri, @Nullable ContentValues values, @Nullable String selection, @Nullable String[] selectionArgs) { return 0; }
}
