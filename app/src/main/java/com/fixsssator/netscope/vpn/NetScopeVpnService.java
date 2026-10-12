package com.fixsssator.netscope.vpn;

import android.content.Intent;\nimport android.content.pm.PackageManager;
import android.net.ProxyInfo;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import com.fixsssator.netscope.data.TrafficStore;
import com.fixsssator.netscope.proxy.CertManager;
import com.fixsssator.netscope.proxy.ProxyServer;

import java.io.IOException;

/**
 * Starts the local proxy and registers it as the system HTTP proxy through a VPN network
 * (VpnService.Builder#setHttpProxy, Android 10+). No packets are routed through the tunnel:
 * apps that honour the system proxy (browsers, OkHttp, HttpURLConnection, ...) send their traffic to 127.0.0.1.
 * NetScope itself is excluded so its own upstream connections do not loop back into the proxy.
 */
public class NetScopeVpnService extends VpnService {
    public static final String ACTION_START = "com.fixsssator.netscope.START";
    public static final String ACTION_STOP = "com.fixsssator.netscope.STOP";
    public static final String ACTION_RESTART = "com.fixsssator.netscope.RESTART";
    public static final int PORT = 8899;

    private static final String TAG = "NetScopeVpn";
    private static volatile boolean running;

    public static boolean isRunning() {
        return running;
    }

    private ParcelFileDescriptor tun;
    private ProxyServer proxy;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            stopAll();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!running) startAll();
        return START_NOT_STICKY;
    }

    private void startAll() {
        try {
            CertManager.get(this).ensure();
            com.fixsssator.netscope.proxy.ProxyControl.resetBypass();

            proxy = new ProxyServer(getApplicationContext(), PORT);
            proxy.start();

            Builder b = new Builder()
                    .setSession("NetScope")
                    .addAddress("10.77.0.1", 32)
                    .addDisallowedApplication(getPackageName())
                    .setHttpProxy(ProxyInfo.buildDirectProxy("127.0.0.1", PORT));
            b.allowBypass();
            tun = b.establish();
            if (tun == null) throw new IllegalStateException("VPN permission was not granted");
            running = true;
        } catch (Exception e) {
            Log.e(TAG, "start failed", e);
            stopAll();
            stopSelf();
        }
        TrafficStore.get().changed();
    }

    private void stopAll() {
        running = false;
        if (proxy != null) {
            proxy.stop();
            proxy = null;
        }
        if (tun != null) {
            try {
                tun.close();
            } catch (IOException ignored) {
                // already closed
            }
            tun = null;
        }
        TrafficStore.get().changed();
    }

    @Override
    public void onRevoke() {
        stopAll();
        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        stopAll();
        super.onDestroy();
    }
}
