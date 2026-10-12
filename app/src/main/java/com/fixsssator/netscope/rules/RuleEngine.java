package com.fixsssator.netscope.rules;

import android.content.Context;
import android.content.SharedPreferences;

import com.fixsssator.netscope.util.Headers;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Rewrite rules applied by the proxy: block, map local, map remote, header rewrite. */
public final class RuleEngine {
    public enum Type { BLOCK, MAP_LOCAL, MAP_REMOTE, REQUEST_HEADER, RESPONSE_HEADER }

    public static final class Rule {
        public Type type = Type.BLOCK;
        /** URL substring, or a regular expression when prefixed with "re:". */
        public String pattern = "";
        /** BLOCK: unused. MAP_LOCAL: response body. MAP_REMOTE: new URL. *_HEADER: "Name: value". */
        public String value = "";
        public boolean enabled = true;
        private transient Pattern compiled;

        boolean matches(String url) {
            if (pattern.isEmpty()) return false;
            if (pattern.startsWith("re:")) {
                try {
                    if (compiled == null) compiled = Pattern.compile(pattern.substring(3));
                    return compiled.matcher(url).find();
                } catch (Exception e) {
                    return false;
                }
            }
            return url.toLowerCase(Locale.ROOT).contains(pattern.toLowerCase(Locale.ROOT));
        }
    }

    private static RuleEngine instance;

    public static synchronized RuleEngine get(Context ctx) {
        if (instance == null) instance = new RuleEngine(ctx.getApplicationContext());
        return instance;
    }

    private final SharedPreferences prefs;
    private volatile List<Rule> rules = new ArrayList<>();

    private RuleEngine(Context ctx) {
        prefs = ctx.getSharedPreferences("netscope_rules", Context.MODE_PRIVATE);
        load();
    }

    public List<Rule> all() {
        return new ArrayList<>(rules);
    }

    public synchronized void add(Rule r) {
        List<Rule> copy = new ArrayList<>(rules);
        copy.add(r);
        rules = copy;
        save();
    }

    public synchronized void remove(int index) {
        List<Rule> copy = new ArrayList<>(rules);
        if (index >= 0 && index < copy.size()) copy.remove(index);
        rules = copy;
        save();
    }

    public synchronized void toggle(int index) {
        if (index >= 0 && index < rules.size()) {
            rules.get(index).enabled = !rules.get(index).enabled;
            save();
        }
    }

    /** First enabled BLOCK / MAP_LOCAL / MAP_REMOTE rule that matches this URL. */
    public Rule findAction(String url) {
        for (Rule r : rules) {
            if (!r.enabled) continue;
            if (r.type == Type.BLOCK || r.type == Type.MAP_LOCAL || r.type == Type.MAP_REMOTE) {
                if (r.matches(url)) return r;
            }
        }
        return null;
    }

    /** Applies all matching header rules of the given type ("Name: value"; empty value removes the header). */
    public void applyHeaders(Type type, String url, List<String[]> headers) {
        for (Rule r : rules) {
            if (!r.enabled || r.type != type || !r.matches(url)) continue;
            int i = r.value.indexOf(':');
            if (i <= 0) continue;
            String name = r.value.substring(0, i).trim();
            String val = r.value.substring(i + 1).trim();
            if (name.isEmpty()) continue;
            if (val.isEmpty()) Headers.remove(headers, name);
            else Headers.set(headers, name, val);
        }
    }

    private void load() {
        List<Rule> list = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs.getString("rules", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Rule r = new Rule();
                r.type = Type.valueOf(o.getString("type"));
                r.pattern = o.optString("pattern", "");
                r.value = o.optString("value", "");
                r.enabled = o.optBoolean("enabled", true);
                list.add(r);
            }
        } catch (Exception ignored) {
            // corrupted preferences: start empty
        }
        rules = list;
    }

    private void save() {
        try {
            JSONArray arr = new JSONArray();
            for (Rule r : rules) {
                JSONObject o = new JSONObject();
                o.put("type", r.type.name());
                o.put("pattern", r.pattern);
                o.put("value", r.value);
                o.put("enabled", r.enabled);
                arr.put(o);
            }
            prefs.edit().putString("rules", arr.toString()).apply();
        } catch (Exception ignored) {
            // JSON building cannot realistically fail
        }
    }
}
