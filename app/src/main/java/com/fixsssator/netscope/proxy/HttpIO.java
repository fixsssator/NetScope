package com.fixsssator.netscope.proxy;

import com.fixsssator.netscope.data.Exchange;
import com.fixsssator.netscope.util.Headers;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Minimal HTTP/1.x wire-format reader/writer used by the proxy. */
final class HttpIO {
    static final long MAX_REQUEST_BODY = 64L * 1024 * 1024;
    private static final int MAX_LINE = 65536;

    private HttpIO() {}

    static final class RequestHead {
        String method;
        String target;
        String version;
        List<String[]> headers;
    }

    static final class StatusHead {
        String version;
        int status;
        String text;
        List<String[]> headers;
    }

    /** Reads one line (CRLF or LF terminated, ISO-8859-1). Returns null on EOF before any byte. */
    static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder(128);
        boolean any = false;
        int c;
        while ((c = in.read()) != -1) {
            any = true;
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
            if (sb.length() > MAX_LINE) throw new IOException("header line too long");
        }
        return any ? sb.toString() : null;
    }

    static List<String[]> readHeaders(InputStream in) throws IOException {
        List<String[]> list = new ArrayList<>();
        String line;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            int i = line.indexOf(':');
            if (i <= 0) continue;
            list.add(new String[]{line.substring(0, i).trim(), line.substring(i + 1).trim()});
        }
        if (line == null) throw new EOFException("connection closed inside headers");
        return list;
    }

    /** Returns null when the peer closed the connection cleanly before sending a request. */
    static RequestHead readRequestHead(InputStream in) throws IOException {
        String line;
        do {
            line = readLine(in);
            if (line == null) return null;
        } while (line.isEmpty());
        String[] p = line.split(" ", 3);
        if (p.length < 3) throw new IOException("bad request line");
        RequestHead r = new RequestHead();
        r.method = p[0];
        r.target = p[1];
        r.version = p[2];
        r.headers = readHeaders(in);
        return r;
    }

    static StatusHead readStatusHead(InputStream in) throws IOException {
        String line = readLine(in);
        if (line == null) throw new EOFException("upstream closed the connection without a response");
        String[] p = line.split(" ", 3);
        if (p.length < 2 || !p[0].startsWith("HTTP/")) throw new IOException("bad status line: " + line);
        StatusHead s = new StatusHead();
        s.version = p[0];
        try {
            s.status = Integer.parseInt(p[1].trim());
        } catch (NumberFormatException e) {
            throw new IOException("bad status code: " + p[1]);
        }
        s.text = p.length > 2 ? p[2] : "";
        s.headers = readHeaders(in);
        return s;
    }

    /** Reads (and de-chunks) a request body completely into memory. */
    static byte[] readRequestBody(InputStream in, List<String[]> headers) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        if (Headers.containsToken(Headers.get(headers, "Transfer-Encoding"), "chunked")) {
            while (true) {
                String line = readLine(in);
                if (line == null) throw new EOFException();
                long size = parseChunkSize(line);
                if (size < 0) continue;
                if (size == 0) {
                    String t;
                    while ((t = readLine(in)) != null && !t.isEmpty()) { /* skip trailers */ }
                    break;
                }
                if (bos.size() + size > MAX_REQUEST_BODY) throw new IOException("request body too large");
                copyN(in, null, bos, Integer.MAX_VALUE, size);
                readLine(in); // CRLF after chunk data
            }
            return bos.toByteArray();
        }
        String cl = Headers.get(headers, "Content-Length");
        if (cl != null) {
            long n;
            try {
                n = Long.parseLong(cl.trim());
            } catch (NumberFormatException e) {
                throw new IOException("bad Content-Length");
            }
            if (n > MAX_REQUEST_BODY) throw new IOException("request body too large");
            copyN(in, null, bos, Integer.MAX_VALUE, n);
        }
        return bos.toByteArray();
    }

    /**
     * Streams a response body from upstream to the client using the framing announced by the upstream headers,
     * while keeping up to {@link Exchange#BODY_LIMIT} bytes for the UI.
     *
     * @param eof set to true when the body was terminated by connection close
     * @return decoded body size in bytes
     */
    static long relayBody(InputStream in, OutputStream out, List<String[]> headers, boolean noBody,
                          ByteArrayOutputStream capture, boolean[] eof) throws IOException {
        if (noBody) return 0;
        if (Headers.containsToken(Headers.get(headers, "Transfer-Encoding"), "chunked")) {
            long total = 0;
            while (true) {
                String line = readLine(in);
                if (line == null) throw new EOFException("upstream closed inside a chunked body");
                out.write((line + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
                long size = parseChunkSize(line);
                if (size < 0) continue;
                if (size == 0) {
                    String t;
                    while ((t = readLine(in)) != null) {
                        out.write((t + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
                        if (t.isEmpty()) break;
                    }
                    out.flush();
                    return total;
                }
                copyN(in, out, capture, Exchange.BODY_LIMIT, size);
                total += size;
                readLine(in); // CRLF after chunk data
                out.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));
                out.flush();
            }
        }
        String cl = Headers.get(headers, "Content-Length");
        if (cl != null) {
            long n;
            try {
                n = Long.parseLong(cl.trim());
            } catch (NumberFormatException e) {
                throw new IOException("bad Content-Length from upstream");
            }
            copyN(in, out, capture, Exchange.BODY_LIMIT, n);
            out.flush();
            return n;
        }
        eof[0] = true;
        byte[] buf = new byte[8192];
        long total = 0;
        int r;
        while ((r = in.read(buf)) != -1) {
            out.write(buf, 0, r);
            out.flush();
            if (capture.size() < Exchange.BODY_LIMIT) {
                capture.write(buf, 0, Math.min(r, Exchange.BODY_LIMIT - capture.size()));
            }
            total += r;
        }
        return total;
    }

    private static long parseChunkSize(String line) throws IOException {
        int semi = line.indexOf(';');
        String s = (semi >= 0 ? line.substring(0, semi) : line).trim();
        if (s.isEmpty()) return -1;
        try {
            return Long.parseLong(s.toLowerCase(Locale.ROOT), 16);
        } catch (NumberFormatException e) {
            throw new IOException("bad chunk size: " + s);
        }
    }

    /** Copies exactly n bytes; optionally forwards them to sink and keeps up to captureLimit bytes. */
    static void copyN(InputStream in, OutputStream sink, ByteArrayOutputStream capture, int captureLimit, long n)
            throws IOException {
        byte[] buf = new byte[8192];
        long left = n;
        while (left > 0) {
            int r = in.read(buf, 0, (int) Math.min(buf.length, left));
            if (r < 0) throw new EOFException("unexpected end of body");
            if (sink != null) {
                sink.write(buf, 0, r);
                sink.flush();
            }
            if (capture != null && capture.size() < captureLimit) {
                capture.write(buf, 0, (int) Math.min(r, (long) captureLimit - capture.size()));
            }
            left -= r;
        }
    }
}
