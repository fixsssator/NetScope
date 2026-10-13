package com.fixsssator.netscope.util;

import com.fixsssator.netscope.data.Exchange;

import org.json.JSONObject;

import java.util.Locale;

/** "Copy as code" generators for captured requests. */
public final class CodeGen {
    private CodeGen() {}

    private static boolean skip(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.equals("host") || n.equals("content-length") || n.equals("connection")
                || n.equals("proxy-connection") || n.equals("proxy-authorization") || n.equals("accept-encoding");
    }

    private static String bodyText(Exchange e) {
        if (e.reqBodySize == 0) return null;
        return BodyUtil.text(BodyUtil.decoded(e.reqBody, e.reqHeaders), e.reqHeaders);
    }

    public static String python(Exchange e) {
        StringBuilder sb = new StringBuilder("import requests\n\nresponse = requests.request(\n");
        sb.append("    ").append(JSONObject.quote(e.method)).append(",\n");
        sb.append("    ").append(JSONObject.quote(e.url())).append(",\n");
        sb.append("    headers={\n");
        for (String[] h : e.reqHeaders) {
            if (skip(h[0])) continue;
            sb.append("        ").append(JSONObject.quote(h[0])).append(": ").append(JSONObject.quote(h[1])).append(",\n");
        }
        sb.append("    },\n");
        String body = bodyText(e);
        if (body != null) sb.append("    data=").append(JSONObject.quote(body)).append(".encode(\"utf-8\"),\n");
        sb.append(")\nprint(response.status_code)\nprint(response.text)\n");
        return sb.toString();
    }

    public static String fetch(Exchange e) {
        StringBuilder sb = new StringBuilder("fetch(").append(JSONObject.quote(e.url())).append(", {\n");
        sb.append("  method: ").append(JSONObject.quote(e.method)).append(",\n");
        sb.append("  headers: {\n");
        for (String[] h : e.reqHeaders) {
            if (skip(h[0])) continue;
            sb.append("    ").append(JSONObject.quote(h[0])).append(": ").append(JSONObject.quote(h[1])).append(",\n");
        }
        sb.append("  },\n");
        String body = bodyText(e);
        if (body != null) sb.append("  body: ").append(JSONObject.quote(body)).append(",\n");
        sb.append("}).then(r => r.text()).then(console.log);\n");
        return sb.toString();
    }
}
