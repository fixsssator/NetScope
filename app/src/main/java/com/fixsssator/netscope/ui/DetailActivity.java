package com.fixsssator.netscope.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.fixsssator.netscope.R;
import com.fixsssator.netscope.data.Exchange;
import com.fixsssator.netscope.data.TrafficStore;
import com.fixsssator.netscope.rules.RuleEngine;
import com.fixsssator.netscope.util.BodyUtil;
import com.fixsssator.netscope.util.CodeGen;
import com.fixsssator.netscope.util.CurlExporter;
import com.fixsssator.netscope.util.Files;
import com.fixsssator.netscope.util.Fmt;
import com.fixsssator.netscope.util.Headers;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.tabs.TabLayout;

import java.util.Locale;

public class DetailActivity extends AppCompatActivity {
    public static final String EXTRA_ID = "id";

    private Exchange e;
    private TextView content;
    private ImageView image;
    private View modeRow;
    private int tab;
    private BodyUtil.ViewMode mode = BodyUtil.ViewMode.PRETTY;

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
        image = findViewById(R.id.image);
        modeRow = findViewById(R.id.mode_row);

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

        ((Button) findViewById(R.id.btn_pretty)).setOnClickListener(v -> setMode(BodyUtil.ViewMode.PRETTY));
        ((Button) findViewById(R.id.btn_raw)).setOnClickListener(v -> setMode(BodyUtil.ViewMode.RAW));
        ((Button) findViewById(R.id.btn_hex)).setOnClickListener(v -> setMode(BodyUtil.ViewMode.HEX));

        ((Button) findViewById(R.id.btn_curl)).setOnClickListener(v -> showCopyAsDialog());
        ((Button) findViewById(R.id.btn_replay)).setOnClickListener(v -> replay());
        ((Button) findViewById(R.id.btn_share)).setOnClickListener(v -> {
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_TEXT, textFor(tab));
            startActivity(Intent.createChooser(send, "Share"));
        });
        show();
    }

    private void setMode(BodyUtil.ViewMode m) {
        mode = m;
        show();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    // ------------------------------------------------------------------ menu

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.detail_menu, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        menu.findItem(R.id.action_fav).setTitle(e.favorite ? "Remove from favorites" : "Add to favorites");
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_fav) {
            e.favorite = !e.favorite;
            TrafficStore.get().changed();
            toast(e.favorite ? "Added to favorites" : "Removed from favorites");
        } else if (id == R.id.action_rule) {
            showRuleDialog();
        } else if (id == R.id.action_copy_body) {
            copyBody();
        } else if (id == R.id.action_save_body) {
            saveBody();
        } else {
            return super.onOptionsItemSelected(item);
        }
        return true;
    }

    // ------------------------------------------------------------------ rendering

    private void show() {
        content.setText(textFor(tab));
        modeRow.setVisibility(tab == 0 ? View.GONE : View.VISIBLE);
        showImage();
    }

    private void showImage() {
        image.setVisibility(View.GONE);
        if (tab != 2 || e.respBodySize == 0 || e.respBodyTruncated) return;
        String ct = Headers.get(e.respHeaders, "Content-Type");
        if (ct == null || !ct.toLowerCase(Locale.ROOT).startsWith("image/")) return;
        byte[] data = BodyUtil.decoded(e.respBody, e.respHeaders);
        Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length);
        if (bmp != null) {
            image.setImageBitmap(bmp);
            image.setVisibility(View.VISIBLE);
        }
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
        if (e.favorite) line(sb, "Favorite", "yes ★");
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
        sb.append('\n').append(BodyUtil.render(e.reqBody, e.reqBodySize, e.reqBodyTruncated, e.reqHeaders, mode));
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
        sb.append('\n').append(BodyUtil.render(e.respBody, e.respBodySize, e.respBodyTruncated, e.respHeaders, mode));
        return sb.toString();
    }

    // ------------------------------------------------------------------ actions

    private void showCopyAsDialog() {
        String[] names = {"cURL", "Python (requests)", "JavaScript (fetch)", "Raw HTTP request"};
        new AlertDialog.Builder(this)
                .setTitle("Copy as…")
                .setItems(names, (d, which) -> {
                    String text;
                    switch (which) {
                        case 0:
                            text = CurlExporter.toCurl(e);
                            break;
                        case 1:
                            text = CodeGen.python(e);
                            break;
                        case 2:
                            text = CodeGen.fetch(e);
                            break;
                        default:
                            text = requestText();
                    }
                    copy(names[which], text);
                    toast(names[which] + " copied");
                })
                .show();
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

    private void copyBody() {
        boolean request = tab == 1;
        byte[] raw = request ? e.reqBody : e.respBody;
        java.util.List<String[]> hdrs = request ? e.reqHeaders : e.respHeaders;
        long size = request ? e.reqBodySize : e.respBodySize;
        if (size == 0) {
            toast("No body");
            return;
        }
        byte[] data = BodyUtil.decoded(raw, hdrs);
        String text = BodyUtil.text(data, hdrs);
        copy("Body", text != null ? text : BodyUtil.hexDump(data, 4096));
        toast("Body copied");
    }

    private void saveBody() {
        boolean request = tab == 1;
        byte[] raw = request ? e.reqBody : e.respBody;
        java.util.List<String[]> hdrs = request ? e.reqHeaders : e.respHeaders;
        long size = request ? e.reqBodySize : e.respBodySize;
        boolean truncated = request ? e.reqBodyTruncated : e.respBodyTruncated;
        if (size == 0) {
            toast("No body");
            return;
        }
        byte[] data = BodyUtil.decoded(raw, hdrs);
        String ct = Headers.get(hdrs, "Content-Type");
        String mime = ct == null ? "application/octet-stream" : ct.split(";")[0].trim();
        String name = "netscope-" + e.id + (request ? "-request" : "-response") + "." + extension(mime);
        boolean ok = Files.saveToDownloads(this, name, mime, data);
        toast(ok ? "Saved Downloads/" + name + (truncated ? " (truncated capture)" : "") : "Could not save the body");
    }

    private static String extension(String mime) {
        String m = mime.toLowerCase(Locale.ROOT);
        if (m.contains("json")) return "json";
        if (m.contains("html")) return "html";
        if (m.contains("xml")) return "xml";
        if (m.contains("javascript")) return "js";
        if (m.contains("css")) return "css";
        if (m.contains("png")) return "png";
        if (m.contains("jpeg") || m.contains("jpg")) return "jpg";
        if (m.contains("gif")) return "gif";
        if (m.contains("webp")) return "webp";
        if (m.contains("pdf")) return "pdf";
        if (m.startsWith("text/")) return "txt";
        return "bin";
    }

    private void showRuleDialog() {
        String[] names = {"Breakpoint on this URL", "Block this URL", "Map local (reply with this response)"};
        new AlertDialog.Builder(this)
                .setTitle("Create rule from this request")
                .setItems(names, (d, which) -> {
                    RuleEngine.Rule r = new RuleEngine.Rule();
                    String full = e.url();
                    int q = full.indexOf('?');
                    r.pattern = q < 0 ? full : full.substring(0, q);
                    if (which == 0) {
                        r.type = RuleEngine.Type.BREAKPOINT;
                    } else if (which == 1) {
                        r.type = RuleEngine.Type.BLOCK;
                    } else {
                        String text = BodyUtil.text(BodyUtil.decoded(e.respBody, e.respHeaders), e.respHeaders);
                        if (text == null || e.respBodySize == 0) {
                            toast("This response has no text body to map");
                            return;
                        }
                        r.type = RuleEngine.Type.MAP_LOCAL;
                        r.value = text;
                    }
                    RuleEngine.get(this).add(r);
                    toast("Rule added (see Rules & breakpoints)");
                })
                .show();
    }

    private void copy(String label, String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText(label, text));
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
