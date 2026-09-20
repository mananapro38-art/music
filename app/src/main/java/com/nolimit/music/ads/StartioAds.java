package com.nolimit.music.ads;

import android.app.Activity;
import android.app.Application;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.nolimit.music.BuildConfig;
import com.startapp.sdk.adsbase.Ad;
import com.startapp.sdk.adsbase.StartAppAd;
import com.startapp.sdk.adsbase.StartAppSDK;
import com.startapp.sdk.adsbase.adlisteners.AdEventListener;

import java.lang.ref.WeakReference;

/**
 * Start.io interstitial wrapper for the directly distributed No Limit Music APK.
 *
 * v1.6.7 is a diagnostic build: Start.io test ads are enabled and one test
 * interstitial is auto-shown once per process after a successful preload.
 * Production frequency caps remain available through onNaturalBreak().
 */
public final class StartioAds {
    private static final String TAG = "StartioAds";
    private static final String PREFS = "startio_ads";
    private static final String KEY_ACTION_COUNT = "eligible_action_count";
    private static final String KEY_LAST_SHOWN_AT = "last_shown_at";
    private static final int ACTIONS_PER_AD = 3;
    private static final long MIN_INTERVAL_MS = 4L * 60L * 1000L;

    private final SharedPreferences prefs;
    private final String appId;

    private volatile boolean sdkInitialized;
    private volatile boolean loading;
    private volatile boolean ready;
    private volatile boolean diagnosticShownThisProcess;
    private volatile boolean diagnosticShowPending;
    private volatile String lastStatus = "초기화 대기";
    private volatile StartAppAd interstitialAd;
    private volatile WeakReference<Activity> activityRef = new WeakReference<>(null);

    public StartioAds(Application app) {
        prefs = app.getSharedPreferences(PREFS, Application.MODE_PRIVATE);
        appId = BuildConfig.STARTIO_APP_ID == null ? "" : BuildConfig.STARTIO_APP_ID.trim();

        if (appId.isEmpty() || "0".equals(appId)) {
            lastStatus = "App ID 없음";
            return;
        }

        try {
            StartAppSDK.setTestAdsEnabled(BuildConfig.STARTIO_TEST_MODE);
            lastStatus = BuildConfig.STARTIO_TEST_MODE ? "TEST MODE · SDK 초기화 중" : "SDK 초기화 중";

            StartAppSDK.initParams(app.getApplicationContext(), appId)
                    .setReturnAdsEnabled(false)
                    .setCallback(() -> {
                        sdkInitialized = true;
                        lastStatus = BuildConfig.STARTIO_TEST_MODE
                                ? "TEST MODE · SDK 초기화 완료"
                                : "SDK 초기화 완료";
                        Activity activity = activityRef.get();
                        if (activity != null) {
                            activity.runOnUiThread(() -> prepare(activity));
                        }
                    })
                    .init();
        } catch (Throwable t) {
            lastStatus = "초기화 실패 · " + compact(t);
            Log.w(TAG, "Start.io SDK initialization failed", t);
        }
    }

    public boolean isConfigured() {
        return !appId.isEmpty() && !"0".equals(appId);
    }

    public boolean isReady() {
        return ready && interstitialAd != null;
    }

    public String getStatus() {
        return lastStatus;
    }

    /**
     * Preload a standard interstitial for the currently visible activity.
     * Safe to call repeatedly from onActivityResumed().
     */
    public synchronized void prepare(Activity activity) {
        if (!isConfigured() || activity == null || activity.isFinishing()) return;
        if (Build.VERSION.SDK_INT >= 17 && activity.isDestroyed()) return;
        if (AdRemovalManager.isAdFree(activity)) {
            lastStatus = "광고 제거 활성";
            return;
        }

        activityRef = new WeakReference<>(activity);
        if (!sdkInitialized || loading || ready) return;

        loading = true;
        lastStatus = BuildConfig.STARTIO_TEST_MODE ? "TEST MODE · 광고 로딩 중" : "광고 로딩 중";

        final StartAppAd candidate = new StartAppAd(activity);
        interstitialAd = candidate;
        try {
            candidate.loadAd(new AdEventListener() {
                @Override
                public void onReceiveAd(@NonNull Ad ad) {
                    loading = false;
                    ready = true;
                    lastStatus = BuildConfig.STARTIO_TEST_MODE
                            ? "TEST MODE · 광고 준비됨"
                            : "광고 준비됨";
                    Log.i(TAG, "Start.io interstitial ready");

                    Activity current = activityRef.get();
                    boolean shouldAutoShow = BuildConfig.STARTIO_DIAGNOSTIC_AUTOSHOW
                            && !diagnosticShownThisProcess;
                    if ((diagnosticShowPending || shouldAutoShow) && current != null) {
                        diagnosticShowPending = false;
                        current.runOnUiThread(() -> showDiagnosticAd(current));
                    }
                }

                @Override
                public void onFailedToReceiveAd(@Nullable Ad ad) {
                    loading = false;
                    ready = false;
                    String error = ad == null ? "" : ad.getErrorMessage();
                    lastStatus = "광고 로드 실패"
                            + (error == null || error.trim().isEmpty() ? "" : " · " + error.trim());
                    Log.w(TAG, lastStatus);
                }
            });
        } catch (Throwable t) {
            loading = false;
            ready = false;
            lastStatus = "광고 로드 예외 · " + compact(t);
            Log.w(TAG, "Start.io interstitial load failed", t);
        }
    }

    /**
     * Diagnostic path: bypasses frequency caps and asks for a Start.io TEST ad.
     * If the ad is not ready yet, it will be shown immediately after load succeeds.
     */
    public synchronized boolean showDiagnosticAd(Activity activity) {
        if (!isConfigured() || activity == null || activity.isFinishing()) return false;
        if (Build.VERSION.SDK_INT >= 17 && activity.isDestroyed()) return false;
        if (AdRemovalManager.isAdFree(activity)) {
            lastStatus = "광고 제거 활성";
            return false;
        }
        activityRef = new WeakReference<>(activity);

        if (!ready || interstitialAd == null) {
            diagnosticShowPending = true;
            lastStatus = loading
                    ? "TEST MODE · 로딩 완료 후 자동 표시"
                    : "TEST MODE · 테스트 광고 요청";
            prepare(activity);
            return false;
        }

        try {
            boolean shown = interstitialAd.showAd();
            if (shown) {
                diagnosticShownThisProcess = true;
                diagnosticShowPending = false;
                lastStatus = "TEST MODE · 광고 표시 성공";
                ready = false;
                loading = false;
                interstitialAd = null;
            } else {
                ready = false;
                interstitialAd = null;
                lastStatus = "TEST MODE · showAd=false";
            }
            return shown;
        } catch (Throwable t) {
            ready = false;
            loading = false;
            interstitialAd = null;
            lastStatus = "TEST MODE · 표시 예외 · " + compact(t);
            Log.w(TAG, "Start.io diagnostic show failed", t);
            return false;
        }
    }

    /**
     * Production path used after a genuine user save action.
     */
    public synchronized boolean onNaturalBreak(Activity activity) {
        if (!isConfigured() || activity == null || activity.isFinishing()) return false;
        if (Build.VERSION.SDK_INT >= 17 && activity.isDestroyed()) return false;
        if (AdRemovalManager.isAdFree(activity)) {
            lastStatus = "광고 제거 활성";
            return false;
        }

        activityRef = new WeakReference<>(activity);

        int actions = prefs.getInt(KEY_ACTION_COUNT, 0) + 1;
        prefs.edit().putInt(KEY_ACTION_COUNT, actions).apply();
        if (actions < ACTIONS_PER_AD) {
            if (!ready) prepare(activity);
            return false;
        }

        long now = System.currentTimeMillis();
        long last = prefs.getLong(KEY_LAST_SHOWN_AT, 0L);
        if (now - last < MIN_INTERVAL_MS) {
            if (!ready) prepare(activity);
            return false;
        }

        StartAppAd ad = interstitialAd;
        if (!ready || ad == null) {
            lastStatus = loading ? "광고 로딩 중" : "광고 미준비 · 재로딩";
            prepare(activity);
            return false;
        }

        try {
            boolean shown = ad.showAd();
            if (shown) {
                prefs.edit()
                        .putInt(KEY_ACTION_COUNT, 0)
                        .putLong(KEY_LAST_SHOWN_AT, now)
                        .apply();
                ready = false;
                loading = false;
                interstitialAd = null;
                lastStatus = "광고 표시됨 · 다음 광고 준비 대기";
            } else {
                ready = false;
                interstitialAd = null;
                lastStatus = "표시 실패 · 다시 로딩";
                prepare(activity);
            }
            return shown;
        } catch (Throwable t) {
            ready = false;
            loading = false;
            interstitialAd = null;
            lastStatus = "표시 예외 · " + compact(t);
            Log.w(TAG, "Start.io interstitial show failed", t);
            prepare(activity);
            return false;
        }
    }

    private static String compact(Throwable t) {
        if (t == null) return "unknown";
        String m = t.getMessage();
        if (m == null || m.trim().isEmpty()) return t.getClass().getSimpleName();
        m = m.replace('\n', ' ').replace('\r', ' ').trim();
        return m.length() > 120 ? m.substring(0, 120) + "…" : m;
    }
}
