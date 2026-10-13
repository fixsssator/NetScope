package com.fixsssator.netscope.ui;

import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.fixsssator.netscope.R;
import com.fixsssator.netscope.rules.BreakpointManager;
import com.google.android.material.appbar.MaterialToolbar;

/** Edit a request that is paused at a breakpoint, then resume or drop it. */
public class BreakpointActivity extends AppCompatActivity {
    public static final String EXTRA_ID = "id";

    private BreakpointManager.Pending pending;
    private EditText method;
    private EditText url;
    private EditText headers;
    private EditText body;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        pending = BreakpointManager.get().forExchange(getIntent().getLongExtra(EXTRA_ID, -1));
        if (pending == null) {
            Toast.makeText(this, "This request is no longer paused", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        setContentView(R.layout.activity_breakpoint);
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Breakpoint");
        }

        method = findViewById(R.id.bp_method);
        url = findViewById(R.id.bp_url);
        headers = findViewById(R.id.bp_headers);
        body = findViewById(R.id.bp_body);
        method.setText(pending.method);
        url.setText(pending.url);
        headers.setText(pending.headers);
        body.setText(pending.body);
        if (!pending.bodyEditable) {
            body.setEnabled(false);
            body.setHint("Binary body - forwarded unchanged");
        }

        ((Button) findViewById(R.id.bp_resume)).setOnClickListener(v -> {
            pending.method = method.getText().toString();
            pending.url = url.getText().toString();
            pending.headers = headers.getText().toString();
            pending.body = body.getText().toString();
            pending.resume();
            finish();
        });
        ((Button) findViewById(R.id.bp_drop)).setOnClickListener(v -> {
            pending.dropRequest();
            finish();
        });
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish(); // the request stays paused until resumed, dropped or timed out
        return true;
    }
}
