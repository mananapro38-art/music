package com.nolimit.music.util;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

public final class NetworkUtil {
    private NetworkUtil() {}

    public static boolean isWifiConnected(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        Network active = cm.getActiveNetwork();
        if (active == null) return false;
        NetworkCapabilities caps = cm.getNetworkCapabilities(active);
        return caps != null
                && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }
}
