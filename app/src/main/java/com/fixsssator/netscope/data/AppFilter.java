package com.fixsssator.netscope.data;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

/** Which apps NetScope captures: all, only the selected ones, or all except the selected ones. */
public final class AppFilter {
    public enum Mode { ALL, ONLY, EXCLUDE }

    private static final String PREFS = "netscope_apps";

    private AppFilter() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static Mode mode(Context c) {
        try {
            return Mode.valueOf(prefs(c).getString("mode", Mode.ALL.name()));
        } catch (IllegalArgumentException e) {
            return Mode.ALL;
        }
    }

    public static void setMode(Context c, Mode mode) {
        prefs(c).edit().putString("mode", mode.name()).apply();
    }

    public static Set<String> selected(Context c) {
        return new HashSet<>(prefs(c).getStringSet("selected", new HashSet<>()));
    }

    public static void setSelected(Context c, Set<String> packages) {
        prefs(c).edit().putStringSet("selected", new HashSet<>(packages)).apply();
    }

    public static String summary(Context c) {
        int n = selected(c).size();
        switch (mode(c)) {
            case ONLY:
                return n == 0 ? "all apps" : n + (n == 1 ? " app" : " apps");
            case EXCLUDE:
                return n == 0 ? "all apps" : "all except " + n;
            default:
                return "all apps";
        }
    }
}
