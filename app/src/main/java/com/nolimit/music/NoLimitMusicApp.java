package com.nolimit.music;

import android.Manifest;
import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.widget.NestedScrollView;

import com.google.android.material.materialswitch.MaterialSwitch;
import com.nolimit.music.data.AutoBackupManager;
import com.nolimit.music.data.DownloadQueueManager;
import com.nolimit.music.ui.LiquidGlassOverlayView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import eightbitlab.com.blurview.BlurTarget;
import eightbitlab.com.blurview.BlurView;

public final class NoLimitMusicApp extends Application implements Application.ActivityLifecycleCallbacks {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private int startedActivities = 0;

    @Override public void onCreate() {
        super.onCreate();
        registerActivityLifecycleCallbacks(this);
    }

    @Override public void onActivityResumed(Activity activity) {
        if (!(activity instanceof MainActivity)) return;
        View playerBar = activity.findViewById(R.id.playerBar);
        if (playerBar != null) {
            playerBar.setBackgroundResource(R.drawable.bg_glass_panel);
            playerBar.setOnClickListener(v -> activity.startActivity(new Intent(activity, PlayerActivity.class)));
        }
        setupGlassChrome(activity);
        injectHomeActions(activity);
        collapseHomePreview(activity);
        injectAdvancedSettings(activity);
        requestV1Permissions(activity);
        DownloadQueueManager.get(activity).kick();
    }

    private void setupGlassChrome(Activity activity) {
        TextView home = activity.findViewById(R.id.iconHome);
        TextView search = activity.findViewById(R.id.iconSearch);
        TextView library = activity.findViewById(R.id.iconPlaylist);
        TextView settings = activity.findViewById(R.id.iconSettings);
        if (home != null) home.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_home, 0, 0, 0);
        if (search != null) search.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0);
        if (library != null) library.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_library, 0, 0, 0);
        if (settings != null) settings.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_settings, 0, 0, 0);

        TextView searchButton = activity.findViewById(R.id.btnSearch);
        if (searchButton != null) {
            searchButton.setText("");
            searchButton.setGravity(Gravity.CENTER);
            searchButton.setPadding(0, 0, 0, 0);
            searchButton.setBackgroundResource(android.R.color.transparent);
            searchButton.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0);
        }

        BlurTarget target = activity.findViewById(R.id.blurTarget);
        BlurView bottom = activity.findViewById(R.id.bottomBlur);
        View mini = activity.findViewById(R.id.playerBar);
        if (target != null) {
            configureBlur(activity, target, bottom, R.drawable.bg_bottom_nav, 22f);
            if (mini instanceof BlurView) configureBlur(activity, target, (BlurView) mini, R.drawable.bg_glass_panel, 20f);
        }

        LiquidGlassOverlayView bottomOverlay = activity.findViewById(R.id.liquidBottomOverlay);
        LiquidGlassOverlayView playerOverlay = activity.findViewById(R.id.liquidPlayerOverlay);
        bindLiquidTouch(activity.findViewById(R.id.tabHome), bottomOverlay, activity.findViewById(R.id.bottomBlur));
        bindLiquidTouch(activity.findViewById(R.id.tabSearch), bottomOverlay, activity.findViewById(R.id.bottomBlur));
        bindLiquidTouch(activity.findViewById(R.id.tabPlaylist), bottomOverlay, activity.findViewById(R.id.bottomBlur));
        bindLiquidTouch(activity.findViewById(R.id.tabSettings), bottomOverlay, activity.findViewById(R.id.bottomBlur));
        bindLiquidTouch(mini, playerOverlay, mini);
    }

    private void configureBlur(Activity activity, BlurTarget target, BlurView blur, int fallbackBackground, float radius) {
        if (blur == null || blur.getTag() != null) return;
        try {
            blur.setupWith(target)
                    .setFrameClearDrawable(activity.getWindow().getDecorView().getBackground())
                    .setBlurRadius(radius);
            blur.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
            blur.setClipToOutline(true);
            blur.setTag("configured");
        } catch (Throwable ignored) {
            blur.setBackgroundResource(fallbackBackground);
        }
    }

    private void bindLiquidTouch(View target, LiquidGlassOverlayView overlay, View glassRoot) {
        if (target == null || overlay == null || glassRoot == null) return;
        target.setOnTouchListener((v, event) -> {
            float x = v.getX() + event.getX();
            float y = v.getY() + event.getY();
            if (v == glassRoot) { x = event.getX(); y = event.getY(); }
            int action = event.getActionMasked();
            overlay.setTouchHighlight(x, y, action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE);
            return false;
        });
    }

    private void injectHomeActions(Activity activity) {
        NestedScrollView home = activity.findViewById(R.id.sectionHome);
        if (home == null || home.getChildCount() == 0 || !(home.getChildAt(0) instanceof LinearLayout)) return;
        LinearLayout container = (LinearLayout) home.getChildAt(0);
        if (container.findViewWithTag("v13_home_actions") != null) return;

        LinearLayout row = new LinearLayout(activity);
        row.setTag("v13_home_actions");
        row.setOrientation(LinearLayout.HORIZONTAL);

        TextView recognize = actionCard(activity, "노래 찾기", R.drawable.ic_waveform);
        recognize.setOnClickListener(v -> activity.startActivity(new Intent(activity, MusicRecognitionActivity.class)));
        LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, dp(activity, 50), 1f);
        left.setMarginEnd(dp(activity, 3));
        row.addView(recognize, left);

        TextView more = actionCard(activity, "보관함 · 더보기", R.drawable.ic_library);
        more.setOnClickListener(v -> showMoreSheet(activity));
        LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, dp(activity, 58), 1f);
        right.setMarginStart(dp(activity, 3));
        row.addView(more, right);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 50));
        lp.topMargin = dp(activity, 8);
        container.addView(row, Math.min(2, container.getChildCount()), lp);
    }

    private void showMoreSheet(Activity activity) {
        String[] items = {"라이브러리+", "캡처에서 플레이리스트 가져오기", "노래 듣고 찾기", "설정"};
        new AlertDialog.Builder(activity)
                .setTitle("더보기")
                .setItems(items, (dialog, which) -> {
                    if (which == 0) activity.startActivity(new Intent(activity, FeatureHubActivity.class));
                    else if (which == 1) activity.startActivity(new Intent(activity, PlaylistScreenshotImportActivity.class));
                    else if (which == 2) activity.startActivity(new Intent(activity, MusicRecognitionActivity.class));
                    else {
                        View tab = activity.findViewById(R.id.tabSettings);
                        if (tab != null) tab.performClick();
                    }
                })
                .show();
    }

    private void collapseHomePreview(Activity activity) {
        View list = activity.findViewById(R.id.rvCharts);
        if (list != null) list.setVisibility(View.GONE);
        View status = activity.findViewById(R.id.tvChartStatus);
        if (status != null && status.getParent() instanceof View) {
            ((View) status.getParent()).setVisibility(View.GONE);
        }
    }

    private void requestV1Permissions(Activity activity) {
        SharedPreferences settings = activity.getSharedPreferences("settings", MODE_PRIVATE);
        if (settings.getBoolean("v1_permissions_requested", false)) return;
        List<String> missing = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                missing.add(Manifest.permission.POST_NOTIFICATIONS);
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED)
                missing.add(Manifest.permission.READ_MEDIA_AUDIO);
        } else if (Build.VERSION.SDK_INT >= 23 &&
                ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        }
        settings.edit().putBoolean("v1_permissions_requested", true).apply();
        if (!missing.isEmpty()) ActivityCompat.requestPermissions(activity, missing.toArray(new String[0]), 7010);
    }

    private void injectAdvancedSettings(Activity activity) {
        NestedScrollView scroll = activity.findViewById(R.id.sectionSettings);
        if (scroll == null || scroll.getChildCount() == 0 || !(scroll.getChildAt(0) instanceof LinearLayout)) return;
        LinearLayout container = (LinearLayout) scroll.getChildAt(0);
        if (container.findViewWithTag("v1_settings") != null) return;
        SharedPreferences settings = activity.getSharedPreferences("settings", MODE_PRIVATE);
        LinearLayout box = new LinearLayout(activity);
        box.setTag("v1_settings");
        box.setOrientation(LinearLayout.VERTICAL);
        TextView title = label(activity, "재생 · 라이브러리 확장", 16, true);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(-1, -2);
        titleLp.topMargin = dp(activity, 26);
        box.addView(title, titleLp);

        TextView recognition = card(activity, "노래 듣고 찾기");
        recognition.setOnClickListener(v -> activity.startActivity(new Intent(activity, MusicRecognitionActivity.class)));
        box.addView(recognition);

        TextView hub = card(activity, "라이브러리+ 열기");
        LinearLayout.LayoutParams hubLp = new LinearLayout.LayoutParams(-1, dp(activity, 52));
        hubLp.topMargin = dp(activity, 7);
        hub.setLayoutParams(hubLp);
        hub.setOnClickListener(v -> activity.startActivity(new Intent(activity, FeatureHubActivity.class)));
        box.addView(hub);

        MaterialSwitch smart = toggle(activity, "스마트 자동 이어듣기", settings.getBoolean("smart_continue", true));
        smart.setOnCheckedChangeListener((b, checked) -> settings.edit().putBoolean("smart_continue", checked).apply());
        box.addView(smart);

        MaterialSwitch fade = toggle(activity, "곡 사이 부드러운 페이드 (2초)", settings.getInt("smooth_transition_ms", 0) > 0);
        fade.setOnCheckedChangeListener((b, checked) -> settings.edit().putInt("smooth_transition_ms", checked ? 2000 : 0).apply());
        box.addView(fade);

        MaterialSwitch autoBackup = toggle(activity, "앱을 나갈 때 자동 백업", settings.getBoolean("auto_backup", true));
        autoBackup.setOnCheckedChangeListener((b, checked) -> settings.edit().putBoolean("auto_backup", checked).apply());
        box.addView(autoBackup);

        TextView backupHint = label(activity,
                "새 곡은 Music/No Limit Music에 보존되고, 라이브러리+에서 기존 곡도 일괄 보존할 수 있습니다.", 11, false);
        box.addView(backupHint);
        container.addView(box);
    }

    private static TextView actionCard(Activity a, String text, int icon) {
        TextView v = label(a, text, 13, true);
        v.setGravity(Gravity.CENTER_VERTICAL);
        v.setPadding(dp(a, 10), 0, dp(a, 9), 0);
        v.setCompoundDrawablePadding(dp(a, 6));
        v.setCompoundDrawablesWithIntrinsicBounds(icon, 0, 0, 0);
        v.setBackgroundResource(R.drawable.bg_glass_panel);
        return v;
    }

    private static MaterialSwitch toggle(Activity activity, String text, boolean checked) {
        MaterialSwitch s = new MaterialSwitch(activity);
        s.setText(text);
        s.setChecked(checked);
        s.setTextColor(ContextCompat.getColor(activity, R.color.text_primary));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(activity, 52));
        lp.topMargin = dp(activity, 5);
        s.setLayoutParams(lp);
        return s;
    }

    private static TextView card(Activity a, String text) {
        TextView v = label(a, text, 13, true);
        v.setGravity(Gravity.CENTER_VERTICAL);
        v.setPadding(dp(a, 16), dp(a, 8), dp(a, 16), dp(a, 8));
        v.setBackgroundResource(R.drawable.bg_smart_card);
        return v;
    }

    private static TextView label(Activity a, String text, int sp, boolean bold) {
        TextView v = new TextView(a);
        v.setText(text);
        v.setTextSize(sp);
        v.setTextColor(ContextCompat.getColor(a, bold ? R.color.text_primary : R.color.muted));
        if (bold) v.setTypeface(v.getTypeface(), android.graphics.Typeface.BOLD);
        return v;
    }

    private static int dp(Activity a, int n) {
        return Math.round(n * a.getResources().getDisplayMetrics().density);
    }

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
        io.execute(() -> {
            try { new AutoBackupManager(this).backupNow(); } catch (Exception ignored) { }
        });
    }

    @Override public void onActivityCreated(Activity activity, Bundle savedInstanceState) { }
    @Override public void onActivityPaused(Activity activity) { }
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) { }
    @Override public void onActivityDestroyed(Activity activity) { }
}
