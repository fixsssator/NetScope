package com.fixsssator.netscope.data;

import com.fixsssator.netscope.util.Headers;

import java.util.Arrays;
import java.util.Locale;

/** Active list filters (type chip, status class, method, favorites, tunnels) plus the free-text matcher. */
public final class TrafficFilter {
    public static final String[] TYPE_LABELS =
            {"All", "JSON", "XML", "HTML", "JS", "CSS", "Image", "Media", "Font", "Other"};
    public static final String[] STATUS_LABELS =
            {"2xx success", "3xx redirect", "4xx client error", "5xx server error", "Failed / blocked / pending"};
    public static final String[] METHOD_LABELS = {"GET", "POST", "PUT", "PATCH", "DELETE", "Other"};

    /** Index into TYPE_LABELS; 0 = all types. */
    public static int type;
    /** Nothing checked in a group means "no restriction" for that group. */
    public static final boolean[] status = new boolean[STATUS_LABELS.length];
    public static final boolean[] method = new boolean[METHOD_LABELS.length];
    public static boolean hideTunnels;
    public static boolean favoritesOnly;

    private TrafficFilter() {}

    public static boolean isActive() {
        return type != 0 || any(status) || any(method) || hideTunnels || favoritesOnly;
    }

    public static void reset() {
        type = 0;
        Arrays.fill(status, false);
        Arrays.fill(method, false);
        hideTunnels = false;
        favoritesOnly = false;
    }

    private static boolean any(boolean[] a) {
        for (boolean b : a) if (b) return true;
        return false;
    }

    public static boolean matches(Exchange e, String query) {
        if (hideTunnels && e.isTunnel()) return false;
        if (favoritesOnly && !e.favorite) return false;
        if (type != 0 && typeIndex(e) != type) return false;
        if (any(status) && !status[statusIndex(e)]) return false;
        if (any(method) && !method[methodIndex(e)]) return false;
        if (query.isEmpty()) return true;
        String hay = (e.method + " " + e.status + " " + e.host + e.path + " "
                + (e.appLabel == null ? "" : e.appLabel)).toLowerCase(Locale.ROOT);
        for (String tok : query.split("\\s+")) {
            if (tok.matches("[1-5]xx")) {
                if (e.status / 100 != tok.charAt(0) - '0') return false;
            } else if (!hay.contains(tok)) {
                return false;
            }
        }
        return true;
    }

    /** Index into TYPE_LABELS based on the response Content-Type. */
    public static int typeIndex(Exchange e) {
        String ct = Headers.get(e.respHeaders, "Content-Type");
        if (ct == null) return 9;
        ct = ct.toLowerCase(Locale.ROOT);
        if (ct.startsWith("image/")) return 6;
        if (ct.startsWith("video/") || ct.startsWith("audio/")) return 7;
        if (ct.contains("font") || ct.contains("woff")) return 8;
        if (ct.contains("json")) return 1;
        if (ct.contains("html")) return 3;
        if (ct.contains("xml")) return 2;
        if (ct.contains("javascript") || ct.contains("ecmascript")) return 4;
        if (ct.contains("css")) return 5;
        return 9;
    }

    private static int statusIndex(Exchange e) {
        if (e.state == Exchange.State.ERROR || e.state == Exchange.State.BLOCKED
                || e.state == Exchange.State.PENDING || e.state == Exchange.State.PAUSED || e.status == 0) return 4;
        int c = e.status / 100;
        return c >= 2 && c <= 5 ? c - 2 : 4;
    }

    private static int methodIndex(Exchange e) {
        switch (e.method.toUpperCase(Locale.ROOT)) {
            case "GET":
                return 0;
            case "POST":
                return 1;
            case "PUT":
                return 2;
            case "PATCH":
                return 3;
            case "DELETE":
                return 4;
            default:
                return 5;
        }
    }
}
