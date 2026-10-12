package com.fixsssator.netscope.util;

import android.util.Base64;

import com.fixsssator.netscope.data.Exchange;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Builds a HAR 1.2 document that can be opened in Chrome DevTools, Reqable, Charles, Fiddler, ... */
public final class HarExporter {
    private HarExporter() {}

    public static String build(List<Exchange> list, String appVersion) throws JSONException {
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US);
        JSONArray entries = new JSONArray();
        for (Exchange e : list) {
            if (e.isTunnel() || e.state == Exchange.State.PENDING || e.status == 0) continue;
            JSONObject entry = new JSONObject();
            entry.put("startedDateTime", iso.format(new Date(e.startMs)));
            entry.put("time", Math.max(0, e.durationMs));
            entry.put("request", request(e));
            entry.put("response", response(e));
            entry.put("cache", new JSONObject());
            JSONObject timings = new JSONObject();
            timings.put("send", 0);
            timings.put("wait", Math.max(0, e.durationMs));
            timings.put("receive", 0);
            entry.put("timings", timings);
            if (e.remoteIp != null) entry.put("serverIPAddress", e.remoteIp);
            entries.put(entry);
        }
        JSONObject creator = new JSONObject();
        creator.put("name", "NetScope");
        creator.put("version", appVersion);
        JSONObject log = new JSONObject();
        log.put("version", "1.2");
        log.put("creator", creator);
        log.put("entries", entries);
        JSONObject root = new JSONObject();
        root.put("log", log);
        return root.toString(2);
    }

    private static JSONObject request(Exchange e) throws JSONException {
        JSONObject r = new JSONObject();
        r.put("method", e.method);
        r.put("url", e.url());
        r.put("httpVersion", e.httpVersion);
        r.put("cookies", new JSONArray());
        r.put("headers", headers(e.reqHeaders));
        r.put("queryString", query(e.path));
        r.put("headersSize", -1);
        r.put("bodySize", e.reqBodySize);
        if (e.reqBodySize > 0) {
            byte[] data = BodyUtil.decoded(e.reqBody, e.reqHeaders);
            JSONObject post = new JSONObject();
            String ct = Headers.get(e.reqHeaders, "Content-Type");
            post.put("mimeType", ct == null ? "" : ct);
            String text = BodyUtil.text(data, e.reqHeaders);
            post.put("text", text != null ? text : Base64.encodeToString(data, Base64.NO_WRAP));
            r.put("postData", post);
        }
        return r;
    }

    private static JSONObject response(Exchange e) throws JSONException {
        JSONObject r = new JSONObject();
        r.put("status", e.status);
        r.put("statusText", e.statusText);
        r.put("httpVersion", "HTTP/1.1");
        r.put("cookies", new JSONArray());
        r.put("headers", headers(e.respHeaders));
        String loc = Headers.get(e.respHeaders, "Location");
        r.put("redirectURL", loc == null ? "" : loc);
        r.put("headersSize", -1);
        r.put("bodySize", e.respBodySize);
        byte[] data = BodyUtil.decoded(e.respBody, e.respHeaders);
        JSONObject content = new JSONObject();
        content.put("size", data.length);
        String ct = Headers.get(e.respHeaders, "Content-Type");
        content.put("mimeType", ct == null ? "" : ct);
        if (data.length > 0) {
            String text = BodyUtil.text(data, e.respHeaders);
            if (text != null) {
                content.put("text", text);
            } else {
                content.put("text", Base64.encodeToString(data, Base64.NO_WRAP));
                content.put("encoding", "base64");
            }
        }
        r.put("content", content);
        return r;
    }

    private static JSONArray headers(List<String[]> list) throws JSONException {
        JSONArray arr = new JSONArray();
        for (String[] h : list) {
            JSONObject o = new JSONObject();
            o.put("name", h[0]);
            o.put("value", h[1]);
            arr.put(o);
        }
        return arr;
    }

    private static JSONArray query(String path) throws JSONException {
        JSONArray arr = new JSONArray();
        int q = path.indexOf('?');
        if (q < 0) return arr;
        for (String pair : path.substring(q + 1).split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            JSONObject o = new JSONObject();
            o.put("name", eq < 0 ? pair : pair.substring(0, eq));
            o.put("value", eq < 0 ? "" : pair.substring(eq + 1));
            arr.put(o);
        }
        return arr;
    }
}
