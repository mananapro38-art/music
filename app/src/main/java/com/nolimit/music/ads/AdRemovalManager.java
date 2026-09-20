package com.nolimit.music.ads;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

/**
 * Local 30-day ad-removal pass.
 *
 * The code is validated on-device. This is intended as a lightweight promo-code
 * mechanism for the directly distributed APK, not as a purchase or entitlement
 * server. Re-entering a valid code starts a new 30-day period from that moment.
 */
public final class AdRemovalManager {
    private static final String PREFS = "ad_removal";
    private static final String KEY_UNTIL = "ad_free_until";
    private static final long DAY_MS = 24L * 60L * 60L * 1000L;
    private static final long PASS_MS = 30L * DAY_MS;

    // Split to avoid casually exposing the full code as one searchable literal.
    private static final String CODE = "NLM30-" + "V7Q9" + "-" + "K2P4";

    private AdRemovalManager() {}

    public static boolean redeem(Context context, String enteredCode) {
        if (context == null || !CODE.equals(normalize(enteredCode))) return false;
        long until = System.currentTimeMillis() + PASS_MS;
        prefs(context).edit().putLong(KEY_UNTIL, until).apply();
        return true;
    }

    public static boolean isAdFree(Context context) {
        if (context == null) return false;
        long until = prefs(context).getLong(KEY_UNTIL, 0L);
        if (until <= System.currentTimeMillis()) {
            if (until != 0L) prefs(context).edit().remove(KEY_UNTIL).apply();
            return false;
        }
        return true;
    }

    public static long getExpiryMillis(Context context) {
        return isAdFree(context) ? prefs(context).getLong(KEY_UNTIL, 0L) : 0L;
    }

    public static int remainingDays(Context context) {
        long remaining = getExpiryMillis(context) - System.currentTimeMillis();
        if (remaining <= 0L) return 0;
        return (int) Math.max(1L, (remaining + DAY_MS - 1L) / DAY_MS);
    }

    public static String statusLabel(Context context) {
        int days = remainingDays(context);
        return days > 0 ? "광고 제거 · " + days + "일 남음" : "광고 제거 코드 입력";
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
