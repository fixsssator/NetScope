package com.fixsssator.netscope.ui;

import android.os.Bundle;
import android.widget.EditText;
import android.widget.RadioGroup;

import androidx.appcompat.app.AppCompatActivity;

import com.fixsssator.netscope.R;
import com.fixsssator.netscope.rules.SslList;
import com.google.android.material.appbar.MaterialToolbar;

/** "SSL proxying" settings: which hosts NetScope decrypts and which it only tunnels. */
public class SslActivity extends AppCompatActivity {
    private RadioGroup mode;
    private EditText list;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ssl);
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("SSL proxying list");
        }
        mode = findViewById(R.id.ssl_mode);
        list = findViewById(R.id.ssl_list);
        list.setText(SslList.patterns(this));
        switch (SslList.mode(this)) {
            case ONLY_LISTED:
                mode.check(R.id.rb_ssl_only);
                break;
            case EXCEPT_LISTED:
                mode.check(R.id.rb_ssl_except);
                break;
            default:
                mode.check(R.id.rb_ssl_all);
        }
    }

    @Override
    protected void onPause() {
        int id = mode.getCheckedRadioButtonId();
        SslList.Mode m = id == R.id.rb_ssl_only ? SslList.Mode.ONLY_LISTED
                : id == R.id.rb_ssl_except ? SslList.Mode.EXCEPT_LISTED : SslList.Mode.ALL;
        SslList.save(this, m, list.getText().toString());
        super.onPause();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}
