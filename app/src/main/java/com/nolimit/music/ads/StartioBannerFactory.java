package com.nolimit.music.ads;

import android.app.Activity;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import com.startapp.sdk.ads.banner.Banner;
import com.startapp.sdk.adsbase.model.AdPreferences;

/**
 * Small helper for placing Start.io banners without duplicating them on Activity resume.
 * Each placement uses a distinct Ad Tag so Start.io portal reports can separate surfaces.
 */
public final class StartioBannerFactory {
    private static final String TAG_PREFIX = "startio_banner_host:";

    private StartioBannerFactory() {}

    public static View attach(Activity activity, LinearLayout parent, String placementTag, int index) {
        if (activity == null || parent == null || placementTag == null || placementTag.trim().isEmpty()) return null;

        String viewTag = TAG_PREFIX + placementTag;
        View existing = parent.findViewWithTag(viewTag);
        if (existing != null) return existing;

        FrameLayout host = new FrameLayout(activity);
        host.setTag(viewTag);
        host.setMinimumHeight(dp(activity, 54));
        host.setPadding(0, dp(activity, 4), 0, dp(activity, 4));

        AdPreferences prefs = new AdPreferences();
        prefs.setAdTag(placementTag);
        Banner banner = new Banner(activity, prefs);

        FrameLayout.LayoutParams bannerLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        host.addView(banner, bannerLp);

        LinearLayout.LayoutParams hostLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        hostLp.topMargin = dp(activity, 6);
        hostLp.bottomMargin = dp(activity, 4);

        int safeIndex = Math.max(0, Math.min(index, parent.getChildCount()));
        parent.addView(host, safeIndex, hostLp);
        return host;
    }

    public static View append(Activity activity, LinearLayout parent, String placementTag) {
        return attach(activity, parent, placementTag, parent == null ? 0 : parent.getChildCount());
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
