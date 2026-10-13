package com.fixsssator.netscope.ui;

import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Spinner;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.fixsssator.netscope.R;
import com.fixsssator.netscope.rules.RuleEngine;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.util.ArrayList;
import java.util.List;

public class RulesActivity extends AppCompatActivity {
    private RuleEngine engine;
    private ArrayAdapter<String> adapter;
    private final List<String> labels = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_rules);
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Rules & breakpoints");
        }
        engine = RuleEngine.get(this);

        ListView list = findViewById(R.id.list);
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, labels);
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> {
            engine.toggle(pos);
            refresh();
        });
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            new AlertDialog.Builder(this)
                    .setTitle("Delete rule?")
                    .setMessage(labels.get(pos))
                    .setPositiveButton("Delete", (d, w) -> {
                        engine.remove(pos);
                        refresh();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
            return true;
        });

        FloatingActionButton fab = findViewById(R.id.fab);
        fab.setOnClickListener(v -> showAddDialog());
        refresh();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    private void refresh() {
        labels.clear();
        for (RuleEngine.Rule r : engine.all()) {
            StringBuilder sb = new StringBuilder(r.enabled ? "● " : "○ ");
            sb.append(r.type.name()).append("  ").append(r.pattern);
            if (!r.value.isEmpty()) sb.append("\n→ ").append(r.value);
            labels.add(sb.toString());
        }
        adapter.notifyDataSetChanged();
    }

    private void showAddDialog() {
        final RuleEngine.Type[] types = RuleEngine.Type.values();
        final String[] typeNames = new String[types.length];
        for (int i = 0; i < types.length; i++) typeNames[i] = types[i].name();

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);

        Spinner type = new Spinner(this);
        type.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, typeNames));
        EditText pattern = new EditText(this);
        pattern.setHint("URL contains… (re:regex also works)");
        pattern.setSingleLine(true);
        EditText value = new EditText(this);
        box.addView(type);
        box.addView(pattern);
        box.addView(value);

        type.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                switch (types[position]) {
                    case BLOCK:
                    case BREAKPOINT:
                        value.setHint("(no value needed)");
                        value.setEnabled(false);
                        break;
                    case MAP_LOCAL:
                        value.setHint("Response body, e.g. {\"ok\":true}");
                        value.setEnabled(true);
                        break;
                    case MAP_REMOTE:
                        value.setHint("New URL, e.g. http://10.0.2.2:3000/api");
                        value.setEnabled(true);
                        break;
                    default:
                        value.setHint("Name: value   (empty value removes the header)");
                        value.setEnabled(true);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        new AlertDialog.Builder(this)
                .setTitle("New rule")
                .setView(box)
                .setPositiveButton("Add", (d, w) -> {
                    String pat = pattern.getText().toString().trim();
                    if (pat.isEmpty()) return;
                    RuleEngine.Rule r = new RuleEngine.Rule();
                    r.type = types[type.getSelectedItemPosition()];
                    r.pattern = pat;
                    r.value = (r.type == RuleEngine.Type.BLOCK || r.type == RuleEngine.Type.BREAKPOINT) ? "" : value.getText().toString().trim();
                    engine.add(r);
                    refresh();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
