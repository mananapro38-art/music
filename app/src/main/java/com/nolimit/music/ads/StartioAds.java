package com.nolimit.music.ads;

import android.app.Activity;
import android.app.Application;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import com.nolimit.music.BuildConfig;
import com.startapp.sdk.adsbase.StartAppAd;
import com.startapp.sdk.adsbase.StartAppSDK;

/**
 * Start.io interstitial wrapper for the directly distributed No Limit Music APK.
 *
 * Ads are never shown on app launch, app return, or automatic Activity changes.
 * They are attempted only after real user-driven save actions, with a local
 * frequency cap to keep monetization from interrupting normal music use.
 */
public final class StartioAds {
    private static final String TAG = "StartioAds";
    private static final String PREFS = "startio_ads";
    private static final String KEY_ACTION_COUNT = "eligible_action_count";
    private static final String KEY_LAST_SHOWN_AT = "last_shown_at";
    private static final int ACTIONS_PER_AD = 3;
    private static final long MIN_INTERVAL_MS = 4L * 60L * 1000L;

    private final SharedPreferences prefs;
    private final boolean configured;

    public StartioAds(Application app) {
        prefs = app.getSharedPreferences(PREFS, Application.MODE_PRIVATE);
        String appId = BuildConfig.STARTIO_APP_ID == null ? "" : BuildConfig.STARTIO_APP_ID.trim();
        boolean ready = false;
        if (!appId.isEmpty() && !"0".equals(appId)) {
            try {
                StartAppSDK.init(app, appId);
                ready = true;
            } catch (Throwable t) {
                Log.w(TAG, "Start.io SDK initialization failed", t);
            }
        }
        configured = ready;
    }

    public boolean isConfigured() {
        return configured;
    }

    /**
     * Call only after a genuine user action such as successfully enqueueing
     * a new music save. Returns true only when the SDK actually displayed an ad.
     */
    public boolean onNaturalBreak(Activity activity) {
        if (!configured || activity == null || activity.isFinishing()) return false;
        if (Build.VERSION.SDK_INT >= 17 && activity.isDestroyed()) return false;

        int actions = prefs.getInt(KEY_ACTION_COUNT, 0) + 1;
        prefs.edit().putInt(KEY_ACTION_COUNT, actions).apply();
        if (actions < ACTIONS_PER_AD) return false;

        long now = System.currentTimeMillis();
        long last = prefs.getLong(KEY_LAST_SHOWN_AT, 0L);
        if (now - last < MIN_INTERVAL_MS) return false;

        try {
            boolean shown = StartAppAd.showAd(activity);
            if (shown) {
                prefs.edit()
                        .putInt(KEY_ACTION_COUNT, 0)
                        .putLong(KEY_LAST_SHOWN_AT, now)
                        .apply();
            }
            return shown;
        } catch (Throwable t) {
            Log.w(TAG, "Start.io interstitial show failed", t);
            return false;
        }
    }
}
