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
import android.widget.ImageView;
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
    private boolean darkMode;
    private boolean includeSystemApps;
    private String currentScreen = "apps";
    private LinearLayout liveControls;
    private LinearLayout domainListView;
    private EditText appSearchView;
    private EditText domainSearchView;
    private android.os.Handler liveHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable liveRefresh;
    private String lastRenderedHistory;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        java.util.Set<String> saved = getSharedPreferences("netscope_settings", MODE_PRIVATE).getStringSet("selected_packages", Collections.<String>emptySet());
        selectedPackageNames = new ArrayList<>(saved);
        selectedAppLabel = selectedPackageNames.isEmpty() ? "No app selected" : selectedPackageNames.size() + " selected app(s)";
        if (getIntent().getBooleanExtra("open_dns_history", false)) showDomainPicker();
        else showAppPicker();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent.getBooleanExtra("open_dns_history", false)) showDomainPicker();
    }

    @Override
    public void onBackPressed() {
        // Handle Android system back, including edge-swipe gestures, as in-app navigation.
        if ("details".equals(currentScreen)) {
            showDomainPicker();
        } else if ("history".equals(currentScreen) || "traffic".equals(currentScreen)) {
            showAppPicker();
        } else {
            super.onBackPressed();
        }
    }

    private void setupRoot() {
        darkMode = getSharedPreferences("netscope_settings", MODE_PRIVATE).getBoolean("dark_mode", true);
        includeSystemApps = getSharedPreferences("netscope_settings", MODE_PRIVATE).getBoolean("include_system_apps", false);
        if (Build.VERSION.SDK_INT >= 21) {
            getWindow().setStatusBarColor(colorBackground());
            getWindow().setNavigationBarColor(colorBackground());
            getWindow().getDecorView().setSystemUiVisibility(darkMode ? 0 :
                    (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR));
        }
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackgroundColor(colorBackground());
        liveControls = new LinearLayout(this);
        liveControls.setOrientation(LinearLayout.VERTICAL);
        liveControls.setPadding(dp(12), dp(8), dp(12), dp(8));
        liveControls.setBackgroundColor(colorCard());
        TextView liveStatus = text(getSharedPreferences("netscope_capture", MODE_PRIVATE).getBoolean("capture_active", false) ? "● DNS observation is running" : "○ DNS observation is stopped", 13);
        liveStatus.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        liveStatus.setTextColor(colorText());
        liveControls.addView(liveStatus, matchParams());
        LinearLayout globalActions = row();
        Button globalToggle = button(getSharedPreferences("netscope_capture", MODE_PRIVATE).getBoolean("capture_active", false) ? "Stop DNS" : "Start DNS");
        globalToggle.setOnClickListener(v -> {
            if (getSharedPreferences("netscope_capture", MODE_PRIVATE).getBoolean("capture_active", false)) stopDnsCapture();
            else {
                selectedPackageNames = getSavedSelectedPackages();
                requestDnsStart();
            }
        });
        Button globalLive = button("Live domains");
        globalLive.setOnClickListener(v -> showDomainPicker());
        globalActions.addView(globalToggle, weightParams());
        globalActions.addView(globalLive, weightParams());
        liveControls.addView(globalActions, marginParams(0, 4, 0, 0));
        outer.addView(liveControls, matchParams());
        ScrollView scroll = new ScrollView(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(28));
        root.setBackgroundColor(colorBackground());
        scroll.setFillViewport(true);
        scroll.addView(root);
        outer.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(outer);
    }

    private void showAppPicker() {
        stopLiveRefresh();
        currentScreen = "apps";
        setupRoot();
        title("NetScope");
        TextView version = text("NETWORK INSPECTOR  ·  v" + BuildConfig.VERSION_NAME, 11);
        version.setTextColor(colorMuted());
        version.setLetterSpacing(0.08f);
        root.addView(version, marginParams(0, 0, 0, 12));
        subtitle("Your network activity, without the clutter.");
        Button themeToggle = button(darkMode ? "☀  Switch to light theme" : "☾  Switch to dark theme");
        themeToggle.setOnClickListener(v -> {
            getSharedPreferences("netscope_settings", MODE_PRIVATE).edit().putBoolean("dark_mode", !darkMode).apply();
            showAppPicker();
        });
        root.addView(themeToggle, marginParams(0, 0, 0, 14));
        subtitle("Choose apps to observe.");
        Button trafficStats = button("View per-app traffic stats");
        trafficStats.setOnClickListener(v -> showTrafficStats());
        root.addView(trafficStats, marginParams(0, 0, 0, 12));
        TextView note = text("DNS requests are forwarded to Cloudflare DNS (1.1.1.1). Only domain names and returned IP addresses are recorded.", 13);
        note.setTextColor(colorMuted());
        root.addView(note, marginParams(0, 4, 0, 12));

        EditText search = new EditText(this);
        appSearchView = search;
        search.setSingleLine(true);
        search.setTextColor(colorText());
        search.setHintTextColor(colorMuted());
        search.setHint("Search installed apps");
        search.setText(getSharedPreferences("netscope_settings", MODE_PRIVATE).getString("app_filter", ""));
        root.addView(search, matchParams());

        Button systemToggle = button(includeSystemApps ? "Hide system apps" : "Show system apps");
        systemToggle.setOnClickListener(v -> {
            getSharedPreferences("netscope_settings", MODE_PRIVATE).edit()
                    .putBoolean("include_system_apps", !includeSystemApps).apply();
            showAppPicker();
        });
        root.addView(systemToggle, marginParams(0, 8, 0, 4));

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
                boolean selectedA = selectedPackageNames.contains(a.packageName);
                boolean selectedB = selectedPackageNames.contains(b.packageName);
                if (selectedA != selectedB) return selectedA ? -1 : 1;
                return pm.getApplicationLabel(a).toString().compareToIgnoreCase(pm.getApplicationLabel(b).toString());
            }
        });

        for (ApplicationInfo info : apps) {
            boolean isSystem = (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                    || (info.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
            if (isSystem && !includeSystemApps) continue;
            String label;
            try {
                label = pm.getApplicationLabel(info).toString();
            } catch (RuntimeException e) {
                label = info.packageName;
            }
            LinearLayout appRow = row();
            appRow.setPadding(dp(8), dp(7), dp(8), dp(7));
            appRow.setBackground(cardBackground());
            ImageView icon = new ImageView(this);
            try {
                icon.setImageDrawable(pm.getApplicationIcon(info));
            } catch (RuntimeException ignored) {
                icon.setImageResource(android.R.drawable.sym_def_app_icon);
            }
            LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(42), dp(42));
            iconParams.setMargins(0, 0, dp(10), 0);
            appRow.addView(icon, iconParams);

            CheckBox check = new CheckBox(this);
            check.setText(label + "\n" + info.packageName + (isSystem ? "\nSYSTEM APP" : ""));
            check.setTextSize(13);
            check.setTextColor(colorText());
            check.setTag(info.packageName);
            if (selectedPackageNames.contains(info.packageName)) check.setChecked(true);
            check.setOnCheckedChangeListener((buttonView, isChecked) -> {
                saveSelectedPackagesFromChecks();
                {
                    list.removeView(appRow);
                    int selectedRows = 0;
                    for (int i = 0; i < list.getChildCount(); i++) {
                        View child = list.getChildAt(i);
                        if (child instanceof LinearLayout) {
                            for (int j = 0; j < ((LinearLayout) child).getChildCount(); j++) {
                                View rowChild = ((LinearLayout) child).getChildAt(j);
                                if (rowChild instanceof CheckBox && ((CheckBox) rowChild).isChecked()) { selectedRows++; break; }
                            }
                        }
                    }
                    list.addView(appRow, Math.min(selectedRows, list.getChildCount()));
                }
            });
            appRow.addView(check, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            list.addView(appRow, marginParams(0, 0, 0, 6));
            appChecks.add(check);
        }

        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                getSharedPreferences("netscope_settings", MODE_PRIVATE).edit().putString("app_filter", s.toString()).apply();
                String query = s.toString().toLowerCase(Locale.ROOT).trim();
                for (CheckBox check : appChecks) {
                    boolean visible = check.getText().toString().toLowerCase(Locale.ROOT).contains(query);
                    if (check.getParent() instanceof View) ((View) check.getParent()).setVisibility(visible ? View.VISIBLE : View.GONE);
                }
            }
            @Override public void afterTextChanged(android.text.Editable s) {}
        });
        String initialAppQuery = search.getText().toString().toLowerCase(Locale.ROOT).trim();
        for (CheckBox check : appChecks) {
            if (check.getParent() instanceof View) {
                ((View) check.getParent()).setVisibility(check.getText().toString().toLowerCase(Locale.ROOT).contains(initialAppQuery) ? View.VISIBLE : View.GONE);
            }
        }

        all.setOnClickListener(v -> {
            for (CheckBox check : appChecks) {
                if (check.getParent() instanceof View && ((View) check.getParent()).getVisibility() == View.VISIBLE) check.setChecked(true);
            }
            saveSelectedPackagesFromChecks();
            showAppPicker();
        });
        none.setOnClickListener(v -> {
            for (CheckBox check : appChecks) check.setChecked(false);
            saveSelectedPackagesFromChecks();
            showAppPicker();
        });

        Button continueButton = button("Continue to domains");
        continueButton.setOnClickListener(v -> {
            List<String> selected = new ArrayList<>();
            for (CheckBox check : appChecks) {
                if (check.isChecked()) selected.add(String.valueOf(check.getTag()));
            }
            selectedPackageNames = new ArrayList<>(selected);
            saveSelectedPackages(selectedPackageNames);
            selectedAppLabel = selected.isEmpty()
                    ? "No app selected" : selected.size() + " selected app(s)";
            showDomainPicker();
        });
        root.addView(continueButton, marginParams(0, 16, 0, 0));

        // DNS controls are kept in the status card above the long app list.
    }

    private void showDomainPicker() {
        currentScreen = "history";
        setupRoot();
        title("Live DNS domains");
        subtitle(selectedAppLabel + " · Domains and IP addresses update automatically while capture is running");
        TextView warning = text("Recently observed DNS lookups. Tap a domain to inspect the IP addresses returned by DNS.", 13);
        warning.setTextColor(colorMuted());
        root.addView(warning, marginParams(0, 4, 0, 12));

        EditText search = new EditText(this);
        domainSearchView = search;
        search.setSingleLine(true);
        search.setTextColor(colorText());
        search.setHintTextColor(colorMuted());
        search.setHint("Filter domains or IP addresses");
        search.setText(getSharedPreferences("netscope_settings", MODE_PRIVATE).getString("domain_filter", ""));
        root.addView(search, matchParams());

        LinearLayout actions = row();
        Button all = button("Select all");
        Button none = button("Clear");
        actions.addView(all, weightParams());
        actions.addView(none, weightParams());
        root.addView(actions, marginParams(0, 8, 0, 8));

        LinearLayout list = new LinearLayout(this);
        domainListView = list;
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list, matchParams());
        domainChecks.clear();

        renderDomainList(list, search.getText().toString());
        /*
        LinkedHashMap<String, JSONObject> domains = loadObservedDomains();
        if (domains.isEmpty()) {
            LinearLayout empty = card();
            TextView message = text("Nothing captured yet", 17);
            message.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            empty.addView(message, matchParams());
            TextView hint = text("Start a session from the home screen. New DNS lookups will appear here.", 13);
            hint.setTextColor(colorMuted());
            empty.addView(hint, marginParams(0, 6, 0, 0));
            list.addView(empty, marginParams(0, 8, 0, 8));
        } else {
            for (String domain : domains.keySet()) {
                JSONObject event = domains.get(domain);
                LinearLayout item = card();
                CheckBox check = new CheckBox(this);
                check.setText(domain);
                check.setTextSize(15);
                check.setTextColor(colorText());
                check.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                check.setTag(domain);
                item.addView(check, matchParams());
                domainChecks.add(check);
                String ipText = formatIps(event.optJSONArray("ips"));
                TextView metadata = text((ipText.isEmpty() ? "No IP address in response" : ipText)
                        + "\\nLast seen  " + event.optString("timestamp", "Unknown"), 12);
                metadata.setTextColor(colorMuted());
                metadata.setPadding(dp(36), 0, dp(4), dp(4));
                item.addView(metadata, matchParams());
                TextView details = text("View details  →", 13);
                details.setTextColor(colorPrimary());
                details.setPadding(dp(36), dp(8), dp(4), dp(4));
                details.setOnClickListener(v -> showDomainDetails(domain));
                item.addView(details, matchParams());
                list.addView(item, marginParams(0, 0, 0, 10));
            }
        }

        */
        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                getSharedPreferences("netscope_settings", MODE_PRIVATE).edit().putString("domain_filter", s.toString()).apply();
                String query = s.toString().toLowerCase(Locale.ROOT).trim();
                renderDomainList(list, s.toString());
                /*
                for (CheckBox check : domainChecks) {
                    boolean visible = String.valueOf(check.getTag()).toLowerCase(Locale.ROOT).contains(query);
                    check.setVisibility(View.VISIBLE);
                    if (check.getParent() instanceof View) ((View) check.getParent()).setVisibility(visible ? View.VISIBLE : View.GONE);
                }
                */
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

        startLiveRefresh();

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
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 701);
            return;
        }
        if ("apps".equals(currentScreen)) {
            selectedPackageNames.clear();
            for (CheckBox check : appChecks) {
                if (check.isChecked()) selectedPackageNames.add(String.valueOf(check.getTag()));
            }
            saveSelectedPackages(selectedPackageNames);
        } else {
            selectedPackageNames = getSavedSelectedPackages();
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
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 701) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                requestDnsStart();
            } else {
                Toast.makeText(this, "Capture can run, but Android may hide its notification. Allow notifications for a visible status.", Toast.LENGTH_LONG).show();
                selectedPackageNames.clear();
                for (CheckBox check : appChecks) if (check.isChecked()) selectedPackageNames.add(String.valueOf(check.getTag()));
                if (!selectedPackageNames.isEmpty()) {
                    Intent permission = VpnService.prepare(this);
                    if (permission != null) startActivityForResult(permission, VPN_PERMISSION_REQUEST);
                    else launchDnsService();
                }
            }
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
        new android.os.Handler(getMainLooper()).postDelayed(this::showDomainPicker, 700);
    }

    private void stopDnsCapture() {
        Intent intent = new Intent(this, DnsCaptureService.class);
        intent.setAction(DnsCaptureService.ACTION_STOP);
        startService(intent);
        Toast.makeText(this, "DNS observation stop requested.", Toast.LENGTH_SHORT).show();
        new android.os.Handler(getMainLooper()).postDelayed(this::showAppPicker, 700);
    }

    private ArrayList<String> getSavedSelectedPackages() {
        return new ArrayList<>(getSharedPreferences("netscope_settings", MODE_PRIVATE)
                .getStringSet("selected_packages", Collections.<String>emptySet()));
    }

    private void saveSelectedPackages(List<String> packages) {
        getSharedPreferences("netscope_settings", MODE_PRIVATE).edit()
                .putStringSet("selected_packages", new java.util.HashSet<>(packages)).apply();
    }

    private void saveSelectedPackagesFromChecks() {
        ArrayList<String> selected = new ArrayList<>();
        for (CheckBox check : appChecks) if (check.isChecked()) selected.add(String.valueOf(check.getTag()));
        selectedPackageNames = selected;
        saveSelectedPackages(selected);
    }

    private void startLiveRefresh() {
        stopLiveRefresh();
        liveRefresh = new Runnable() {
            @Override public void run() {
                if (!"history".equals(currentScreen) || domainListView == null) return;
                String history = getSharedPreferences("netscope_capture", MODE_PRIVATE).getString("dns_history", "[]");
                if (!history.equals(lastRenderedHistory)) {
                    int scrollY = findScrollView().getScrollY();
                    String filter = domainSearchView == null ? "" : domainSearchView.getText().toString();
                    renderDomainList(domainListView, filter);
                    findScrollView().post(() -> findScrollView().scrollTo(0, scrollY));
                }
                liveHandler.postDelayed(this, 1200);
            }
        };
        liveHandler.postDelayed(liveRefresh, 800);
    }

    private ScrollView findScrollView() {
        View content = root;
        if (content.getParent() instanceof ScrollView) return (ScrollView) content.getParent();
        return new ScrollView(this);
    }

    private void stopLiveRefresh() {
        if (liveRefresh != null) liveHandler.removeCallbacks(liveRefresh);
        liveRefresh = null;
    }

    private void renderDomainList(LinearLayout list, String filterText) {
        if (list == null) return;
        lastRenderedHistory = getSharedPreferences("netscope_capture", MODE_PRIVATE).getString("dns_history", "[]");
        list.removeAllViews();
        domainChecks.clear();
        String query = filterText == null ? "" : filterText.toLowerCase(Locale.ROOT).trim();
        LinkedHashMap<String, JSONObject> domains = loadObservedDomains();
        int visibleCount = 0;
        for (String domain : domains.keySet()) {
            JSONObject event = domains.get(domain);
            String ipsText = formatIps(event.optJSONArray("ips"));
            if (!domain.toLowerCase(Locale.ROOT).contains(query) && !ipsText.toLowerCase(Locale.ROOT).contains(query)) continue;
            visibleCount++;
            LinearLayout item = card();
            CheckBox check = new CheckBox(this);
            check.setText(domain);
            check.setTextSize(15);
            check.setTextColor(colorText());
            check.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            check.setTag(domain);
            item.addView(check, matchParams());
            domainChecks.add(check);
            TextView metadata = text((ipsText.isEmpty() ? "No IP address in response" : ipsText)
                    + "\\nLast seen  " + event.optString("timestamp", "Unknown"), 12);
            metadata.setTextColor(colorMuted());
            metadata.setPadding(dp(36), 0, dp(4), dp(4));
            item.addView(metadata, matchParams());
            TextView details = text("View details  →", 13);
            details.setTextColor(colorPrimary());
            details.setPadding(dp(36), dp(8), dp(4), dp(4));
            details.setOnClickListener(v -> showDomainDetails(domain));
            item.addView(details, matchParams());
            list.addView(item, marginParams(0, 0, 0, 10));
        }
        if (visibleCount == 0) {
            LinearLayout empty = card();
            TextView message = text(getSharedPreferences("netscope_capture", MODE_PRIVATE).getBoolean("capture_active", false)
                    ? "Waiting for DNS queries…" : "No matching DNS domains", 17);
            message.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            empty.addView(message, matchParams());
            TextView hint = text("Newly observed domains and IP addresses appear here automatically while DNS observation is running.", 13);
            hint.setTextColor(colorMuted());
            empty.addView(hint, marginParams(0, 6, 0, 0));
            list.addView(empty, marginParams(0, 8, 0, 8));
        }
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
        stopLiveRefresh();
        currentScreen = "details";
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
        back.setTextColor(colorPrimary());
        back.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        back.setOnClickListener(v -> showDomainPicker());
        root.addView(back, marginParams(0, 0, 0, 18));
        title(domain);
        subtitle("DNS lookup details");
        LinearLayout details = card();
        TextView label = text("RESOLVED IP ADDRESSES", 11);
        label.setTextColor(colorMuted());
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
                divider.setBackgroundColor(colorDivider());
                details.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
            }
        }
        TextView seen = text("Last observed\\n" + (found == null ? "Unknown" : found.optString("timestamp", "Unknown")), 13);
        seen.setTextColor(colorMuted());
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
        eyebrow.setTextColor(colorMuted());
        status.addView(eyebrow, matchParams());
        TextView headline = text(active ? "●  Capture is running" : "○  Capture is stopped", 18);
        headline.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        headline.setTextColor(active ? (darkMode ? 0xFF5FE0A2 : 0xFF13845B) : colorMuted());
        status.addView(headline, marginParams(0, 6, 0, 2));
        TextView countView = text(count + (count == 1 ? " DNS query captured" : " DNS queries captured"), 13);
        countView.setTextColor(colorMuted());
        status.addView(countView, matchParams());
        Button refresh = button("Refresh capture status");
        refresh.setOnClickListener(v -> showAppPicker());
        status.addView(refresh, marginParams(0, 10, 0, 0));
        Button toggleCapture = button(active ? "Stop DNS observation" : "Start DNS observation for selected apps");
        toggleCapture.setOnClickListener(v -> {
            if (getSharedPreferences("netscope_capture", MODE_PRIVATE).getBoolean("capture_active", false)) {
                stopDnsCapture();
            } else {
                requestDnsStart();
            }
        });
        status.addView(toggleCapture, marginParams(0, 10, 0, 0));

        Button view = button("View observed DNS history");
        view.setOnClickListener(v -> showDomainPicker());
        status.addView(view, marginParams(0, 6, 0, 0));
        root.addView(status, marginParams(0, 0, 0, 16));
    }

    private void showTrafficStats() {
        stopLiveRefresh();
        currentScreen = "traffic";
        setupRoot();
        title("Per-app traffic");
        subtitle("Received and sent bytes since the device booted, where Android exposes per-app counters.");

        TextView note = text(
                "These are byte counters, not domains or full packet capture. Android may report a counter as unavailable on some devices.",
                14);
        note.setTextColor(colorMuted());
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
            LinearLayout statRow = row();
            statRow.setPadding(dp(8), dp(8), dp(8), dp(8));
            ImageView icon = new ImageView(this);
            try { icon.setImageDrawable(pm.getApplicationIcon(info)); }
            catch (RuntimeException ignored) { icon.setImageResource(android.R.drawable.sym_def_app_icon); }
            LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(38), dp(38));
            iconParams.setMargins(0, 0, dp(10), 0);
            statRow.addView(icon, iconParams);
            TextView item = text(label + "\\n" + info.packageName
                    + "\\nReceived: " + rxText + " · Sent: " + txText, 13);
            statRow.addView(item, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            list.addView(statRow, matchParams());
            View divider = new View(this);
            divider.setBackgroundColor(colorDivider());
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
        TextView view = text(value, 28);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setTextColor(colorText());
        root.addView(view, marginParams(0, 0, 0, 6));
    }

    private void subtitle(String value) {
        TextView view = text(value, 14);
        view.setTextColor(colorMuted());
        root.addView(view, marginParams(0, 0, 0, 14));
    }

    private TextView text(String value, int size) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(colorText());
        return view;
    }

    private Button button(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(0xFFFFFFFF);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(colorPrimary());
        bg.setCornerRadius(dp(14));
        button.setBackground(bg);
        if (Build.VERSION.SDK_INT >= 21) button.setElevation(dp(1));
        button.setPadding(dp(12), dp(4), dp(12), dp(4));
        return button;
    }

    private GradientDrawable cardBackground() {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(colorCard());
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), colorBorder());
        return bg;
    }

    private int colorBackground() { return darkMode ? 0xFF10141D : 0xFFF3F5FA; }
    private int colorCard() { return darkMode ? 0xFF1B2230 : Color.WHITE; }
    private int colorText() { return darkMode ? 0xFFE8EDF7 : 0xFF17233F; }
    private int colorMuted() { return darkMode ? 0xFFA4AEC2 : 0xFF65708A; }
    private int colorBorder() { return darkMode ? 0xFF30394B : 0xFFE4E9F2; }
    private int colorDivider() { return darkMode ? 0xFF30394B : 0xFFE6EAF2; }
    private int colorPrimary() { return darkMode ? 0xFF4769D8 : 0xFF3659D9; }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(colorCard());
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(1), colorBorder());
        card.setBackground(bg);
        if (Build.VERSION.SDK_INT >= 21) card.setElevation(dp(1));
        return card;
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
