package com.fixsssator.netscope.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.fixsssator.netscope.R;
import com.fixsssator.netscope.data.Exchange;
import com.fixsssator.netscope.data.TrafficStore;
import com.fixsssator.netscope.util.BodyUtil;
import com.fixsssator.netscope.util.CurlExporter;
import com.fixsssator.netscope.util.Fmt;
import com.fixsssator.netscope.util.Headers;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.tabs.TabLayout;

import java.util.Locale;

public class DetailActivity extends AppCompatActivity {
    public static final String EXTRA_ID = "id";

    private Exchange e;
    private TextView content;
    private int tab;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        e = TrafficStore.get().find(getIntent().getLongExtra(EXTRA_ID, -1));
        if (e == null) {
            finish();
            return;
        }
        setContentView(R.layout.activity_detail);
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(e.method + " " + e.host);
        }

        content = findViewById(R.id.content);
        TabLayout tabs = findViewById(R.id.tabs);
        tabs.addTab(tabs.newTab().setText("Overview"));
        tabs.addTab(tabs.newTab().setText("Request"));
        tabs.addTab(tabs.newTab().setText("Response"));
        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab t) {
                tab = t.getPosition();
                show();
            }

            @Override
            public void onTabUnselected(TabLayout.Tab t) {}

            @Override
            public void onTabReselected(TabLayout.Tab t) {}
        });

        Button curl = findViewById(R.id.btn_curl);
        Button replay = findViewById(R.id.btn_replay);
        Button share = findViewById(R.id.btn_share);
        curl.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("cURL", CurlExporter.toCurl(e)));
            Toast.makeText(this, "cURL command copied", Toast.LENGTH_SHORT).show();
        });
        replay.setOnClickListener(v -> replay());
        share.setOnClickListener(v -> {
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_TEXT, textFor(tab));
            startActivity(Intent.createChooser(send, "Share"));
        });
        show();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    private void show() {
        content.setText(textFor(tab));
    }

    private String textFor(int t) {
        return t == 0 ? overview() : t == 1 ? requestText() : responseText();
    }

    private String overview() {
        StringBuilder sb = new StringBuilder();
        line(sb, "URL", e.url());
        line(sb, "Method", e.method);
        line(sb, "Status", e.state == Exchange.State.ERROR ? "ERROR"
                : e.status == 0 ? "pending" : e.status + " " + e.statusText);
        line(sb, "State", e.state.name().toLowerCase(Locale.ROOT));
        line(sb, "Protocol", e.httpVersion + (e.intercepted ? " over TLS (decrypted)" : ""));
        if (e.remoteIp != null) line(sb, "Remote IP", e.remoteIp);
        if (e.appLabel != null) line(sb, "App", e.appLabel + " (" + e.appPackage + ")");
        line(sb, "Duration", Fmt.duration(e.durationMs));
        line(sb, "Request body", Fmt.size(e.reqBodySize));
        line(sb, "Response body", Fmt.size(e.respBodySize));
        if (e.note != null) line(sb, "Note", e.note);
        if (e.error != null) line(sb, "Error", e.error);
        return sb.toString();
    }

    private static void line(StringBuilder sb, String k, String v) {
        sb.append(String.format(Locale.US, "%-14s", k)).append(v).append('\n');
    }

    private String requestText() {
        if (e.isTunnel()) return "(CONNECT tunnel - nothing to show)";
        StringBuilder sb = new StringBuilder();
        sb.append(e.method).append(' ').append(e.path).append(' ').append(e.httpVersion).append('\n');
        for (String[] h : e.reqHeaders) sb.append(h[0]).append(": ").append(h[1]).append('\n');
        sb.append('\n').append(BodyUtil.render(e.reqBody, e.reqBodySize, e.reqBodyTruncated, e.reqHeaders));
        return sb.toString();
    }

    private String responseText() {
        if (e.isTunnel()) return "(CONNECT tunnel - nothing to show)";
        if (e.status == 0) {
            return e.error != null ? "No response: " + e.error : "(waiting for response…)";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(e.status).append(' ').append(e.statusText).append('\n');
        for (String[] h : e.respHeaders) sb.append(h[0]).append(": ").append(h[1]).append('\n');
        sb.append('\n').append(BodyUtil.render(e.respBody, e.respBodySize, e.respBodyTruncated, e.respHeaders));
        return sb.toString();
    }

    private void replay() {
        Intent i = new Intent(this, ComposeActivity.class);
        i.putExtra(ComposeActivity.EXTRA_METHOD, e.method);
        i.putExtra(ComposeActivity.EXTRA_URL, e.url());
        StringBuilder hs = new StringBuilder();
        for (String[] h : e.reqHeaders) {
            String n = h[0].toLowerCase(Locale.ROOT);
            if (n.equals("host") || n.equals("content-length") || n.equals("connection")
                    || n.equals("proxy-connection") || n.equals("accept-encoding")) continue;
            hs.append(h[0]).append(": ").append(h[1]).append('\n');
        }
        i.putExtra(ComposeActivity.EXTRA_HEADERS, hs.toString().trim());
        String body = e.reqBodySize > 0
                ? BodyUtil.text(BodyUtil.decoded(e.reqBody, e.reqHeaders), e.reqHeaders) : null;
        i.putExtra(ComposeActivity.EXTRA_BODY, body == null ? "" : body);
        startActivity(i);
    }
}
