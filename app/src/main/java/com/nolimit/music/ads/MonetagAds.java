package com.nolimit.music.ads;

import android.app.Activity;
import android.app.Application;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import com.nolimit.music.BuildConfig;

import co.notix.interstitial.InterstitialData;
import co.notix.interstitial.InterstitialLoader;
import co.notix.interstitial.NotixInterstitial;

/**
 * Small Monetag/Notix wrapper for direct-APK monetization.
 *
 * The SDK stays completely dormant when MONETAG_INTERSTITIAL_ZONE_ID is 0.
 * A single loader is reused for the process and ads are only attempted at a
 * user-driven natural break. Frequency is capped locally to avoid hammering
 * the user or the ad network.
 */
public final class MonetagAds {
    private static final String TAG = "MonetagAds";
    private static final String PREFS = "monetag_ads";
    private static final String KEY_ACTION_COUNT = "eligible_action_count";
    private static final String KEY_LAST_SHOWN_AT = "last_shown_at";
    private static final int ACTIONS_PER_AD = 3;
    private static final long MIN_INTERVAL_MS = 4L * 60L * 1000L;

    private final SharedPreferences prefs;
    private final InterstitialLoader loader;
    private final boolean configured;

    public MonetagAds(Application app) {
        prefs = app.getSharedPreferences(PREFS, Application.MODE_PRIVATE);
        long zoneId = BuildConfig.MONETAG_INTERSTITIAL_ZONE_ID;
        InterstitialLoader created = null;
        boolean ready = false;
        if (zoneId > 0L) {
            try {
                created = NotixInterstitial.Companion.createLoader(zoneId);
                created.startLoading();
                ready = true;
            } catch (Throwable t) {
                Log.w(TAG, "Monetag loader initialization failed", t);
            }
        }
        loader = created;
        configured = ready;
    }

    public boolean isConfigured() {
        return configured;
    }

    /**
     * Call after a real user action such as successfully enqueueing a download.
     * Returns true only when an ad was actually handed to the SDK for display.
     */
    public boolean onNaturalBreak(Activity activity) {
        if (!configured || loader == null || activity == null || activity.isFinishing()) return false;
        if (Build.VERSION.SDK_INT >= 17 && activity.isDestroyed()) return false;

        int actions = prefs.getInt(KEY_ACTION_COUNT, 0) + 1;
        prefs.edit().putInt(KEY_ACTION_COUNT, actions).apply();
        if (actions < ACTIONS_PER_AD) return false;

        long now = System.currentTimeMillis();
        long last = prefs.getLong(KEY_LAST_SHOWN_AT, 0L);
        if (now - last < MIN_INTERVAL_MS) return false;

        try {
            if (!loader.hasNext()) return false;
            InterstitialData ad = loader.getNext();
            if (ad == null) return false;
            NotixInterstitial.Companion.show(ad);
            prefs.edit()
                    .putInt(KEY_ACTION_COUNT, 0)
                    .putLong(KEY_LAST_SHOWN_AT, now)
                    .apply();
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "Monetag interstitial show failed", t);
            return false;
        }
    }
}
