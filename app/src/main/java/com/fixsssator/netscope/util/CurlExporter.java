package com.fixsssator.netscope.util;

import com.fixsssator.netscope.data.Exchange;

import java.util.Locale;

public final class CurlExporter {
    private CurlExporter() {}

    public static String toCurl(Exchange e) {
        StringBuilder sb = new StringBuilder("curl");
        if (!"GET".equalsIgnoreCase(e.method)) sb.append(" -X ").append(e.method);
        sb.append(' ').append(quote(e.url()));
        boolean compressed = false;
        for (String[] h : e.reqHeaders) {
            String n = h[0].toLowerCase(Locale.ROOT);
            if (n.equals("host") || n.equals("content-length") || n.equals("connection")
                    || n.equals("proxy-connection") || n.equals("proxy-authorization")) continue;
            if (n.equals("accept-encoding")) {
                compressed = true;
                continue;
            }
            sb.append(" \\\n  -H ").append(quote(h[0] + ": " + h[1]));
        }
        if (compressed) sb.append(" \\\n  --compressed");
        if (e.reqBodySize > 0) {
            String text = BodyUtil.text(BodyUtil.decoded(e.reqBody, e.reqHeaders), e.reqHeaders);
            if (text != null) {
                sb.append(" \\\n  --data-raw ").append(quote(text));
            } else {
                sb.append(" \\\n  # binary request body (").append(Fmt.size(e.reqBodySize)).append(") omitted");
            }
        }
        return sb.toString();
    }

    private static String quote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
