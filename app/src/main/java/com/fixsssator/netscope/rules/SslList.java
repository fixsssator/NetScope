package com.fixsssator.netscope.rules;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

/** "SSL proxying" list (like Reqable): which hosts NetScope decrypts. Patterns: example.com or *.example.com */
public final class SslList {
    public enum Mode { ALL, ONLY_LISTED, EXCEPT_LISTED }

    private SslList() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("netscope_ssl", Context.MODE_PRIVATE);
    }

    public static Mode mode(Context c) {
        try {
            return Mode.valueOf(prefs(c).getString("mode", Mode.ALL.name()));
        } catch (IllegalArgumentException e) {
            return Mode.ALL;
        }
    }

    public static String patterns(Context c) {
        return prefs(c).getString("patterns", "");
    }

    public static void save(Context c, Mode mode, String patterns) {
        prefs(c).edit().putString("mode", mode.name()).putString("patterns", patterns).apply();
    }

    public static boolean shouldDecrypt(Context c, String host) {
        Mode m = mode(c);
        if (m == Mode.ALL) return true;
        boolean listed = matches(patterns(c), host);
        return m == Mode.ONLY_LISTED ? listed : !listed;
    }

    static boolean matches(String patterns, String host) {
        String h = host.toLowerCase(Locale.ROOT);
        for (String line : patterns.split("\\n")) {
            String p = line.trim().toLowerCase(Locale.ROOT);
            if (p.isEmpty()) continue;
            if (p.startsWith("*.")) {
                if (h.equals(p.substring(2)) || h.endsWith(p.substring(1))) return true;
            } else if (h.equals(p)) {
                return true;
            }
        }
        return false;
    }
}
