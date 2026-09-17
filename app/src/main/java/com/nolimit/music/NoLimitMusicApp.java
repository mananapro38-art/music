package com.nolimit.music;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.core.widget.NestedScrollView;

import com.google.android.material.materialswitch.MaterialSwitch;
import com.nolimit.music.data.AutoBackupManager;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class NoLimitMusicApp extends Application implements Application.ActivityLifecycleCallbacks {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private int startedActivities = 0;

    @Override public void onCreate() { super.onCreate(); registerActivityLifecycleCallbacks(this); }

    @Override public void onActivityResumed(Activity activity) {
        if (!(activity instanceof MainActivity)) return;
        View playerBar = activity.findViewById(R.id.playerBar);
        if (playerBar != null) playerBar.setOnClickListener(v -> activity.startActivity(new Intent(activity, PlayerActivity.class)));
        injectHomeHub(activity);
        injectAdvancedSettings(activity);
    }

    private void injectHomeHub(Activity activity) {
        NestedScrollView home = activity.findViewById(R.id.sectionHome);
        if (home == null || home.getChildCount() == 0 || !(home.getChildAt(0) instanceof LinearLayout)) return;
        LinearLayout container = (LinearLayout) home.getChildAt(0);
        if (container.findViewWithTag("v1_hub_card") != null) return;
        TextView card = card(activity, "▦  라이브러리+\n아티스트 · 앨범 · 태그 · 다운로드 · 기록 · 스마트 플리");
        card.setTag("v1_hub_card");
        card.setOnClickListener(v -> activity.startActivity(new Intent(activity, FeatureHubActivity.class)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 76));
        lp.topMargin = dp(activity, 12);
        container.addView(card, Math.min(2, container.getChildCount()), lp);
    }

    private void injectAdvancedSettings(Activity activity) {
        NestedScrollView scroll = activity.findViewById(R.id.sectionSettings);
        if (scroll == null || scroll.getChildCount() == 0 || !(scroll.getChildAt(0) instanceof LinearLayout)) return;
        LinearLayout container = (LinearLayout) scroll.getChildAt(0);
        if (container.findViewWithTag("v1_settings") != null) return;
        SharedPreferences settings = activity.getSharedPreferences("settings", MODE_PRIVATE);
        LinearLayout box = new LinearLayout(activity); box.setTag("v1_settings"); box.setOrientation(LinearLayout.VERTICAL);
        TextView title = label(activity, "재생 · 라이브러리 확장", 16, true); LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(-1, -2); titleLp.topMargin = dp(activity, 26); box.addView(title, titleLp);

        TextView hub = card(activity, "라이브러리+ 열기"); hub.setOnClickListener(v -> activity.startActivity(new Intent(activity, FeatureHubActivity.class))); box.addView(hub);
        MaterialSwitch smart = toggle(activity, "스마트 자동 이어듣기", settings.getBoolean("smart_continue", true));
        smart.setOnCheckedChangeListener((b, checked) -> settings.edit().putBoolean("smart_continue", checked).apply()); box.addView(smart);
        MaterialSwitch fade = toggle(activity, "곡 사이 부드러운 페이드 (2초)", settings.getInt("smooth_transition_ms", 0) > 0);
        fade.setOnCheckedChangeListener((b, checked) -> settings.edit().putInt("smooth_transition_ms", checked ? 2000 : 0).apply()); box.addView(fade);
        MaterialSwitch autoBackup = toggle(activity, "앱을 나갈 때 자동 백업", settings.getBoolean("auto_backup", true));
        autoBackup.setOnCheckedChangeListener((b, checked) -> settings.edit().putBoolean("auto_backup", checked).apply()); box.addView(autoBackup);
        TextView backupHint = label(activity, "자동 백업은 Downloads/No Limit Music에 저장됩니다. 앱을 삭제한 뒤에도 수동 복원 파일로 사용할 수 있습니다.", 11, false); box.addView(backupHint);
        container.addView(box);
    }

    private static MaterialSwitch toggle(Activity activity, String text, boolean checked) {
        MaterialSwitch s = new MaterialSwitch(activity); s.setText(text); s.setChecked(checked); s.setTextColor(ContextCompat.getColor(activity, R.color.text_primary));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(activity, 52)); lp.topMargin = dp(activity, 5); s.setLayoutParams(lp); return s;
    }

    private static TextView card(Activity a, String text) {
        TextView v = label(a, text, 13, true); v.setGravity(Gravity.CENTER_VERTICAL); v.setPadding(dp(a, 15), dp(a, 8), dp(a, 15), dp(a, 8)); v.setBackgroundResource(R.drawable.bg_smart_card); return v;
    }
    private static TextView label(Activity a, String text, int sp, boolean bold) {
        TextView v = new TextView(a); v.setText(text); v.setTextSize(sp); v.setTextColor(ContextCompat.getColor(a, bold ? R.color.text_primary : R.color.muted)); if (bold) v.setTypeface(v.getTypeface(), android.graphics.Typeface.BOLD); return v;
    }
    private static int dp(Activity a, int n) { return Math.round(n * a.getResources().getDisplayMetrics().density); }

    @Override public void onActivityStarted(Activity activity) { startedActivities++; }
    @Override public void onActivityStopped(Activity activity) {
        startedActivities = Math.max(0, startedActivities - 1);
        if (startedActivities == 0) maybeAutoBackup();
    }

    private void maybeAutoBackup() {
        SharedPreferences settings = getSharedPreferences("settings", MODE_PRIVATE);
        if (!settings.getBoolean("auto_backup", true) || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return;
        long last = settings.getLong("last_auto_backup", 0L);
        if (System.currentTimeMillis() - last < 5 * 60 * 1000L) return;
        io.execute(() -> { try { new AutoBackupManager(this).backupNow(); } catch (Exception ignored) { } });
    }

    @Override public void onActivityCreated(Activity activity, Bundle savedInstanceState) { }
    @Override public void onActivityPaused(Activity activity) { }
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) { }
    @Override public void onActivityDestroyed(Activity activity) { }
}
