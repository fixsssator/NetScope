package com.fixsssator.netscope.ui;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.fixsssator.netscope.R;
import com.fixsssator.netscope.data.AppFilter;
import com.google.android.material.appbar.MaterialToolbar;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Lets the user choose which apps are captured (like the app list in Reqable). */
public class AppPickerActivity extends AppCompatActivity {
    private static final class Item {
        String pkg;
        String label;
        Drawable icon;
        boolean system;
    }

    private final List<Item> all = new ArrayList<>();
    private final List<Item> shown = new ArrayList<>();
    private final Set<String> selected = new HashSet<>();
    private boolean showSystem;
    private String query = "";

    private ListAdapter adapter;
    private RadioGroup modeGroup;
    private TextView hint;
    private View progress;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_apps);
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Apps to capture");
        }

        hint = findViewById(R.id.hint);
        progress = findViewById(R.id.progress);
        modeGroup = findViewById(R.id.mode);
        EditText search = findViewById(R.id.search);
        ListView list = findViewById(R.id.list);

        selected.addAll(AppFilter.selected(this));
        switch (AppFilter.mode(this)) {
            case ONLY:
                modeGroup.check(R.id.rb_only);
                break;
            case EXCLUDE:
                modeGroup.check(R.id.rb_exclude);
                break;
            default:
                modeGroup.check(R.id.rb_all);
        }
        modeGroup.setOnCheckedChangeListener((g, id) -> {
            AppFilter.Mode m = id == R.id.rb_only ? AppFilter.Mode.ONLY
                    : id == R.id.rb_exclude ? AppFilter.Mode.EXCLUDE : AppFilter.Mode.ALL;
            AppFilter.setMode(this, m);
            changed();
        });

        adapter = new ListAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> {
            Item it = shown.get(pos);
            if (!selected.remove(it.pkg)) selected.add(it.pkg);
            AppFilter.setSelected(this, selected);
            changed();
            adapter.notifyDataSetChanged();
        });

        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                query = s.toString().trim().toLowerCase(Locale.ROOT);
                applyFilter();
            }
        });

        updateHint();
        loadApps();
    }

    private void loadApps() {
        progress.setVisibility(View.VISIBLE);
        new Thread(() -> {
            PackageManager pm = getPackageManager();
            List<Item> loaded = new ArrayList<>();
            for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
                if (ai.packageName.equals(getPackageName())) continue;
                Item it = new Item();
                it.pkg = ai.packageName;
                it.label = String.valueOf(pm.getApplicationLabel(ai));
                it.system = (ai.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
                        && pm.getLaunchIntentForPackage(ai.packageName) == null;
                try {
                    it.icon = pm.getApplicationIcon(ai);
                } catch (Exception e) {
                    it.icon = null;
                }
                loaded.add(it);
            }
            Collections.sort(loaded, (a, b) -> a.label.compareToIgnoreCase(b.label));
            runOnUiThread(() -> {
                all.clear();
                all.addAll(loaded);
                progress.setVisibility(View.GONE);
                applyFilter();
            });
        }, "netscope-app-list").start();
    }

    /**
     * Rebuilds the visible list: selected apps first, then the rest, each part alphabetical.
     * Toggling a checkbox does not re-sort (the row would jump under your finger); the order is
     * refreshed when the screen opens, on search, on "show system apps", and on select all / clear.
     */
    private void applyFilter() {
        shown.clear();
        List<Item> others = new ArrayList<>();
        for (Item it : all) {
            if (!showSystem && it.system && !selected.contains(it.pkg)) continue;
            if (!query.isEmpty() && !it.label.toLowerCase(Locale.ROOT).contains(query)
                    && !it.pkg.toLowerCase(Locale.ROOT).contains(query)) continue;
            if (selected.contains(it.pkg)) shown.add(it);
            else others.add(it);
        }
        shown.addAll(others);
        adapter.notifyDataSetChanged();
    }

    /** Marks the result so the main screen restarts a running capture with the new app list. */
    private void changed() {
        setResult(RESULT_OK);
        updateHint();
    }

    private void updateHint() {
        int n = selected.size();
        int id = modeGroup.getCheckedRadioButtonId();
        if (id == R.id.rb_only) {
            hint.setText(n == 0
                    ? "Nothing selected yet - until you pick an app, all apps are captured."
                    : n + " selected - only these apps are captured.");
        } else if (id == R.id.rb_exclude) {
            hint.setText(n + " selected - these apps are NOT captured.");
        } else {
            hint.setText("Selection is ignored while \"Capture all apps\" is chosen (" + n + " selected).");
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.apps_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_system) {
            showSystem = !item.isChecked();
            item.setChecked(showSystem);
            applyFilter();
        } else if (id == R.id.action_select_all) {
            for (Item it : shown) selected.add(it.pkg);
            AppFilter.setSelected(this, selected);
            changed();
            applyFilter();
        } else if (id == R.id.action_clear_sel) {
            selected.clear();
            AppFilter.setSelected(this, selected);
            changed();
            applyFilter();
        } else {
            return super.onOptionsItemSelected(item);
        }
        return true;
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    private final class ListAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return shown.size();
        }

        @Override
        public Object getItem(int position) {
            return shown.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView != null ? convertView
                    : LayoutInflater.from(AppPickerActivity.this).inflate(R.layout.item_app, parent, false);
            Item it = shown.get(position);
            ((ImageView) v.findViewById(R.id.icon)).setImageDrawable(it.icon);
            ((TextView) v.findViewById(R.id.name)).setText(it.label);
            ((TextView) v.findViewById(R.id.pkg)).setText(it.pkg);
            ((CheckBox) v.findViewById(R.id.check)).setChecked(selected.contains(it.pkg));
            return v;
        }
    }
}
