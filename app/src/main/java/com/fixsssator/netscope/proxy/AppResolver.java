package com.fixsssator.netscope.proxy;

import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.system.OsConstants;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Finds which installed app opened a connection to the local proxy (works because NetScope is the active VPN). */
final class AppResolver {
    private static final Map<Integer, String[]> CACHE = new ConcurrentHashMap<>();
    private static final String[] UNKNOWN = {null, null};

    private AppResolver() {}

    /** @return {packageName, label}; both null when the owner cannot be determined. */
    static String[] resolve(Context ctx, Socket client) {
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            InetSocketAddress src = (InetSocketAddress) client.getRemoteSocketAddress();
            InetSocketAddress dst = (InetSocketAddress) client.getLocalSocketAddress();
            int uid = cm.getConnectionOwnerUid(OsConstants.IPPROTO_TCP, src, dst);
            if (uid < 0) return UNKNOWN;
            String[] cached = CACHE.get(uid);
            if (cached != null) return cached;
            PackageManager pm = ctx.getPackageManager();
            String[] pkgs = pm.getPackagesForUid(uid);
            if (pkgs == null || pkgs.length == 0) return UNKNOWN;
            String label = pkgs[0];
            try {
                label = pm.getApplicationLabel(pm.getApplicationInfo(pkgs[0], 0)).toString();
            } catch (PackageManager.NameNotFoundException ignored) {
                // keep package name as label
            }
            String[] result = {pkgs[0], label};
            CACHE.put(uid, result);
            return result;
        } catch (Exception e) {
            // SecurityException when we are not the active VPN, or the socket is already closed
            return UNKNOWN;
        }
    }
}
