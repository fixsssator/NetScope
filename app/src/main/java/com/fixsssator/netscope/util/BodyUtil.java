package com.fixsssator.netscope.util;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/** Decoding and pretty-printing of captured HTTP bodies. */
public final class BodyUtil {
    private static final int MAX_CHARS = 300_000;

    private BodyUtil() {}

    /** Removes gzip/deflate content-encoding (returns partial data if the body was truncated). */
    public static byte[] decoded(byte[] body, List<String[]> headers) {
        String enc = Headers.get(headers, "Content-Encoding");
        if (enc == null || body.length == 0) return body;
        enc = enc.toLowerCase(Locale.ROOT).trim();
        try {
            if (enc.equals("gzip") || enc.equals("x-gzip")) {
                return readAll(new GZIPInputStream(new ByteArrayInputStream(body)));
            }
            if (enc.equals("deflate")) {
                return readAll(new InflaterInputStream(new ByteArrayInputStream(body)));
            }
        } catch (IOException ignored) {
            // not really compressed - fall through and show raw bytes
        }
        return body;
    }

    private static byte[] readAll(InputStream in) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        try {
            int r;
            while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
        } catch (IOException ignored) {
            // truncated stream: keep what we have
        }
        return out.toByteArray();
    }

    public static boolean isText(byte[] data, List<String[]> headers) {
        String ct = Headers.get(headers, "Content-Type");
        if (ct != null) {
            String c = ct.toLowerCase(Locale.ROOT);
            if (c.contains("text") || c.contains("json") || c.contains("xml") || c.contains("javascript")
                    || c.contains("html") || c.contains("x-www-form-urlencoded") || c.contains("graphql")
                    || c.contains("yaml") || c.contains("csv")) {
                return true;
            }
            if (c.startsWith("image/") || c.startsWith("video/") || c.startsWith("audio/")
                    || c.contains("octet-stream") || c.contains("zip") || c.contains("pdf")
                    || c.contains("protobuf") || c.contains("font")) {
                return false;
            }
        }
        int n = Math.min(data.length, 512);
        int bad = 0;
        for (int i = 0; i < n; i++) {
            int b = data[i] & 0xff;
            if (b == 0 || (b < 9) || (b > 13 && b < 32)) bad++;
        }
        return n > 0 && bad == 0;
    }

    public static Charset charset(List<String[]> headers) {
        String ct = Headers.get(headers, "Content-Type");
        if (ct != null) {
            int i = ct.toLowerCase(Locale.ROOT).indexOf("charset=");
            if (i >= 0) {
                String cs = ct.substring(i + 8).split(";")[0].trim().replace("\"", "");
                try {
                    return Charset.forName(cs);
                } catch (Exception ignored) {
                    // fall back to UTF-8
                }
            }
        }
        return StandardCharsets.UTF_8;
    }

    /** Plain text of a (decoded) body, or null if it is binary. */
    public static String text(byte[] data, List<String[]> headers) {
        if (!isText(data, headers)) return null;
        return new String(data, charset(headers));
    }

    /** Human readable body for the UI. */
    public static String render(byte[] body, long fullSize, boolean truncated, List<String[]> headers) {
        if (fullSize == 0) return "(empty body)";
        String enc = Headers.get(headers, "Content-Encoding");
        if (enc != null && enc.toLowerCase(Locale.ROOT).contains("br")) {
            return "[Brotli-encoded body (" + Fmt.size(fullSize) + ") - not decoded]";
        }
        byte[] data = decoded(body, headers);
        StringBuilder sb = new StringBuilder();
        if (isText(data, headers)) {
            String s = new String(data, charset(headers));
            String ct = Headers.get(headers, "Content-Type");
            String trimmed = s.trim();
            if (ct != null && ct.toLowerCase(Locale.ROOT).contains("x-www-form-urlencoded")) {
                s = prettyForm(s);
            } else if (s.length() < 1_000_000 && (trimmed.startsWith("{") || trimmed.startsWith("["))) {
                try {
                    s = trimmed.startsWith("{") ? new JSONObject(trimmed).toString(2) : new JSONArray(trimmed).toString(2);
                } catch (Exception ignored) {
                    // not valid JSON - show as is
                }
            }
            if (s.length() > MAX_CHARS) s = s.substring(0, MAX_CHARS) + "\n… (display truncated)";
            sb.append(s);
        } else {
            sb.append("[binary, ").append(Fmt.size(data.length)).append("]\n");
            int n = Math.min(data.length, 512);
            for (int i = 0; i < n; i += 16) {
                sb.append(String.format(Locale.US, "%04x  ", i));
                StringBuilder ascii = new StringBuilder();
                for (int j = i; j < i + 16; j++) {
                    if (j < n) {
                        int b = data[j] & 0xff;
                        sb.append(String.format(Locale.US, "%02x ", b));
                        ascii.append(b >= 32 && b < 127 ? (char) b : '.');
                    } else {
                        sb.append("   ");
                    }
                }
                sb.append(' ').append(ascii).append('\n');
            }
            if (data.length > n) sb.append("…\n");
        }
        if (truncated) {
            sb.append("\n\n… captured only the first ").append(Fmt.size(body.length))
                    .append(" of ").append(Fmt.size(fullSize));
        }
        return sb.toString();
    }

    private static String prettyForm(String s) {
        StringBuilder sb = new StringBuilder();
        for (String pair : s.split("&")) {
            int eq = pair.indexOf('=');
            try {
                String k = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), "UTF-8");
                String v = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
                sb.append(k).append(" = ").append(v).append('\n');
            } catch (Exception e) {
                sb.append(pair).append('\n');
            }
        }
        return sb.toString().trim();
    }
}
