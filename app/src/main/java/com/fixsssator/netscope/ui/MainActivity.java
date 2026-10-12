package com.fixsssator.netscope.ui;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fixsssator.netscope.R;
import com.fixsssator.netscope.data.Exchange;
import com.fixsssator.netscope.data.TrafficStore;
import com.fixsssator.netscope.proxy.CertManager;
import com.fixsssator.netscope.util.Files;
import com.fixsssator.netscope.util.HarExporter;
import com.fixsssator.netscope.vpn.NetScopeVpnService;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.HashSet;
import java.util.Comparator;

public class MainActivity extends AppCompatActivity implements TrafficStore.Listener {
    private final TrafficStore store = TrafficStore.get();
    private ExchangeAdapter adapter;
    private FloatingActionButton fab;
    private EditText search;
    private TextView banner;
    private TextView empty;
    private MaterialToolbar toolbar;

    private final ActivityResultLauncher<Intent> vpnPermission = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK) launchService();
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        banner = findViewById(R.id.banner);
        empty = findViewById(R.id.empty);
        search = findViewById(R.id.search);
        fab = findViewById(R.id.fab);

        RecyclerView list = findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ExchangeAdapter(e -> {
            Intent i = new Intent(this, DetailActivity.class);
            i.putExtra(DetailActivity.EXTRA_ID, e.id);
            startActivity(i);
        });
        list.setAdapter(adapter);

        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                refresh();
            }
        });

        banner.setOnClickListener(v -> showCertDialog());
        fab.setOnClickListener(v -> toggleCapture());
    }

    @Override
    protected void onStart() {
        super.onStart();
        store.addListener(this);
        refresh();
        updateBanner();
    }

    @Override
    protected void onStop() {
        store.removeListener(this);
        super.onStop();
    }

    @Override
    public void onTrafficChanged() {
        refresh();
    }

    // ------------------------------------------------------------------ capture control

    private void toggleCapture() {
        if (NetScopeVpnService.isRunning()) {
            Intent stop = new Intent(this, NetScopeVpnService.class).setAction(NetScopeVpnService.ACTION_STOP);
            startService(stop);
            return;
        }
        // Create the CA first (RSA key generation can take a moment), then ask for VPN permission.
        fab.setEnabled(false);
        new Thread(() -> {
            try {
                CertManager.get(this).ensure();
            } catch (Exception e) {
                runOnUiThread(() -> {
                    fab.setEnabled(true);
                    toast("Could not create certificate: " + e.getMessage());
                });
                return;
            }
            runOnUiThread(() -> {
                fab.setEnabled(true);
                updateBanner();
                Intent prepare = VpnService.prepare(this);
                if (prepare != null) vpnPermission.launch(prepare);
                else launchService();
            });
        }, "netscope-ca-init").start();
    }

    private void launchService() {
        startService(new Intent(this, NetScopeVpnService.class).setAction(NetScopeVpnService.ACTION_START));
    }

    // ------------------------------------------------------------------ list

    private void refresh() {
        boolean running = NetScopeVpnService.isRunning();
        fab.setImageResource(running ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);

        String q = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        List<Exchange> all = store.snapshot();
        List<Exchange> shown = new ArrayList<>();
        for (Exchange e : all) if (matches(e, q)) shown.add(e);
        Collections.reverse(shown);
        adapter.submit(shown);

        toolbar.setTitle("NetScope");
        toolbar.setSubtitle(running
                ? "Capturing · 127.0.0.1:" + NetScopeVpnService.PORT + " · " + all.size() + " requests"
                : "Stopped · " + all.size() + " requests");
        empty.setText(running
                ? "Waiting for traffic…\nOpen a browser or app that uses the system proxy."
                : "No traffic yet.\nTap ▶ to start capturing.");
        empty.setVisibility(shown.isEmpty() ? TextView.VISIBLE : TextView.GONE);
    }

    private static boolean matches(Exchange e, String q) {
        if (q.isEmpty()) return true;
        String hay = (e.method + " " + e.status + " " + e.host + e.path + " "
                + (e.appLabel == null ? "" : e.appLabel)).toLowerCase(Locale.ROOT);
        for (String tok : q.split("\\s+")) {
            if (tok.matches("[1-5]xx")) {
                if (e.status / 100 != tok.charAt(0) - '0') return false;
            } else if (!hay.contains(tok)) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ certificate

    private void updateBanner() {
        new Thread(() -> {
            CertManager cm = CertManager.get(this);
            boolean decrypt = getSharedPreferences("netscope", Context.MODE_PRIVATE).getBoolean("mitm", true);
            boolean ok = !decrypt || cm.isInstalled();
            runOnUiThread(() -> {
                if (ok) {
                    banner.setVisibility(TextView.GONE);
                } else {
                    banner.setText("HTTPS traffic can't be decrypted until the NetScope CA is installed. Tap for steps.");
                    banner.setVisibility(TextView.VISIBLE);
                }
            });
        }, "netscope-ca-check").start();
    }

    private void showCertDialog() {
        new Thread(() -> {
            CertManager cm = CertManager.get(this);
            try {
                cm.ensure();
            } catch (Exception e) {
                runOnUiThread(() -> toast("Could not create certificate: " + e.getMessage()));
                return;
            }
            boolean installed = cm.isInstalled();
            runOnUiThread(() -> new AlertDialog.Builder(this)
                    .setTitle("NetScope CA certificate")
                    .setMessage((installed ? "Status: installed ✓\n\n" : "Status: not installed\n\n")
                            + "To read HTTPS traffic, Android must trust the local NetScope certificate authority. "
                            + "The key is generated on this device and never leaves it.\n\n"
                            + "1. Tap \"Save to Downloads\".\n"
                            + "2. Open Settings → Security → Encryption & credentials → Install a certificate → CA certificate "
                            + "(exact path depends on your device).\n"
                            + "3. Pick NetScope-CA.crt.\n\n"
                            + "Note: since Android 7 many apps ignore user-installed CAs. Browsers such as Chrome work; "
                            + "apps with certificate pinning cannot be decrypted.")
                    .setPositiveButton("Save to Downloads", (d, w) -> saveCertificate())
                    .setNeutralButton("Security settings", (d, w) ->
                            startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS)))
                    .setNegativeButton("Close", null)
                    .show());
        }, "netscope-ca-dialog").start();
    }

    private void saveCertificate() {
        new Thread(() -> {
            boolean ok;
            try {
                String pem = CertManager.get(this).caPem();
                ok = Files.saveToDownloads(this, "NetScope-CA.crt", "application/x-x509-ca-cert",
                        pem.getBytes(StandardCharsets.US_ASCII));
            } catch (Exception e) {
                ok = false;
            }
            boolean saved = ok;
            runOnUiThread(() -> toast(saved ? "Saved Downloads/NetScope-CA.crt" : "Could not save the certificate"));
        }, "netscope-ca-save").start();
    }

    // ------------------------------------------------------------------ menu

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem item = menu.findItem(R.id.action_mitm);
        item.setChecked(getSharedPreferences("netscope", Context.MODE_PRIVATE).getBoolean("mitm", true));
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_compose) {
            startActivity(new Intent(this, ComposeActivity.class));
        } else if (id == R.id.action_rules) {
            startActivity(new Intent(this, RulesActivity.class));
        } else if (id == R.id.action_apps) {
            showAppSelectionDialog();
        } else if (id == R.id.action_mitm) {
            boolean on = !item.isChecked();
            item.setChecked(on);
            getSharedPreferences("netscope", Context.MODE_PRIVATE).edit().putBoolean("mitm", on).apply();
            toast(on ? "HTTPS decryption on (applies to new connections)"
                    : "HTTPS decryption off - HTTPS is tunnelled untouched");
            updateBanner();
        } else if (id == R.id.action_cert) {
            showCertDialog();
        } else if (id == R.id.action_har) {
            exportHar();
        } else if (id == R.id.action_clear) {
            store.clear();
        } else {
            return super.onOptionsItemSelected(item);
        }
        return true;
    }


    /**
     * Per-app capture selector with live search and a toggle to hide system apps.
     */
    private void showAppSelectionDialog() {
        PackageManager pm = getPackageManager();
        List<ApplicationInfo> apps = new ArrayList<>();
        try {
            apps.addAll(pm.getInstalledApplications(PackageManager.GET_META_DATA));
        } catch (RuntimeException e) {
            toast("Could not list installed apps: " + e.getMessage());
            return;
        }
        apps.removeIf(info -> getPackageName().equals(info.packageName));
        apps.sort((a, b) -> String.valueOf(pm.getApplicationLabel(a))
                .compareToIgnoreCase(String.valueOf(pm.getApplicationLabel(b))));

        Set<String> saved = getSharedPreferences("netscope", Context.MODE_PRIVATE)
                .getStringSet("capture_packages", Collections.emptySet());
        Set<String> selected = new HashSet<>(saved);
        EditText filter = new EditText(this);
        filter.setSingleLine(true);
        filter.setHint("Search apps or package names");

        CheckBox hideSystem = new CheckBox(this);
        hideSystem.setText("Hide system apps");
        hideSystem.setChecked(true);

        TextView count = new TextView(this);
        count.setPadding(0, dp(4), 0, dp(6));

        LinearLayout appRows = new LinearLayout(this);
        appRows.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(appRows, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        scroll.setFillViewport(false);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int horizontal = dp(20);
        content.setPadding(horizontal, dp(8), horizontal, 0);
        content.addView(filter, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        content.addView(hideSystem, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        content.addView(count, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        content.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(360)));

        final Runnable[] render = new Runnable[1];
        render[0] = () -> {
            String query = filter.getText().toString().trim().toLowerCase(Locale.ROOT);
            boolean hide = hideSystem.isChecked();
            appRows.removeAllViews();
            int visible = 0;
            for (ApplicationInfo info : apps) {
                boolean systemApp = (info.flags & (ApplicationInfo.FLAG_SYSTEM
                        | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
                if (hide && systemApp) continue;
                String label = String.valueOf(pm.getApplicationLabel(info));
                if (!query.isEmpty() && !label.toLowerCase(Locale.ROOT).contains(query)
                        && !info.packageName.toLowerCase(Locale.ROOT).contains(query)) continue;

                CheckBox row = new CheckBox(this);
                row.setText(label + "\n" + info.packageName);
                row.setTextSize(14);
                row.setMinHeight(dp(52));
                row.setPadding(0, dp(4), 0, dp(4));
                row.setChecked(selected.contains(info.packageName));
                row.setOnCheckedChangeListener((button, isChecked) -> {
                    if (isChecked) selected.add(info.packageName);
                    else selected.remove(info.packageName);
                    count.setText(visibleCount(apps, hideSystem.isChecked(), filter.getText().toString())
                            + " apps shown · " + selected.size() + " selected");
                });
                appRows.addView(row, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
                visible++;
            }
            count.setText(visible + " apps shown · " + selected.size() + " selected");
        };

        filter.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { render[0].run(); }
            @Override public void afterTextChanged(Editable s) {}
        });
        hideSystem.setOnCheckedChangeListener((button, checked) -> render[0].run());
        render[0].run();

        new AlertDialog.Builder(this)
                .setTitle("Apps to capture")
                .setView(content)
                .setNeutralButton("Capture all", (dialog, which) -> {
                    getSharedPreferences("netscope", Context.MODE_PRIVATE).edit()
                            .putStringSet("capture_packages", new HashSet<>()).apply();
                    applyCaptureAppSelection();
                })
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (dialog, which) -> {
                    getSharedPreferences("netscope", Context.MODE_PRIVATE).edit()
                            .putStringSet("capture_packages", new HashSet<>(selected)).apply();
                    applyCaptureAppSelection();
                })
                .show();
    }

    private int visibleCount(List<ApplicationInfo> apps, boolean hideSystem, String queryText) {
        String query = queryText == null ? "" : queryText.trim().toLowerCase(Locale.ROOT);
        int count = 0;
        PackageManager pm = getPackageManager();
        for (ApplicationInfo info : apps) {
            if (hideSystem && (info.flags & (ApplicationInfo.FLAG_SYSTEM
                    | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0) continue;
            String label = String.valueOf(pm.getApplicationLabel(info)).toLowerCase(Locale.ROOT);
            if (query.isEmpty() || label.contains(query)
                    || info.packageName.toLowerCase(Locale.ROOT).contains(query)) count++;
        }
        return count;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void applyCaptureAppSelection() {
        Set<String> selected = getSharedPreferences("netscope", Context.MODE_PRIVATE)
                .getStringSet("capture_packages", Collections.emptySet());
        if (NetScopeVpnService.isRunning()) {
            // Re-establish the VPN with the updated allow-list.
            startService(new Intent(this, NetScopeVpnService.class)
                    .setAction(NetScopeVpnService.ACTION_RESTART));
            toast(selected.isEmpty()
                    ? "Capture all apps selected. Reconfiguring capture…"
                    : "Capture restricted to " + selected.size() + " app(s). Reconfiguring…");
        } else {
            toast(selected.isEmpty()
                    ? "Capture all apps selected."
                    : "Capture restricted to " + selected.size() + " app(s).");
        }
        invalidateOptionsMenu();
        refresh();
    }

    private void exportHar() {
        List<Exchange> all = store.snapshot();
        new Thread(() -> {
            boolean ok;
            String name = "netscope-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".har";
            try {
                String har = HarExporter.build(all, versionName());
                ok = Files.saveToDownloads(this, name, "application/json", har.getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                ok = false;
            }
            boolean saved = ok;
            runOnUiThread(() -> toast(saved ? "Saved Downloads/" + name : "HAR export failed"));
        }, "netscope-har").start();
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
