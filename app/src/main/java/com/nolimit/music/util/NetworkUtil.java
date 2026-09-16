package com.nolimit.music.util;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

public final class NetworkUtil {
    private NetworkUtil() {}

    public static boolean isWifiConnected(Context context) {
        NetworkCapabilities caps = activeCapabilities(context);
        return caps != null
                && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    public static boolean isCellularConnected(Context context) {
        NetworkCapabilities caps = activeCapabilities(context);
        return caps != null
                && caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    public static boolean isInternetConnected(Context context) {
        NetworkCapabilities caps = activeCapabilities(context);
        return caps != null
                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    public static boolean canDownload(Context context, boolean allowMobileData) {
        if (isWifiConnected(context)) return true;
        return allowMobileData && isInternetConnected(context);
    }

    private static NetworkCapabilities activeCapabilities(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return null;
        Network active = cm.getActiveNetwork();
        if (active == null) return null;
        return cm.getNetworkCapabilities(active);
    }
}
