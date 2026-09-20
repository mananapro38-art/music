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
 * The SDK is initialized once, then an interstitial is explicitly preloaded
 * while MainActivity is visible. Ads are only displayed at a user-driven
 * natural break and are frequency capped locally.
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
            // Current Start.io SDK initialization API. Return Ads stay disabled;
            // No Limit Music uses only manually triggered standard interstitials.
            StartAppSDK.initParams(app.getApplicationContext(), appId)
                    .setReturnAdsEnabled(false)
                    .setCallback(() -> {
                        sdkInitialized = true;
                        lastStatus = "SDK 초기화 완료";
                        Activity activity = activityRef.get();
                        if (activity != null) {
                            activity.runOnUiThread(() -> prepare(activity));
                        }
                    })
                    .init();
            lastStatus = "SDK 초기화 중";
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

        activityRef = new WeakReference<>(activity);
        if (!sdkInitialized || loading || ready) return;

        loading = true;
        lastStatus = "광고 로딩 중";

        final StartAppAd candidate = new StartAppAd(activity);
        interstitialAd = candidate;
        try {
            candidate.loadAd(new AdEventListener() {
                @Override
                public void onReceiveAd(@NonNull Ad ad) {
                    loading = false;
                    ready = true;
                    lastStatus = "광고 준비됨";
                    Log.i(TAG, "Start.io interstitial ready");
                }

                @Override
                public void onFailedToReceiveAd(@Nullable Ad ad) {
                    loading = false;
                    ready = false;
                    String error = ad == null ? "" : ad.getErrorMessage();
                    lastStatus = "광고 로드 실패" + (error == null || error.trim().isEmpty() ? "" : " · " + error.trim());
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
     * Call only after a genuine user action such as successfully enqueueing
     * a new music save. Returns true only when a preloaded ad was displayed.
     */
    public synchronized boolean onNaturalBreak(Activity activity) {
        if (!isConfigured() || activity == null || activity.isFinishing()) return false;
        if (Build.VERSION.SDK_INT >= 17 && activity.isDestroyed()) return false;

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
        return m.length() > 100 ? m.substring(0, 100) + "…" : m;
    }
}
