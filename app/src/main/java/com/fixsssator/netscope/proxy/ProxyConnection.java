package com.fixsssator.netscope.proxy;

import android.content.Context;

import com.fixsssator.netscope.data.Exchange;
import com.fixsssator.netscope.data.TrafficStore;
import com.fixsssator.netscope.rules.RuleEngine;
import com.fixsssator.netscope.util.Headers;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Handles one client connection: plain HTTP proxying, CONNECT tunnels, TLS interception (MITM)
 * and WebSocket upgrades. Every request is forwarded upstream on its own connection (HTTP/1.1).
 */
final class ProxyConnection implements Runnable {
    /** Hosts whose TLS handshake failed (certificate pinning / CA not trusted) are tunnelled untouched afterwards. */
    private static final Set<String> TLS_BYPASS = ConcurrentHashMap.newKeySet();

    private static final Set<String> HOP_BY_HOP = new HashSet<>(Arrays.asList(
            "connection", "proxy-connection", "keep-alive", "proxy-authorization", "proxy-authenticate",
            "te", "trailer", "upgrade", "transfer-encoding", "content-length", "expect", "accept-encoding"));

    private static final byte[] CONNECT_OK =
            "HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);
    private static final byte[] CONTINUE = "HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);

    static void resetBypass() {
        TLS_BYPASS.clear();
    }

    private final Context ctx;
    private final Socket client;
    private final TrafficStore store = TrafficStore.get();
    private final RuleEngine rules;
    private final CertManager certs;
    private Socket io;
    private String appPkg;
    private String appLabel;

    ProxyConnection(Context ctx, Socket client) {
        this.ctx = ctx;
        this.client = client;
        this.io = client;
        this.rules = RuleEngine.get(ctx);
        this.certs = CertManager.get(ctx);
    }

    @Override
    public void run() {
        try {
            client.setSoTimeout(120_000);
            client.setTcpNoDelay(true);
            String[] app = AppResolver.resolve(ctx, client);
            appPkg = app[0];
            appLabel = app[1];
            InputStream in = new BufferedInputStream(client.getInputStream(), 16384);
            OutputStream out = new BufferedOutputStream(client.getOutputStream(), 16384);
            while (true) {
                HttpIO.RequestHead rh;
                try {
                    rh = HttpIO.readRequestHead(in);
                } catch (IOException e) {
                    return;
                }
                if (rh == null) return;
                if ("CONNECT".equalsIgnoreCase(rh.method)) {
                    handleConnect(rh, in, out);
                    return;
                }
                if (!handleRequest(rh, in, out, null, null, 0)) return;
            }
        } catch (Exception ignored) {
            // connection-level failure: just drop the client
        } finally {
            closeQuietly(io);
            closeQuietly(client);
        }
    }

    // ------------------------------------------------------------------ CONNECT

    private void handleConnect(HttpIO.RequestHead rh, InputStream in, OutputStream out) throws IOException {
        String[] hp = splitHostPort(rh.target, 443);
        String host = hp[0];
        int port = Integer.parseInt(hp[1]);
        boolean mitmEnabled = ctx.getSharedPreferences("netscope", Context.MODE_PRIVATE).getBoolean("mitm", true);

        SSLSocketFactory factory = null;
        if (mitmEnabled && certs.isReady() && !TLS_BYPASS.contains(host + ":" + port)) {
            try {
                factory = certs.factoryFor(host);
            } catch (Exception e) {
                factory = null;
            }
        }
        if (factory == null) {
            tunnel(host, port, in, out);
            return;
        }

        out.write(CONNECT_OK);
        out.flush();
        SSLSocket ssl;
        try {
            ssl = (SSLSocket) factory.createSocket(client, host, port, true);
            ssl.setUseClientMode(false);
            ssl.setSoTimeout(120_000);
            ssl.startHandshake();
        } catch (Exception e) {
            TLS_BYPASS.add(host + ":" + port);
            Exchange ex = store.create();
            ex.method = "CONNECT";
            ex.scheme = "https";
            ex.host = host;
            ex.port = port;
            ex.path = "";
            ex.appPackage = appPkg;
            ex.appLabel = appLabel;
            ex.error = "TLS handshake failed: the client rejected the NetScope certificate "
                    + "(certificate pinning, or the NetScope CA is not trusted by this app). "
                    + "Further connections to this host are tunnelled without decryption.";
            ex.state = Exchange.State.ERROR;
            ex.durationMs = System.currentTimeMillis() - ex.startMs;
            store.changed();
            return;
        }
        io = ssl;
        InputStream tin = new BufferedInputStream(ssl.getInputStream(), 16384);
        OutputStream tout = new BufferedOutputStream(ssl.getOutputStream(), 16384);
        while (true) {
            HttpIO.RequestHead r2;
            try {
                r2 = HttpIO.readRequestHead(tin);
            } catch (IOException e) {
                break;
            }
            if (r2 == null) break;
            if (!handleRequest(r2, tin, tout, "https", host, port)) break;
        }
    }

    /** Blind TCP tunnel for hosts we cannot or must not decrypt. */
    private void tunnel(String host, int port, InputStream in, OutputStream out) {
        Exchange e = store.create();
        e.method = "CONNECT";
        e.scheme = "https";
        e.host = host;
        e.port = port;
        e.path = "";
        e.appPackage = appPkg;
        e.appLabel = appLabel;
        e.note = "Tunnel - not decrypted";
        Socket up = new Socket();
        try {
            up.connect(new InetSocketAddress(host, port), 15000);
            up.setTcpNoDelay(true);
            e.remoteIp = up.getInetAddress().getHostAddress();
            client.setSoTimeout(0);
            up.setSoTimeout(0);
            out.write(CONNECT_OK);
            out.flush();
            AtomicLong sent = new AtomicLong();
            AtomicLong received = new AtomicLong();
            OutputStream upOut = up.getOutputStream();
            Thread t = new Thread(() -> {
                pump(in, upOut, sent);
                closeQuietly(up);
                closeQuietly(client);
            }, "netscope-tunnel-up");
            t.setDaemon(true);
            t.start();
            pump(up.getInputStream(), out, received);
            e.reqBodySize = sent.get();
            e.respBodySize = received.get();
            e.status = 200;
            e.statusText = "Tunnel";
            e.state = Exchange.State.DONE;
        } catch (IOException ex) {
            e.error = describe(ex);
            e.state = Exchange.State.ERROR;
        } finally {
            closeQuietly(up);
            e.durationMs = System.currentTimeMillis() - e.startMs;
            store.changed();
        }
    }

    // ------------------------------------------------------------------ one HTTP request

    /** @return true if the client connection may be reused for another request. */
    private boolean handleRequest(HttpIO.RequestHead rh, InputStream in, OutputStream out,
                                  String tScheme, String tHost, int tPort) throws IOException {
        String scheme;
        String host;
        String path;
        int port;
        if (tScheme != null) {
            scheme = tScheme;
            host = tHost;
            port = tPort;
            path = rh.target;
        } else {
            Target t = parseAbsolute(rh.target);
            if (t == null) {
                String hh = Headers.get(rh.headers, "Host");
                if (hh == null) {
                    writeSimple(out, 400, "Bad Request", "NetScope: missing Host header");
                    return false;
                }
                String[] hp = splitHostPort(hh, 80);
                t = new Target("http", hp[0], Integer.parseInt(hp[1]), rh.target);
            }
            scheme = t.scheme;
            host = t.host;
            port = t.port;
            path = t.path;
        }

        Exchange e = store.create();
        e.method = rh.method;
        e.scheme = scheme;
        e.host = host;
        e.port = port;
        e.path = path;
        e.httpVersion = rh.version;
        e.intercepted = tScheme != null;
        e.appPackage = appPkg;
        e.appLabel = appLabel;
        for (String[] h : rh.headers) e.reqHeaders.add(h.clone());

        boolean clientClose = wantsClose(rh);
        boolean ws = isWebSocket(rh.headers);
        Socket up = null;
        boolean headSent = false;
        try {
            if (Headers.containsToken(Headers.get(rh.headers, "Expect"), "100-continue")) {
                out.write(CONTINUE);
                out.flush();
            }
            byte[] body = HttpIO.readRequestBody(in, rh.headers);
            e.reqBodySize = body.length;
            e.reqBodyTruncated = body.length > Exchange.BODY_LIMIT;
            e.reqBody = e.reqBodyTruncated ? Arrays.copyOf(body, Exchange.BODY_LIMIT) : body;
            store.changed();

            String url = e.url();
            RuleEngine.Rule action = rules.findAction(url);
            if (action != null && action.type == RuleEngine.Type.BLOCK) {
                respondLocal(out, e, 403, "Forbidden", "text/plain; charset=utf-8",
                        "Blocked by a NetScope rule".getBytes(StandardCharsets.UTF_8), clientClose);
                e.state = Exchange.State.BLOCKED;
                e.note = "Blocked by rule: " + action.pattern;
                finish(e);
                return !clientClose;
            }
            if (action != null && action.type == RuleEngine.Type.MAP_LOCAL) {
                String v = action.value == null ? "" : action.value;
                String trimmed = v.trim();
                String ct = (trimmed.startsWith("{") || trimmed.startsWith("["))
                        ? "application/json; charset=utf-8" : "text/plain; charset=utf-8";
                respondLocal(out, e, 200, "OK", ct, v.getBytes(StandardCharsets.UTF_8), clientClose);
                e.state = Exchange.State.MOCKED;
                e.note = "Mapped to local response by rule: " + action.pattern;
                finish(e);
                return !clientClose;
            }

            String upScheme = scheme;
            String upHost = host;
            int upPort = port;
            String upPath = path;
            if (action != null && action.type == RuleEngine.Type.MAP_REMOTE) {
                Target mapped = parseAbsolute(action.value == null ? "" : action.value.trim());
                if (mapped != null) {
                    upScheme = mapped.scheme;
                    upHost = mapped.host;
                    upPort = mapped.port;
                    upPath = mapped.path;
                    e.note = "Mapped to " + action.value;
                }
            }

            List<String[]> hdrs = new ArrayList<>();
            for (String[] h : rh.headers) {
                String n = h[0].toLowerCase(Locale.ROOT);
                if (HOP_BY_HOP.contains(n) || n.equals("host")) continue;
                hdrs.add(h.clone());
            }
            rules.applyHeaders(RuleEngine.Type.REQUEST_HEADER, url, hdrs);

            up = connectUpstream(upScheme, upHost, upPort, e);
            OutputStream uo = new BufferedOutputStream(up.getOutputStream(), 16384);
            StringBuilder sb = new StringBuilder(512);
            sb.append(rh.method).append(' ').append(upPath).append(" HTTP/1.1\r\n");
            sb.append("Host: ").append(hostHeader(upScheme, upHost, upPort)).append("\r\n");
            for (String[] h : hdrs) sb.append(h[0]).append(": ").append(h[1]).append("\r\n");
            if (!ws && Headers.get(rh.headers, "Accept-Encoding") != null) {
                // Only encodings we can decode for display.
                sb.append("Accept-Encoding: gzip, deflate\r\n");
            }
            if (ws) sb.append("Connection: Upgrade\r\nUpgrade: websocket\r\n");
            else sb.append("Connection: close\r\n");
            if (body.length > 0 || needsBody(rh.method)) sb.append("Content-Length: ").append(body.length).append("\r\n");
            sb.append("\r\n");
            uo.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
            if (body.length > 0) uo.write(body);
            uo.flush();

            InputStream ui = new BufferedInputStream(up.getInputStream(), 16384);
            HttpIO.StatusHead sh;
            do {
                sh = HttpIO.readStatusHead(ui);
            } while (sh.status >= 100 && sh.status < 200 && sh.status != 101);

            e.status = sh.status;
            e.statusText = sh.text;
            for (String[] h : sh.headers) e.respHeaders.add(h.clone());
            List<String[]> rhdrs = new ArrayList<>();
            for (String[] h : sh.headers) rhdrs.add(h.clone());
            rules.applyHeaders(RuleEngine.Type.RESPONSE_HEADER, url, rhdrs);
            store.changed();

            if (sh.status == 101) {
                StringBuilder hb = new StringBuilder("HTTP/1.1 101 ").append(sh.text).append("\r\n");
                for (String[] h : rhdrs) {
                    String n = h[0].toLowerCase(Locale.ROOT);
                    if (n.equals("connection") || n.equals("keep-alive")) continue;
                    hb.append(h[0]).append(": ").append(h[1]).append("\r\n");
                }
                hb.append("Connection: Upgrade\r\n\r\n");
                out.write(hb.toString().getBytes(StandardCharsets.ISO_8859_1));
                out.flush();
                headSent = true;
                e.websocket = true;
                e.note = "WebSocket - frames are relayed, not recorded";
                finish(e);
                relayStreams(in, out, ui, up);
                return false;
            }

            boolean noBody = "HEAD".equalsIgnoreCase(rh.method) || sh.status == 204 || sh.status == 304;
            boolean hasLength = Headers.get(sh.headers, "Content-Length") != null;
            boolean chunked = Headers.containsToken(Headers.get(sh.headers, "Transfer-Encoding"), "chunked");
            boolean eofDelimited = !noBody && !hasLength && !chunked;
            boolean keep = !clientClose && !eofDelimited;

            StringBuilder hb = new StringBuilder("HTTP/1.1 ").append(sh.status).append(' ').append(sh.text).append("\r\n");
            for (String[] h : rhdrs) {
                String n = h[0].toLowerCase(Locale.ROOT);
                if (n.equals("connection") || n.equals("keep-alive") || n.equals("proxy-connection")
                        || n.equals("proxy-authenticate")) continue;
                hb.append(h[0]).append(": ").append(h[1]).append("\r\n");
            }
            if (!keep) hb.append("Connection: close\r\n");
            hb.append("\r\n");
            out.write(hb.toString().getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            headSent = true;

            ByteArrayOutputStream capture = new ByteArrayOutputStream();
            boolean[] eof = new boolean[1];
            long total = HttpIO.relayBody(ui, out, sh.headers, noBody, capture, eof);
            e.respBody = capture.toByteArray();
            e.respBodySize = total;
            e.respBodyTruncated = total > e.respBody.length;
            e.state = Exchange.State.DONE;
            finish(e);
            return keep;
        } catch (Exception ex) {
            e.state = Exchange.State.ERROR;
            e.error = describe(ex);
            finish(e);
            if (!headSent) {
                try {
                    writeSimple(out, 502, "Bad Gateway", "NetScope could not complete the request: " + e.error);
                } catch (IOException ignored) {
                    // client already gone
                }
            }
            return false;
        } finally {
            closeQuietly(up);
        }
    }

    private void finish(Exchange e) {
        e.durationMs = System.currentTimeMillis() - e.startMs;
        if (e.state == Exchange.State.PENDING) e.state = Exchange.State.DONE;
        store.changed();
    }

    /** Sends a response generated by NetScope itself (blocked / mapped local). */
    private void respondLocal(OutputStream out, Exchange e, int code, String text, String contentType,
                              byte[] body, boolean close) throws IOException {
        StringBuilder sb = new StringBuilder("HTTP/1.1 ").append(code).append(' ').append(text).append("\r\n");
        sb.append("Content-Type: ").append(contentType).append("\r\n");
        sb.append("Content-Length: ").append(body.length).append("\r\n");
        sb.append("Access-Control-Allow-Origin: *\r\n");
        sb.append("Connection: ").append(close ? "close" : "keep-alive").append("\r\n\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
        e.status = code;
        e.statusText = text;
        e.respHeaders.add(new String[]{"Content-Type", contentType});
        e.respHeaders.add(new String[]{"Content-Length", String.valueOf(body.length)});
        e.respBody = body;
        e.respBodySize = body.length;
    }

    private static void writeSimple(OutputStream out, int code, String text, String message) throws IOException {
        byte[] body = message.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + code + " " + text + "\r\nContent-Type: text/plain; charset=utf-8\r\n"
                + "Content-Length: " + body.length + "\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    // ------------------------------------------------------------------ upstream

    private Socket connectUpstream(String scheme, String host, int port, Exchange e) throws IOException {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), 15000);
            s.setTcpNoDelay(true);
            s.setSoTimeout(60_000);
            if (s.getInetAddress() != null) e.remoteIp = s.getInetAddress().getHostAddress();
            if (!"https".equals(scheme)) return s;
            SSLSocket ssl = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                    .createSocket(s, host, port, true);
            SSLParameters p = ssl.getSSLParameters();
            if (!isIp(host)) p.setServerNames(Collections.singletonList(new SNIHostName(host)));
            // Verify the server certificate against the host name (not done by default on raw SSLSockets).
            p.setEndpointIdentificationAlgorithm("HTTPS");
            ssl.setSSLParameters(p);
            ssl.setSoTimeout(60_000);
            ssl.startHandshake();
            return ssl;
        } catch (IOException ex) {
            closeQuietly(s);
            throw ex;
        }
    }

    private void relayStreams(InputStream clientIn, OutputStream clientOut, InputStream upIn, Socket up) {
        try {
            io.setSoTimeout(0);
            up.setSoTimeout(0);
            OutputStream upOut = up.getOutputStream();
            Thread t = new Thread(() -> {
                pump(clientIn, upOut, null);
                closeQuietly(up);
            }, "netscope-ws-up");
            t.setDaemon(true);
            t.start();
            pump(upIn, clientOut, null);
        } catch (IOException ignored) {
            // socket closed while switching to relay mode
        }
        closeQuietly(up);
    }

    private static void pump(InputStream in, OutputStream out, AtomicLong counter) {
        byte[] buf = new byte[16384];
        try {
            int r;
            while ((r = in.read(buf)) != -1) {
                out.write(buf, 0, r);
                out.flush();
                if (counter != null) counter.addAndGet(r);
            }
        } catch (IOException ignored) {
            // one side closed
        }
    }

    // ------------------------------------------------------------------ small helpers

    private static final class Target {
        final String scheme;
        final String host;
        final int port;
        final String path;

        Target(String scheme, String host, int port, String path) {
            this.scheme = scheme;
            this.host = host;
            this.port = port;
            this.path = path;
        }
    }

    /** Parses "http(s)://host[:port]/path?query" or returns null when the string is not an absolute URL. */
    private static Target parseAbsolute(String url) {
        String low = url.toLowerCase(Locale.ROOT);
        boolean https = low.startsWith("https://");
        if (!https && !low.startsWith("http://")) return null;
        String rest = url.substring(https ? 8 : 7);
        int cut = -1;
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == '/' || c == '?' || c == '#') {
                cut = i;
                break;
            }
        }
        String authority = cut < 0 ? rest : rest.substring(0, cut);
        String path = cut < 0 ? "/" : rest.substring(cut);
        if (path.startsWith("?")) path = "/" + path;
        int hash = path.indexOf('#');
        if (hash >= 0) path = path.substring(0, hash);
        int at = authority.lastIndexOf('@');
        if (at >= 0) authority = authority.substring(at + 1);
        if (authority.isEmpty()) return null;
        String[] hp = splitHostPort(authority, https ? 443 : 80);
        return new Target(https ? "https" : "http", hp[0], Integer.parseInt(hp[1]), path);
    }

    static String[] splitHostPort(String s, int defaultPort) {
        String host = s;
        int port = defaultPort;
        if (s.startsWith("[")) {
            int end = s.indexOf(']');
            if (end > 0) {
                host = s.substring(1, end);
                if (s.length() > end + 2 && s.charAt(end + 1) == ':') port = parsePort(s.substring(end + 2), defaultPort);
            }
        } else {
            int c = s.lastIndexOf(':');
            if (c > 0 && s.indexOf(':') == c) {
                host = s.substring(0, c);
                port = parsePort(s.substring(c + 1), defaultPort);
            }
        }
        return new String[]{host, String.valueOf(port)};
    }

    private static int parsePort(String s, int fallback) {
        try {
            int p = Integer.parseInt(s.trim());
            return p > 0 && p < 65536 ? p : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String hostHeader(String scheme, String host, int port) {
        String h = host.indexOf(':') >= 0 ? "[" + host + "]" : host;
        boolean def = ("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443);
        return def ? h : h + ":" + port;
    }

    private static boolean isIp(String host) {
        return host.matches("\\d{1,3}(\\.\\d{1,3}){3}") || host.indexOf(':') >= 0;
    }

    private static boolean wantsClose(HttpIO.RequestHead rh) {
        String c = Headers.get(rh.headers, "Connection");
        if (Headers.containsToken(c, "close")) return true;
        return rh.version.equalsIgnoreCase("HTTP/1.0") && !Headers.containsToken(c, "keep-alive");
    }

    private static boolean isWebSocket(List<String[]> headers) {
        return Headers.containsToken(Headers.get(headers, "Upgrade"), "websocket");
    }

    private static boolean needsBody(String method) {
        return method.equalsIgnoreCase("POST") || method.equalsIgnoreCase("PUT") || method.equalsIgnoreCase("PATCH");
    }

    private static String describe(Exception ex) {
        String m = ex.getMessage();
        String name = ex.getClass().getSimpleName();
        return m == null || m.isEmpty() ? name : name + ": " + m;
    }

    private static void closeQuietly(Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException ignored) {
            // nothing to do
        }
    }
}
