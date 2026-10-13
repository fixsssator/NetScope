package com.fixsssator.netscope.data;

import java.util.ArrayList;
import java.util.List;

/** One captured request/response pair (or a tunnel / failed TLS attempt). */
public class Exchange {
    public enum State { PENDING, PAUSED, DONE, ERROR, BLOCKED, MOCKED }

    /** Maximum number of body bytes kept in memory per direction. */
    public static final int BODY_LIMIT = 2 * 1024 * 1024;

    public final long id;
    public final long startMs;
    public volatile State state = State.PENDING;

    public String method = "";
    public String scheme = "http";
    public String host = "";
    public int port = 80;
    public String path = "/";
    public String httpVersion = "HTTP/1.1";

    public int status;
    public String statusText = "";
    public String error;
    public String note;
    public String remoteIp;
    public String appPackage;
    public String appLabel;

    public volatile boolean favorite;
    public boolean intercepted;
    public boolean websocket;
    public long durationMs = -1;

    public final List<String[]> reqHeaders = new ArrayList<>();
    public final List<String[]> respHeaders = new ArrayList<>();
    public byte[] reqBody = new byte[0];
    public byte[] respBody = new byte[0];
    public long reqBodySize;
    public long respBodySize;
    public boolean reqBodyTruncated;
    public boolean respBodyTruncated;

    public Exchange(long id, long startMs) {
        this.id = id;
        this.startMs = startMs;
    }

    public boolean isTunnel() {
        return "CONNECT".equals(method);
    }

    public String url() {
        StringBuilder sb = new StringBuilder(scheme).append("://");
        sb.append(host.indexOf(':') >= 0 ? "[" + host + "]" : host);
        boolean defaultPort = ("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443);
        if (!defaultPort) sb.append(':').append(port);
        sb.append(path);
        return sb.toString();
    }
}
