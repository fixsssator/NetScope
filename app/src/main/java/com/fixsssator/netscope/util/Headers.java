package com.fixsssator.netscope.util;

import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/** Helpers for header lists stored as {name, value} pairs (order and duplicates preserved). */
public final class Headers {
    private Headers() {}

    public static String get(List<String[]> headers, String name) {
        for (String[] h : headers) {
            if (h[0].equalsIgnoreCase(name)) return h[1];
        }
        return null;
    }

    public static boolean has(List<String[]> headers, String name) {
        return get(headers, name) != null;
    }

    public static void remove(List<String[]> headers, String name) {
        for (Iterator<String[]> it = headers.iterator(); it.hasNext(); ) {
            if (it.next()[0].equalsIgnoreCase(name)) it.remove();
        }
    }

    /** Replaces the first header with this name (dropping duplicates) or appends a new one. */
    public static void set(List<String[]> headers, String name, String value) {
        boolean done = false;
        for (Iterator<String[]> it = headers.iterator(); it.hasNext(); ) {
            String[] h = it.next();
            if (h[0].equalsIgnoreCase(name)) {
                if (!done) {
                    h[1] = value;
                    done = true;
                } else {
                    it.remove();
                }
            }
        }
        if (!done) headers.add(new String[]{name, value});
    }

    public static boolean containsToken(String value, String token) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(token.toLowerCase(Locale.ROOT));
    }
}
