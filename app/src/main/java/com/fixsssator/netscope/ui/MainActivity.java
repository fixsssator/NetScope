package com.fixsssator.netscope.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.net.VpnService;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.view.ActionMode;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fixsssator.netscope.R;
import com.fixsssator.netscope.data.AppFilter;
import com.fixsssator.netscope.data.Exchange;
import com.fixsssator.netscope.data.TrafficFilter;
import com.fixsssator.netscope.data.TrafficStore;
import com.fixsssator.netscope.proxy.CertManager;
import com.fixsssator.netscope.rules.BreakpointManager;
import com.fixsssator.netscope.util.CurlExporter;
import com.fixsssator.netscope.util.Files;
import com.fixsssator.netscope.util.HarExporter;
import com.fixsssator.netscope.vpn.NetScopeVpnService;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public class MainActivity extends AppCompatActivity implements TrafficStore.Listener, ExchangeAdapter.Callbacks {
    private enum Group { NONE, DOMAIN, APP, APP_DOMAIN, IP }

    private final TrafficStore store = TrafficStore.get();
    private ExchangeAdapter adapter;
    private FloatingActionButton fab;
    private EditText search;
    private TextView banner;
    private TextView bpBanner;
    private TextView empty;
    private ChipGroup chips;
    private MaterialToolbar toolbar;

    private Group group = Group.NONE;
    private final Set<String> collapsed = new HashSet<>();
    private final Set<Long> selected = new HashSet<>();
    private final Map<String, List<Exchange>> groupMap = new LinkedHashMap<>();
    private List<Exchange> visible = new ArrayList<>(); // newest first, after filters
    private ActionMode actionMode;

    private final ActivityResultLauncher<Intent> vpnPermission = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK) launchService();
            });

    private final ActivityResultLauncher<Intent> appPicker = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                // App selection changed while capturing: restart so the VPN picks up the new app list.
                if (result.getResultCode() == RESULT_OK && NetScopeVpnService.isRunning()) launchService();
                refresh();
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        banner = findViewById(R.id.banner);
        bpBanner = findViewById(R.id.bp_banner);
        empty = findViewById(R.id.empty);
        search = findViewById(R.id.search);
        fab = findViewById(R.id.fab);
        chips = findViewById(R.id.chips);

        try {
            group = Group.valueOf(prefs().getString("group", Group.NONE.name()));
        } catch (IllegalArgumentException e) {
            group = Group.NONE;
        }

        RecyclerView list = findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ExchangeAdapter(this);
        list.setAdapter(adapter);

        setupChips();

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
        bpBanner.setOnClickListener(v -> {
            BreakpointManager.Pending p = BreakpointManager.get().first();
            if (p != null) openBreakpoint(p.exchange.id);
        });
        fab.setOnClickListener(v -> toggleCapture());
    }

    @Override
    protected void onStart() {
        super.onStart();
        store.addListener(this);
        syncChips();
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

    private SharedPreferences prefs() {
        return getSharedPreferences("netscope", Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------------ type chips

    private void setupChips() {
        for (int i = 0; i < TrafficFilter.TYPE_LABELS.length; i++) {
            Chip c = new Chip(this);
            c.setId(View.generateViewId());
            c.setText(TrafficFilter.TYPE_LABELS[i]);
            c.setCheckable(true);
            c.setTag(i);
            chips.addView(c);
        }
        syncChips();
        chips.setOnCheckedStateChangeListener((g, ids) -> {
            if (ids.isEmpty()) return;
            View v = g.findViewById(ids.get(0));
            if (v != null && v.getTag() instanceof Integer) {
                TrafficFilter.type = (Integer) v.getTag();
                refresh();
            }
        });
    }

    private void syncChips() {
        for (int i = 0; i < chips.getChildCount(); i++) {
            View c = chips.getChildAt(i);
            if (c instanceof Chip && c.getTag() instanceof Integer) {
                ((Chip) c).setChecked((Integer) c.getTag() == TrafficFilter.type);
            }
        }
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

    // ------------------------------------------------------------------ list, filters, grouping

    private static String subKey(String app, String domain) {
        return app + "\u0001" + domain;
    }

    private String appKey(Exchange e) {
        return e.appLabel != null ? e.appLabel : "Unknown app";
    }

    private String domainKey(Exchange e) {
        return e.host.isEmpty() ? "(no host)" : e.host;
    }

    private String ipKey(Exchange e) {
        return e.remoteIp != null ? e.remoteIp : "(no connection)";
    }

    private String simpleKey(Exchange e) {
        switch (group) {
            case APP:
                return appKey(e);
            case IP:
                return ipKey(e);
            default:
                return domainKey(e);
        }
    }

    private List<Exchange> bucket(String key) {
        List<Exchange> l = groupMap.get(key);
        if (l == null) {
            l = new ArrayList<>();
            groupMap.put(key, l);
        }
        return l;
    }

    private void refresh() {
        boolean running = NetScopeVpnService.isRunning();
        fab.setImageResource(running ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);

        String q = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        List<Exchange> all = store.snapshot();
        visible = new ArrayList<>();
        for (int i = all.size() - 1; i >= 0; i--) {
            if (TrafficFilter.matches(all.get(i), q)) visible.add(all.get(i));
        }

        List<ExchangeAdapter.Row> rows = new ArrayList<>();
        groupMap.clear();
        if (group == Group.NONE) {
            for (Exchange e : visible) rows.add(ExchangeAdapter.Row.item(e, 0, selected.contains(e.id)));
        } else if (group == Group.APP_DOMAIN) {
            Map<String, Map<String, List<Exchange>>> tree = new LinkedHashMap<>();
            for (Exchange e : visible) {
                String app = appKey(e);
                String dom = domainKey(e);
                Map<String, List<Exchange>> doms = tree.get(app);
                if (doms == null) {
                    doms = new LinkedHashMap<>();
                    tree.put(app, doms);
                }
                List<Exchange> l = doms.get(dom);
                if (l == null) {
                    l = new ArrayList<>();
                    doms.put(dom, l);
                }
                l.add(e);
                bucket(app).add(e);
                bucket(subKey(app, dom)).add(e);
            }
            for (Map.Entry<String, Map<String, List<Exchange>>> a : tree.entrySet()) {
                String app = a.getKey();
                List<Exchange> appItems = groupMap.get(app);
                boolean appCollapsed = collapsed.contains(app);
                rows.add(ExchangeAdapter.Row.group(app, app, 0, appItems.size(), appCollapsed, allSelected(appItems)));
                if (appCollapsed) continue;
                for (Map.Entry<String, List<Exchange>> d : a.getValue().entrySet()) {
                    String key = subKey(app, d.getKey());
                    boolean domCollapsed = collapsed.contains(key);
                    rows.add(ExchangeAdapter.Row.group(key, d.getKey(), 1, d.getValue().size(), domCollapsed,
                            allSelected(d.getValue())));
                    if (domCollapsed) continue;
                    for (Exchange e : d.getValue()) rows.add(ExchangeAdapter.Row.item(e, 2, selected.contains(e.id)));
                }
            }
        } else {
            for (Exchange e : visible) bucket(simpleKey(e)).add(e);
            for (Map.Entry<String, List<Exchange>> en : groupMap.entrySet()) {
                boolean isCollapsed = collapsed.contains(en.getKey());
                rows.add(ExchangeAdapter.Row.group(en.getKey(), en.getKey(), 0, en.getValue().size(),
                        isCollapsed, allSelected(en.getValue())));
                if (isCollapsed) continue;
                for (Exchange e : en.getValue()) rows.add(ExchangeAdapter.Row.item(e, 1, selected.contains(e.id)));
            }
        }
        adapter.submit(rows, actionMode != null);

        StringBuilder sub = new StringBuilder(running ? "Capturing " : "Stopped · ");
        sub.append(AppFilter.summary(this)).append(" · ");
        sub.append(visible.size() == all.size() ? all.size() + " requests" : visible.size() + " of " + all.size());
        if (TrafficFilter.isActive()) sub.append(" · filtered");
        switch (group) {
            case DOMAIN:
                sub.append(" · by domain");
                break;
            case APP:
                sub.append(" · by app");
                break;
            case APP_DOMAIN:
                sub.append(" · app → domain");
                break;
            case IP:
                sub.append(" · by IP");
                break;
            default:
                break;
        }
        toolbar.setTitle("NetScope");
        toolbar.setSubtitle(sub.toString());

        int paused = BreakpointManager.get().count();
        if (paused > 0) {
            bpBanner.setText("⏸ " + paused + (paused == 1 ? " request is" : " requests are")
                    + " paused at a breakpoint - tap to edit");
            bpBanner.setVisibility(View.VISIBLE);
        } else {
            bpBanner.setVisibility(View.GONE);
        }

        empty.setText(all.isEmpty()
                ? (running ? "Waiting for traffic…\nOpen a browser or app that uses the system proxy."
                : "No traffic yet.\nTap ▶ to start capturing.")
                : "Nothing matches the current filters.");
        empty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private boolean allSelected(List<Exchange> list) {
        if (list.isEmpty()) return false;
        for (Exchange e : list) if (!selected.contains(e.id)) return false;
        return true;
    }

    private void showFilterDialog() {
        ScrollView sv = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        sv.addView(box);

        TextView note = new TextView(this);
        note.setText("Nothing checked in a section = no restriction. (Content type: use the chips above the list.)");
        note.setTextSize(12);
        box.addView(note);
        CheckBox[] st = addSection(box, "Status", TrafficFilter.STATUS_LABELS, TrafficFilter.status);
        CheckBox[] me = addSection(box, "Method", TrafficFilter.METHOD_LABELS, TrafficFilter.method);
        CheckBox fav = new CheckBox(this);
        fav.setText("Favorites only ★");
        fav.setChecked(TrafficFilter.favoritesOnly);
        box.addView(fav);
        CheckBox hide = new CheckBox(this);
        hide.setText("Hide CONNECT tunnels");
        hide.setChecked(TrafficFilter.hideTunnels);
        box.addView(hide);

        new AlertDialog.Builder(this)
                .setTitle("Filters")
                .setView(sv)
                .setPositiveButton("Apply", (d, w) -> {
                    for (int i = 0; i < st.length; i++) TrafficFilter.status[i] = st[i].isChecked();
                    for (int i = 0; i < me.length; i++) TrafficFilter.method[i] = me[i].isChecked();
                    TrafficFilter.favoritesOnly = fav.isChecked();
                    TrafficFilter.hideTunnels = hide.isChecked();
                    refresh();
                })
                .setNeutralButton("Reset", (d, w) -> {
                    TrafficFilter.reset();
                    syncChips();
                    refresh();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private CheckBox[] addSection(LinearLayout box, String title, String[] labels, boolean[] state) {
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(14);
        t.setTypeface(null, Typeface.BOLD);
        t.setPadding(0, (int) (14 * getResources().getDisplayMetrics().density), 0, 0);
        box.addView(t);
        CheckBox[] boxes = new CheckBox[labels.length];
        for (int i = 0; i < labels.length; i++) {
            boxes[i] = new CheckBox(this);
            boxes[i].setText(labels[i]);
            boxes[i].setChecked(state[i]);
            box.addView(boxes[i]);
        }
        return boxes;
    }

    private void showGroupDialog() {
        String[] names = {"No grouping", "By domain", "By app", "By app → domain", "By IP"};
        new AlertDialog.Builder(this)
                .setTitle("Group requests")
                .setSingleChoiceItems(names, group.ordinal(), (d, which) -> {
                    group = Group.values()[which];
                    prefs().edit().putString("group", group.name()).apply();
                    collapsed.clear();
                    d.dismiss();
                    refresh();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ------------------------------------------------------------------ row callbacks / selection

    private void openBreakpoint(long id) {
        Intent i = new Intent(this, BreakpointActivity.class);
        i.putExtra(BreakpointActivity.EXTRA_ID, id);
        startActivity(i);
    }

    @Override
    public void onItemClick(Exchange e) {
        if (actionMode != null) {
            toggle(e.id);
            return;
        }
        if (e.state == Exchange.State.PAUSED && BreakpointManager.get().forExchange(e.id) != null) {
            openBreakpoint(e.id);
            return;
        }
        Intent i = new Intent(this, DetailActivity.class);
        i.putExtra(DetailActivity.EXTRA_ID, e.id);
        startActivity(i);
    }

    @Override
    public void onItemLongClick(Exchange e) {
        toggle(e.id);
    }

    @Override
    public void onGroupClick(String key) {
        if (actionMode != null) {
            toggleGroup(key);
        } else {
            if (!collapsed.remove(key)) collapsed.add(key);
            refresh();
        }
    }

    @Override
    public void onGroupLongClick(String key) {
        toggleGroup(key);
    }

    private void toggle(long id) {
        if (!selected.remove(id)) selected.add(id);
        selectionChanged();
    }

    private void toggleGroup(String key) {
        List<Exchange> items = groupMap.get(key);
        if (items == null) return;
        if (allSelected(items)) {
            for (Exchange e : items) selected.remove(e.id);
        } else {
            for (Exchange e : items) selected.add(e.id);
        }
        selectionChanged();
    }

    private void selectionChanged() {
        if (selected.isEmpty()) {
            if (actionMode != null) actionMode.finish();
            return;
        }
        if (actionMode == null) actionMode = startSupportActionMode(actionCallback);
        if (actionMode != null) actionMode.setTitle(selected.size() + " selected");
        refresh();
    }

    private final ActionMode.Callback actionCallback = new ActionMode.Callback() {
        @Override
        public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            mode.getMenuInflater().inflate(R.menu.selection_menu, menu);
            return true;
        }

        @Override
        public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
            return false;
        }

        @Override
        public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            return handleSelectionAction(item.getItemId());
        }

        @Override
        public void onDestroyActionMode(ActionMode mode) {
            selected.clear();
            actionMode = null;
            refresh();
        }
    };

    private List<Exchange> selectedExchanges() {
        List<Exchange> out = new ArrayList<>();
        for (Exchange e : store.snapshot()) if (selected.contains(e.id)) out.add(e);
        return out;
    }

    private boolean handleSelectionAction(int id) {
        if (id == R.id.sel_all) {
            for (Exchange e : visible) selected.add(e.id);
            selectionChanged();
            return true;
        }
        List<Exchange> sel = selectedExchanges();
        if (sel.isEmpty()) return true;

        if (id == R.id.sel_copy_domains) {
            String text = joinDomains(sel);
            copy("Domains", text);
            toast(text.split("\n").length + " unique domains copied");
        } else if (id == R.id.sel_copy_ips) {
            String text = joinIps(sel);
            if (text.isEmpty()) {
                toast("No IP addresses for the selected requests");
            } else {
                copy("IPs", text);
                toast(text.split("\n").length + " unique IPs copied");
            }
        } else if (id == R.id.sel_favorite) {
            boolean allFav = true;
            for (Exchange e : sel) if (!e.favorite) allFav = false;
            for (Exchange e : sel) e.favorite = !allFav;
            toast(allFav ? "Removed from favorites" : sel.size() + " added to favorites");
            refresh();
        } else if (id == R.id.sel_copy_urls) {
            copy("URLs", joinUrls(sel));
            toast(sel.size() + " URLs copied");
        } else if (id == R.id.sel_copy_curl) {
            StringBuilder sb = new StringBuilder();
            for (Exchange e : sel) sb.append(CurlExporter.toCurl(e)).append("\n\n");
            copy("cURL", sb.toString().trim());
            toast(sel.size() + " cURL commands copied");
        } else if (id == R.id.sel_export_domains) {
            saveText("netscope-domains-" + stamp() + ".txt", "text/plain", joinDomains(sel));
        } else if (id == R.id.sel_export_har) {
            exportHar(sel);
        } else if (id == R.id.sel_share) {
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_TEXT, joinUrls(sel));
            startActivity(Intent.createChooser(send, "Share URLs"));
        } else {
            return false;
        }
        return true;
    }

    private static String joinDomains(List<Exchange> list) {
        Set<String> hosts = new TreeSet<>();
        for (Exchange e : list) if (!e.host.isEmpty()) hosts.add(e.host);
        StringBuilder sb = new StringBuilder();
        for (String h : hosts) sb.append(h).append('\n');
        return sb.toString().trim();
    }

    private static String joinIps(List<Exchange> list) {
        Set<String> ips = new TreeSet<>();
        for (Exchange e : list) if (e.remoteIp != null) ips.add(e.remoteIp);
        StringBuilder sb = new StringBuilder();
        for (String ip : ips) sb.append(ip).append('\n');
        return sb.toString().trim();
    }

    private static String joinUrls(List<Exchange> list) {
        StringBuilder sb = new StringBuilder();
        for (Exchange e : list) sb.append(e.url()).append('\n');
        return sb.toString().trim();
    }

    private void copy(String label, String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText(label, text));
    }

    private static String stamp() {
        return new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
    }

    private void saveText(String name, String mime, String text) {
        new Thread(() -> {
            boolean ok = Files.saveToDownloads(this, name, mime, text.getBytes(StandardCharsets.UTF_8));
            runOnUiThread(() -> toast(ok ? "Saved Downloads/" + name : "Could not save " + name));
        }, "netscope-save").start();
    }

    // ------------------------------------------------------------------ certificate

    private void updateBanner() {
        new Thread(() -> {
            CertManager cm = CertManager.get(this);
            boolean decrypt = prefs().getBoolean("mitm", true);
            boolean ok = !decrypt || cm.isInstalled();
            runOnUiThread(() -> {
                if (ok) {
                    banner.setVisibility(View.GONE);
                } else {
                    banner.setText("HTTPS traffic can't be decrypted until the NetScope CA is installed. Tap for steps.");
                    banner.setVisibility(View.VISIBLE);
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
        menu.findItem(R.id.action_mitm).setChecked(prefs().getBoolean("mitm", true));
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_filters) {
            showFilterDialog();
        } else if (id == R.id.action_group) {
            showGroupDialog();
        } else if (id == R.id.action_apps) {
            appPicker.launch(new Intent(this, AppPickerActivity.class));
        } else if (id == R.id.action_ssl) {
            startActivity(new Intent(this, SslActivity.class));
        } else if (id == R.id.action_compose) {
            startActivity(new Intent(this, ComposeActivity.class));
        } else if (id == R.id.action_rules) {
            startActivity(new Intent(this, RulesActivity.class));
        } else if (id == R.id.action_mitm) {
            boolean on = !item.isChecked();
            item.setChecked(on);
            prefs().edit().putBoolean("mitm", on).apply();
            toast(on ? "HTTPS decryption on (applies to new connections)"
                    : "HTTPS decryption off - HTTPS is tunnelled untouched");
            updateBanner();
        } else if (id == R.id.action_cert) {
            showCertDialog();
        } else if (id == R.id.action_har) {
            List<Exchange> chronological = new ArrayList<>(visible);
            Collections.reverse(chronological);
            exportHar(chronological);
        } else if (id == R.id.action_clear) {
            selected.clear();
            if (actionMode != null) actionMode.finish();
            store.clear();
        } else {
            return super.onOptionsItemSelected(item);
        }
        return true;
    }

    private void exportHar(List<Exchange> list) {
        new Thread(() -> {
            boolean ok;
            String name = "netscope-" + stamp() + ".har";
            try {
                String har = HarExporter.build(list, versionName());
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
