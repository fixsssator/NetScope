package com.fixsssator.netscope;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.TrafficStats;
import android.net.VpnService;
import android.os.Build;
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
import java.util.LinkedHashMap;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.Locale;

public class MainActivity extends Activity {
    private final List<CheckBox> appChecks = new ArrayList<>();
    private final List<CheckBox> domainChecks = new ArrayList<>();
    private LinearLayout root;
    private String selectedAppLabel = "No app selected";
    private ArrayList<String> selectedPackageNames = new ArrayList<>();
    private static final int VPN_PERMISSION_REQUEST = 700;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getIntent().getBooleanExtra("open_dns_history", false)) showDomainPicker();
        else showAppPicker();
    }

    private void setupRoot() {
        ScrollView scroll = new ScrollView(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(28));
        root.setBackgroundColor(0xFFF3F5FA);
        scroll.setFillViewport(true);
        scroll.addView(root);
        setContentView(scroll);
    }

    private void showAppPicker() {
        setupRoot();
        title("NetScope");
        TextView version = text("NETWORK INSPECTOR  ·  v" + BuildConfig.VERSION_NAME, 11);
        version.setTextColor(0xFF65708A);
        version.setLetterSpacing(0.08f);
        root.addView(version, marginParams(0, 0, 0, 12));
        subtitle("Your network activity, without the clutter.");
        addCaptureStatusCard();
        subtitle("Choose apps to observe.");
        Button trafficStats = button("View per-app traffic stats");
        trafficStats.setOnClickListener(v -> showTrafficStats());
        root.addView(trafficStats, marginParams(0, 0, 0, 12));
        TextView note = text("DNS requests are forwarded to Cloudflare DNS (1.1.1.1). Only domain names and returned IP addresses are recorded.", 13);
        note.setTextColor(0xFF65708A);
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
            check.setTag(info.packageName);
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

        Button startDns = button("Start DNS observation for selected apps");
        startDns.setOnClickListener(v -> requestDnsStart());
        root.addView(startDns, marginParams(0, 8, 0, 0));

        Button stopDns = button("Stop DNS observation");
        stopDns.setOnClickListener(v -> stopDnsCapture());
        root.addView(stopDns, marginParams(0, 4, 0, 0));

        Button history = button("View observed DNS history");
        history.setOnClickListener(v -> showDomainPicker());
        root.addView(history, marginParams(0, 4, 0, 0));
    }

    private void showDomainPicker() {
        setupRoot();
        title("Observed DNS history");
        subtitle(selectedAppLabel + " · Locally stored DNS observations");
        TextView warning = text("Recently observed DNS lookups. Tap a domain to inspect the IP addresses returned by DNS.", 13);
        warning.setTextColor(0xFF65708A);
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

        LinkedHashMap<String, JSONObject> domains = loadObservedDomains();
        if (domains.isEmpty()) {
            LinearLayout empty = card();
            TextView message = text("Nothing captured yet", 17);
            message.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            empty.addView(message, matchParams());
            TextView hint = text("Start a session from the home screen. New DNS lookups will appear here.", 13);
            hint.setTextColor(0xFF65708A);
            empty.addView(hint, marginParams(0, 6, 0, 0));
            list.addView(empty, marginParams(0, 8, 0, 8));
        } else {
            for (String domain : domains.keySet()) {
                JSONObject event = domains.get(domain);
                LinearLayout item = card();
                CheckBox check = new CheckBox(this);
                check.setText(domain);
                check.setTextSize(15);
                check.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                check.setTag(domain);
                item.addView(check, matchParams());
                domainChecks.add(check);
                String ipText = formatIps(event.optJSONArray("ips"));
                TextView metadata = text((ipText.isEmpty() ? "No IP address in response" : ipText)
                        + "\\nLast seen  " + event.optString("timestamp", "Unknown"), 12);
                metadata.setTextColor(0xFF65708A);
                metadata.setPadding(dp(36), 0, dp(4), dp(4));
                item.addView(metadata, matchParams());
                TextView details = text("View details  →", 13);
                details.setTextColor(0xFF3659D9);
                details.setPadding(dp(36), dp(8), dp(4), dp(4));
                details.setOnClickListener(v -> showDomainDetails(domain));
                item.addView(details, matchParams());
                list.addView(item, marginParams(0, 0, 0, 10));
            }
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


    private void requestDnsStart() {
        selectedPackageNames.clear();
        for (CheckBox check : appChecks) {
            if (check.isChecked()) selectedPackageNames.add(String.valueOf(check.getTag()));
        }
        if (selectedPackageNames.isEmpty()) {
            Toast.makeText(this, "Select at least one app first.", Toast.LENGTH_LONG).show();
            return;
        }
        Intent permission = VpnService.prepare(this);
        if (permission != null) {
            startActivityForResult(permission, VPN_PERMISSION_REQUEST);
        } else {
            launchDnsService();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == VPN_PERMISSION_REQUEST) {
            if (resultCode == RESULT_OK) launchDnsService();
            else Toast.makeText(this, "VPN permission was not granted.", Toast.LENGTH_LONG).show();
        }
    }

    private void launchDnsService() {
        Intent intent = new Intent(this, DnsCaptureService.class);
        intent.setAction(DnsCaptureService.ACTION_START);
        intent.putStringArrayListExtra(DnsCaptureService.EXTRA_PACKAGES, new ArrayList<>(selectedPackageNames));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent);
        else startService(intent);
        Toast.makeText(this, "DNS observation requested. Check the persistent notification.", Toast.LENGTH_LONG).show();
    }

    private void stopDnsCapture() {
        Intent intent = new Intent(this, DnsCaptureService.class);
        intent.setAction(DnsCaptureService.ACTION_STOP);
        startService(intent);
        Toast.makeText(this, "DNS observation stop requested.", Toast.LENGTH_SHORT).show();
    }

    private LinkedHashMap<String, JSONObject> loadObservedDomains() {
        LinkedHashMap<String, JSONObject> result = new LinkedHashMap<>();
        String json = getSharedPreferences("netscope_capture", MODE_PRIVATE).getString("dns_history", "[]");
        try {
            JSONArray events = new JSONArray(json);
            for (int i = events.length() - 1; i >= 0; i--) {
                JSONObject event = events.getJSONObject(i);
                String domain = event.optString("domain", "");
                if (!domain.isEmpty() && !result.containsKey(domain)) result.put(domain, event);
            }
        } catch (JSONException ignored) { }
        return result;
    }

    private String formatIps(JSONArray ips) {
        if (ips == null || ips.length() == 0) return "";
        List<String> values = new ArrayList<>();
        for (int i = 0; i < ips.length(); i++) values.add(ips.optString(i));
        return String.join("  ·  ", values);
    }

    private void showDomainDetails(String domain) {
        JSONObject found = null;
        String json = getSharedPreferences("netscope_capture", MODE_PRIVATE).getString("dns_history", "[]");
        try {
            JSONArray events = new JSONArray(json);
            for (int i = events.length() - 1; i >= 0; i--) {
                JSONObject event = events.getJSONObject(i);
                if (domain.equals(event.optString("domain"))) { found = event; break; }
            }
        } catch (JSONException ignored) { }
        setupRoot();
        TextView back = text("←  DNS HISTORY", 12);
        back.setTextColor(0xFF3659D9);
        back.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        back.setOnClickListener(v -> showDomainPicker());
        root.addView(back, marginParams(0, 0, 0, 18));
        title(domain);
        subtitle("DNS lookup details");
        LinearLayout details = card();
        TextView label = text("RESOLVED IP ADDRESSES", 11);
        label.setTextColor(0xFF65708A);
        label.setLetterSpacing(0.08f);
        details.addView(label, matchParams());
        JSONArray ips = found == null ? null : found.optJSONArray("ips");
        if (ips == null || ips.length() == 0) {
            TextView noIp = text("No IP address was present in the DNS response.", 14);
            noIp.setPadding(0, dp(8), 0, dp(8));
            details.addView(noIp, matchParams());
        } else {
            for (int i = 0; i < ips.length(); i++) {
                TextView ip = text("◈  " + ips.optString(i), 16);
                ip.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
                ip.setPadding(0, dp(10), 0, dp(10));
                details.addView(ip, matchParams());
                View divider = new View(this);
                divider.setBackgroundColor(0xFFE6EAF2);
                details.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
            }
        }
        TextView seen = text("Last observed\\n" + (found == null ? "Unknown" : found.optString("timestamp", "Unknown")), 13);
        seen.setTextColor(0xFF65708A);
        details.addView(seen, marginParams(0, 12, 0, 0));
        root.addView(details, marginParams(0, 4, 0, 14));
        Button copy = button("Copy domain and IPs");
        copy.setOnClickListener(v -> {
            String value = domain + "\\n" + formatIps(ips);
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("NetScope DNS details", value));
            Toast.makeText(this, "DNS details copied.", Toast.LENGTH_SHORT).show();
        });
        root.addView(copy, matchParams());
    }

    private void addCaptureStatusCard() {
        boolean active = getSharedPreferences("netscope_capture", MODE_PRIVATE).getBoolean("capture_active", false);
        int count = getSharedPreferences("netscope_capture", MODE_PRIVATE).getInt("capture_count", 0);
        LinearLayout status = card();
        TextView eyebrow = text("CAPTURE STATUS", 11);
        eyebrow.setLetterSpacing(0.08f);
        eyebrow.setTextColor(0xFF65708A);
        status.addView(eyebrow, matchParams());
        TextView headline = text(active ? "●  Capture is running" : "○  Capture is stopped", 18);
        headline.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        headline.setTextColor(active ? 0xFF13845B : 0xFF5F687A);
        status.addView(headline, marginParams(0, 6, 0, 2));
        TextView countView = text(count + (count == 1 ? " DNS query captured" : " DNS queries captured"), 13);
        countView.setTextColor(0xFF65708A);
        status.addView(countView, matchParams());
        Button view = button("Open domain history");
        view.setOnClickListener(v -> showDomainPicker());
        status.addView(view, marginParams(0, 10, 0, 0));
        root.addView(status, marginParams(0, 0, 0, 16));
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
