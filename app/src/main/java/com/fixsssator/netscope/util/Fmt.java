package com.fixsssator.netscope.util;

import java.util.Locale;

public final class Fmt {
    private Fmt() {}

    public static String size(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.US, "%.1f MB", bytes / 1048576.0);
    }

    public static String duration(long ms) {
        if (ms < 0) return "…";
        if (ms < 1000) return ms + " ms";
        return String.format(Locale.US, "%.2f s", ms / 1000.0);
    }
}
