package com.fixsssator.netscope.data;

import java.util.Locale;

/** Active list filters (status class, method, tunnels) plus the free-text query matcher. */
public final class TrafficFilter {
    public static final String[] STATUS_LABELS =
            {"2xx success", "3xx redirect", "4xx client error", "5xx server error", "Failed / blocked / pending"};
    public static final String[] METHOD_LABELS = {"GET", "POST", "PUT", "PATCH", "DELETE", "Other"};

    /** Nothing checked in a group means "no restriction" for that group. */
    public static final boolean[] status = new boolean[STATUS_LABELS.length];
    public static final boolean[] method = new boolean[METHOD_LABELS.length];
    public static boolean hideTunnels;

    private TrafficFilter() {}

    public static boolean isActive() {
        return any(status) || any(method) || hideTunnels;
    }

    public static void reset() {
        java.util.Arrays.fill(status, false);
        java.util.Arrays.fill(method, false);
        hideTunnels = false;
    }

    private static boolean any(boolean[] a) {
        for (boolean b : a) if (b) return true;
        return false;
    }

    public static boolean matches(Exchange e, String query) {
        if (hideTunnels && e.isTunnel()) return false;
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

    private static int statusIndex(Exchange e) {
        if (e.state == Exchange.State.ERROR || e.state == Exchange.State.BLOCKED
                || e.state == Exchange.State.PENDING || e.status == 0) return 4;
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
