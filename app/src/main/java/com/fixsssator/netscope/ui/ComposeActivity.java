package com.fixsssator.netscope.ui;

import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.fixsssator.netscope.R;
import com.fixsssator.netscope.data.Exchange;
import com.fixsssator.netscope.util.BodyUtil;
import com.fixsssator.netscope.util.Fmt;
import com.google.android.material.appbar.MaterialToolbar;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Simple API tester: compose a request, send it, inspect the response (like the Composer in Reqable). */
public class ComposeActivity extends AppCompatActivity {
    public static final String EXTRA_METHOD = "method";
    public static final String EXTRA_URL = "url";
    public static final String EXTRA_HEADERS = "headers";
    public static final String EXTRA_BODY = "body";

    private static final List<String> METHODS =
            Arrays.asList("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");

    private Spinner method;
    private EditText url;
    private EditText headers;
    private EditText body;
    private TextView result;
    private Button send;
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_compose);
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Composer");
        }

        method = findViewById(R.id.method);
        url = findViewById(R.id.url);
        headers = findViewById(R.id.headers);
        body = findViewById(R.id.body);
        result = findViewById(R.id.result);
        send = findViewById(R.id.send);

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, METHODS);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        method.setAdapter(adapter);

        String m = getIntent().getStringExtra(EXTRA_METHOD);
        if (m != null && METHODS.contains(m.toUpperCase())) method.setSelection(METHODS.indexOf(m.toUpperCase()));
        String u = getIntent().getStringExtra(EXTRA_URL);
        if (u != null) url.setText(u);
        String h = getIntent().getStringExtra(EXTRA_HEADERS);
        if (h != null) headers.setText(h);
        String b = getIntent().getStringExtra(EXTRA_BODY);
        if (b != null) body.setText(b);

        send.setOnClickListener(v -> send());
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    private void send() {
        final String m = (String) method.getSelectedItem();
        String raw = url.getText().toString().trim();
        if (raw.isEmpty()) {
            result.setText("Enter a URL first.");
            return;
        }
        final String u = raw.contains("://") ? raw : "https://" + raw;
        final String hdr = headers.getText().toString();
        final String payload = body.getText().toString();
        send.setEnabled(false);
        result.setText("Sending…");
        io.execute(() -> {
            String out;
            try {
                out = doRequest(m, u, hdr, payload);
            } catch (Exception e) {
                out = "Error: " + e;
            }
            final String text = out;
            runOnUiThread(() -> {
                result.setText(text);
                send.setEnabled(true);
            });
        });
    }

    private static String doRequest(String method, String url, String headerText, String payload) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(15_000);
        c.setReadTimeout(30_000);
        c.setInstanceFollowRedirects(false);
        for (String line : headerText.split("\n")) {
            int i = line.indexOf(':');
            if (i > 0) c.setRequestProperty(line.substring(0, i).trim(), line.substring(i + 1).trim());
        }
        boolean hasBody = !payload.isEmpty() && !method.equals("GET") && !method.equals("HEAD");
        long t0 = System.currentTimeMillis();
        if (hasBody) {
            c.setDoOutput(true);
            byte[] data = payload.getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(data.length);
            try (OutputStream os = c.getOutputStream()) {
                os.write(data);
            }
        }
        int code = c.getResponseCode();
        InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        long total = 0;
        if (is != null) {
            byte[] buf = new byte[8192];
            int r;
            while ((r = is.read(buf)) != -1) {
                total += r;
                if (bos.size() < Exchange.BODY_LIMIT) bos.write(buf, 0, Math.min(r, Exchange.BODY_LIMIT - bos.size()));
            }
            is.close();
        }
        long elapsed = System.currentTimeMillis() - t0;

        List<String[]> respHeaders = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        sb.append(code).append(' ').append(c.getResponseMessage() == null ? "" : c.getResponseMessage())
                .append("  ·  ").append(Fmt.duration(elapsed)).append("  ·  ").append(Fmt.size(total)).append("\n\n");
        for (Map.Entry<String, List<String>> en : c.getHeaderFields().entrySet()) {
            if (en.getKey() == null) continue;
            for (String v : en.getValue()) {
                respHeaders.add(new String[]{en.getKey(), v});
                sb.append(en.getKey()).append(": ").append(v).append('\n');
            }
        }
        sb.append('\n').append(BodyUtil.render(bos.toByteArray(), total, total > bos.size(), respHeaders));
        c.disconnect();
        return sb.toString();
    }
}
