package com.fixsssator.netscope;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.TrafficStats;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private final List<CheckBox> appChecks = new ArrayList<>();
    private final List<CheckBox> domainChecks = new ArrayList<>();
    private LinearLayout root;
    private String selectedAppLabel = "Sample session";

    private static final String[] SAMPLE_DOMAINS = {
            "api.example.com",
            "cdn.example.net",
            "auth.example.org",
            "telemetry.example.com",
            "static.example.net",
            "203.0.113.10"
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        showAppPicker();
    }

    private void setupRoot() {
        ScrollView scroll = new ScrollView(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(24));
        scroll.addView(root);
        setContentView(scroll);
    }

    private void showAppPicker() {
        setupRoot();
        title("NetScope");
        TextView version = text("Version " + BuildConfig.VERSION_NAME, 12);
        version.setTextColor(0xFF777777);
        root.addView(version, marginParams(0, 0, 0, 4));
        subtitle("Choose which apps you want to inspect.");
        Button trafficStats = button("View per-app traffic stats");
        trafficStats.setOnClickListener(v -> showTrafficStats());
        root.addView(trafficStats, marginParams(0, 0, 0, 12));
        TextView note = text("This starter build does not capture live network traffic yet.", 14);
        note.setTextColor(0xFFB45309);
        root.addView(note, marginParams(0, 4, 0, 12));

        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setHint("Search installed apps");
        root.addView(search, matchParams());

        LinearLayout actions = row();
        Button all = button("Select all");
        Button none = button("Clear");
        actions.addView(all, weightParams());
        actions.addView(none, weightParams());
        root.addView(actions, marginParams(0, 8, 0, 8));

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list, matchParams());

        appChecks.clear();
        List<ApplicationInfo> apps = new ArrayList<>();
        PackageManager pm = getPackageManager();
        try {
            apps.addAll(pm.getInstalledApplications(PackageManager.GET_META_DATA));
        } catch (RuntimeException ignored) {
            // Keep the screen usable if package enumeration is restricted by the OS.
        }
        Collections.sort(apps, new Comparator<ApplicationInfo>() {
            @Override public int compare(ApplicationInfo a, ApplicationInfo b) {
                return pm.getApplicationLabel(a).toString()
                        .compareToIgnoreCase(pm.getApplicationLabel(b).toString());
            }
        });

        for (ApplicationInfo info : apps) {
            String label;
            try {
                label = pm.getApplicationLabel(info).toString();
            } catch (RuntimeException e) {
                label = info.packageName;
            }
            CheckBox check = new CheckBox(this);
            check.setText(label + "\n" + info.packageName);
            check.setTextSize(14);
            check.setTag(label);
            list.addView(check, matchParams());
            appChecks.add(check);
        }

        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                String query = s.toString().toLowerCase(Locale.ROOT).trim();
                for (CheckBox check : appChecks) {
                    check.setVisibility(check.getText().toString().toLowerCase(Locale.ROOT)
                            .contains(query) ? View.VISIBLE : View.GONE);
                }
            }
            @Override public void afterTextChanged(android.text.Editable s) {}
        });

        all.setOnClickListener(v -> {
            for (CheckBox check : appChecks) {
                if (check.getVisibility() == View.VISIBLE) check.setChecked(true);
            }
        });
        none.setOnClickListener(v -> {
            for (CheckBox check : appChecks) check.setChecked(false);
        });

        Button continueButton = button("Continue to domains");
        continueButton.setOnClickListener(v -> {
            List<String> selected = new ArrayList<>();
            for (CheckBox check : appChecks) {
                if (check.isChecked()) selected.add(String.valueOf(check.getTag()));
            }
            selectedAppLabel = selected.isEmpty()
                    ? "No app selected" : selected.size() + " selected app(s)";
            showDomainPicker();
        });
        root.addView(continueButton, marginParams(0, 16, 0, 0));
    }

    private void showDomainPicker() {
        setupRoot();
        title("Select domains");
        subtitle(selectedAppLabel + " · Sample data only");
        TextView warning = text(
                "These are example addresses, not observed connections. Live capture will be added in a later version.",
                14);
        warning.setTextColor(0xFFB45309);
        root.addView(warning, marginParams(0, 4, 0, 12));

        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setHint("Filter domains or IP addresses");
        root.addView(search, matchParams());

        LinearLayout actions = row();
        Button all = button("Select all");
        Button none = button("Clear");
        actions.addView(all, weightParams());
        actions.addView(none, weightParams());
        root.addView(actions, marginParams(0, 8, 0, 8));

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list, matchParams());
        domainChecks.clear();

        for (String domain : SAMPLE_DOMAINS) {
            CheckBox check = new CheckBox(this);
            check.setText(domain);
            check.setTextSize(15);
            check.setTag(domain);
            list.addView(check, matchParams());
            domainChecks.add(check);
        }

        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                String query = s.toString().toLowerCase(Locale.ROOT).trim();
                for (CheckBox check : domainChecks) {
                    check.setVisibility(String.valueOf(check.getTag()).toLowerCase(Locale.ROOT)
                            .contains(query) ? View.VISIBLE : View.GONE);
                }
            }
            @Override public void afterTextChanged(android.text.Editable s) {}
        });

        all.setOnClickListener(v -> {
            for (CheckBox check : domainChecks) {
                if (check.getVisibility() == View.VISIBLE) check.setChecked(true);
            }
        });
        none.setOnClickListener(v -> {
            for (CheckBox check : domainChecks) check.setChecked(false);
        });

        Button copySelected = button("Copy selected (0)");
        for (CheckBox check : domainChecks) {
            check.setOnCheckedChangeListener((buttonView, isChecked) -> {
                int count = 0;
                for (CheckBox item : domainChecks) if (item.isChecked()) count++;
                copySelected.setText("Copy selected (" + count + ")");
            });
        }
        copySelected.setOnClickListener(v -> {
            List<String> selected = new ArrayList<>();
            for (CheckBox check : domainChecks) {
                if (check.isChecked()) selected.add(String.valueOf(check.getTag()));
            }
            if (selected.isEmpty()) {
                Toast.makeText(this, "Select at least one address.", Toast.LENGTH_SHORT).show();
                return;
            }
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("NetScope addresses",
                    String.join("\n", selected)));
            Toast.makeText(this, "Copied " + selected.size() + " address(es).",
                    Toast.LENGTH_SHORT).show();
        });
        root.addView(copySelected, marginParams(0, 16, 0, 8));

        Button back = button("Back to app selection");
        back.setOnClickListener(v -> showAppPicker());
        root.addView(back, matchParams());
    }


    private void showTrafficStats() {
        setupRoot();
        title("Per-app traffic");
        subtitle("Received and sent bytes since the device booted, where Android exposes per-app counters.");

        TextView note = text(
                "These are byte counters, not domains or full packet capture. Android may report a counter as unavailable on some devices.",
                14);
        note.setTextColor(0xFF60646C);
        root.addView(note, marginParams(0, 0, 0, 12));

        Button refresh = button("Refresh");
        refresh.setOnClickListener(v -> showTrafficStats());
        root.addView(refresh, marginParams(0, 0, 0, 8));

        Button back = button("Back to app selection");
        back.setOnClickListener(v -> showAppPicker());
        root.addView(back, marginParams(0, 0, 0, 12));

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list, matchParams());

        PackageManager pm = getPackageManager();
        List<ApplicationInfo> apps = new ArrayList<>();
        try {
            apps.addAll(pm.getInstalledApplications(PackageManager.GET_META_DATA));
        } catch (RuntimeException ignored) {
            // Display whatever the platform allows us to enumerate.
        }
        Collections.sort(apps, new Comparator<ApplicationInfo>() {
            @Override public int compare(ApplicationInfo a, ApplicationInfo b) {
                long aTotal = safeTotal(a.uid);
                long bTotal = safeTotal(b.uid);
                return Long.compare(bTotal, aTotal);
            }
        });

        for (ApplicationInfo info : apps) {
            long rx = TrafficStats.getUidRxBytes(info.uid);
            long tx = TrafficStats.getUidTxBytes(info.uid);
            String label;
            try {
                label = pm.getApplicationLabel(info).toString();
            } catch (RuntimeException e) {
                label = info.packageName;
            }
            String rxText = rx < 0 ? "Unavailable" : formatBytes(rx);
            String txText = tx < 0 ? "Unavailable" : formatBytes(tx);
            TextView item = text(label + "\\n" + info.packageName
                    + "\\nReceived: " + rxText + " · Sent: " + txText, 14);
            item.setPadding(dp(4), dp(8), dp(4), dp(8));
            list.addView(item, matchParams());
            View divider = new View(this);
            divider.setBackgroundColor(0xFFE0E0E0);
            list.addView(divider, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));
        }
    }

    private long safeTotal(int uid) {
        long rx = TrafficStats.getUidRxBytes(uid);
        long tx = TrafficStats.getUidTxBytes(uid);
        if (rx < 0 || tx < 0) return -1;
        return rx + tx;
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double value = bytes;
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int unit = 0;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
    }

    private void title(String value) {
        TextView view = text(value, 26);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        root.addView(view, marginParams(0, 0, 0, 4));
    }

    private void subtitle(String value) {
        TextView view = text(value, 15);
        view.setTextColor(0xFF60646C);
        root.addView(view, marginParams(0, 0, 0, 12));
    }

    private TextView text(String value, int size) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        return view;
    }

    private Button button(String label) {
        Button button = new Button(this);
        button.setText(label);
        return button;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private LinearLayout.LayoutParams matchParams() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams marginParams(int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = matchParams();
        params.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return params;
    }

    private LinearLayout.LayoutParams weightParams() {
        return new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
