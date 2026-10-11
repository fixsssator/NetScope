package com.fixsssator.netscope;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

public class DnsCaptureService extends VpnService {
    public static final String ACTION_START = "com.fixsssator.netscope.START_DNS";
    public static final String ACTION_STOP = "com.fixsssator.netscope.STOP_DNS";
    public static final String EXTRA_PACKAGES = "packages";
    private static final String TAG = "NetScopeDns";
    private static final String CHANNEL = "netscope_dns";
    private static final String PREFS = "netscope_capture";
    private static final String HISTORY = "dns_history";
    private static final String VPN_IP = "10.111.0.2";
    private static final String DNS_IP = "10.111.0.1";
    private static final String UPSTREAM = "1.1.1.1";
    private static final int NOTIFICATION_ID = 41;
    private ParcelFileDescriptor vpn;
    private Thread worker;
    private volatile boolean running;

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopCapture(); stopSelf(); return START_NOT_STICKY;
        }
        if (running) return START_STICKY;
        ArrayList<String> packages = intent == null ? null : intent.getStringArrayListExtra(EXTRA_PACKAGES);
        if (packages == null || packages.isEmpty()) { stopSelf(); return START_NOT_STICKY; }
        try {
            Builder builder = new Builder().setSession("NetScope DNS observation")
                    .setMtu(1500).addAddress(VPN_IP, 32).addDnsServer(DNS_IP).addRoute(DNS_IP, 32);
            int count = 0;
            for (String name : packages) {
                try {
                    getPackageManager().getApplicationInfo(name, 0);
                    builder.addAllowedApplication(name);
                    count++;
                } catch (PackageManager.NameNotFoundException ignored) { }
            }
            if (count == 0) { stopSelf(); return START_NOT_STICKY; }
            vpn = builder.establish();
            if (vpn == null) { stopSelf(); return START_NOT_STICKY; }
            createChannel();
            startForeground(NOTIFICATION_ID, notification());
            running = true;
            worker = new Thread(this::captureLoop, "NetScope-DNS");
            worker.start();
        } catch (Exception e) {
            Log.e(TAG, "Could not start DNS observation", e);
            stopCapture(); stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void captureLoop() {
        try (FileInputStream input = new FileInputStream(vpn.getFileDescriptor());
             FileOutputStream output = new FileOutputStream(vpn.getFileDescriptor())) {
            byte[] buffer = new byte[32767];
            while (running) {
                int length = input.read(buffer);
                if (length <= 0) continue;
                Packet p = parse(buffer, length);
                if (p == null) continue;
                String domain = questionName(p.dns);
                if (domain != null) save(domain);
                byte[] response = forward(p.dns);
                if (response != null) {
                    byte[] reply = responsePacket(buffer, p, response);
                    if (reply != null) output.write(reply);
                }
            }
        } catch (IOException e) {
            if (running) Log.w(TAG, "DNS tunnel stopped after I/O error", e);
        }
    }

    private Packet parse(byte[] b, int length) {
        if (length < 28 || (b[0] >> 4 & 15) != 4) return null;
        int ihl = (b[0] & 15) * 4;
        if (ihl < 20 || length < ihl + 8 || (b[9] & 255) != 17) return null;
        int total = u16(b, 2);
        if (total < ihl + 8 || total > length) return null;
        int udp = ihl;
        if (u16(b, udp + 2) != 53) return null;
        String dst = (b[16] & 255) + "." + (b[17] & 255) + "." + (b[18] & 255) + "." + (b[19] & 255);
        if (!DNS_IP.equals(dst)) return null;
        int udpLength = u16(b, udp + 4);
        if (udpLength < 20 || udp + udpLength > total) return null;
        byte[] dns = new byte[udpLength - 8];
        System.arraycopy(b, udp + 8, dns, 0, dns.length);
        if (dns.length < 12 || (dns[2] & 0x80) != 0) return null;
        return new Packet(u16(b, udp), new byte[]{b[12],b[13],b[14],b[15]}, dns);
    }

    private byte[] forward(byte[] query) {
        try (DatagramSocket socket = new DatagramSocket()) {
            if (!protect(socket)) return null;
            socket.setSoTimeout(2500);
            InetAddress upstream = InetAddress.getByName(UPSTREAM);
            socket.send(new DatagramPacket(query, query.length, new InetSocketAddress(upstream, 53)));
            byte[] buf = new byte[4096];
            DatagramPacket response = new DatagramPacket(buf, buf.length);
            socket.receive(response);
            byte[] result = new byte[response.getLength()];
            System.arraycopy(response.getData(), response.getOffset(), result, 0, result.length);
            return result;
        } catch (SocketTimeoutException e) {
            Log.d(TAG, "DNS upstream timeout"); return null;
        } catch (IOException e) {
            Log.w(TAG, "DNS upstream request failed", e); return null;
        }
    }

    private byte[] responsePacket(byte[] request, Packet p, byte[] dns) {
        int total = 28 + dns.length;
        if (total > 65535) return null;
        byte[] out = new byte[total];
        out[0] = 0x45; put16(out, 2, total); put16(out, 4, u16(request, 4));
        put16(out, 6, 0x4000); out[8] = 64; out[9] = 17;
        System.arraycopy(request, 16, out, 12, 4);
        System.arraycopy(p.sourceIp, 0, out, 16, 4);
        put16(out, 10, checksum(out, 0, 20));
        put16(out, 20, 53); put16(out, 22, p.sourcePort);
        put16(out, 24, 8 + dns.length);
        System.arraycopy(dns, 0, out, 28, dns.length);
        return out;
    }

    private String questionName(byte[] dns) {
        if (dns.length < 17 || u16(dns, 4) < 1) return null;
        StringBuilder name = new StringBuilder();
        int offset = 12, labels = 0;
        while (offset < dns.length) {
            int size = dns[offset++] & 255;
            if (size == 0) break;
            if ((size & 0xC0) != 0 || size > 63 || offset + size > dns.length) return null;
            if (name.length() > 0) name.append('.');
            name.append(new String(dns, offset, size, StandardCharsets.US_ASCII));
            offset += size;
            if (++labels > 127) return null;
        }
        if (name.length() == 0 || offset + 4 > dns.length) return null;
        return name.toString().toLowerCase(Locale.ROOT);
    }

    private void save(String domain) {
        synchronized (DnsCaptureService.class) {
            android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
            JSONArray old;
            try { old = new JSONArray(prefs.getString(HISTORY, "[]")); }
            catch (JSONException e) { old = new JSONArray(); }
            JSONArray next = new JSONArray();
            try {
                for (int i = Math.max(0, old.length() - 498); i < old.length(); i++) next.put(old.get(i));
                JSONObject event = new JSONObject();
                event.put("domain", domain);
                event.put("timestamp", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date()));
                next.put(event);
                prefs.edit().putString(HISTORY, next.toString()).apply();
            } catch (JSONException e) { Log.w(TAG, "Unable to persist DNS history", e); }
        }
    }

    private int checksum(byte[] data, int offset, int length) {
        long sum = 0;
        for (int i = offset; i < offset + length; i += 2) {
            int word = (data[i] & 255) << 8;
            if (i + 1 < offset + length) word |= data[i + 1] & 255;
            sum += word;
        }
        while ((sum >> 16) != 0) sum = (sum & 65535) + (sum >> 16);
        return (int) (~sum) & 65535;
    }
    private int u16(byte[] b, int i) { return ((b[i] & 255) << 8) | (b[i+1] & 255); }
    private void put16(byte[] b, int i, int v) { b[i]=(byte)(v>>8); b[i+1]=(byte)v; }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "NetScope DNS observation", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Shown while local DNS observation is active.");
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }
    private Notification notification() {
        Intent stop = new Intent(this, DnsCaptureService.class); stop.setAction(ACTION_STOP);
        PendingIntent pending = PendingIntent.getService(this, 42, stop, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        return builder.setContentTitle("NetScope is observing DNS")
                .setContentText("Selected apps only · use Stop to end the session")
                .setSmallIcon(android.R.drawable.stat_sys_warning).setOngoing(true)
                .addAction(android.R.drawable.ic_media_pause, "Stop", pending).build();
    }
    private void stopCapture() {
        running = false;
        if (worker != null) worker.interrupt();
        if (vpn != null) { try { vpn.close(); } catch (IOException ignored) { } vpn = null; }
        stopForeground(true);
    }
    @Override public void onRevoke() { stopCapture(); stopSelf(); super.onRevoke(); }
    @Override public void onDestroy() { stopCapture(); super.onDestroy(); }

    private static final class Packet {
        final int sourcePort; final byte[] sourceIp; final byte[] dns;
        Packet(int sourcePort, byte[] sourceIp, byte[] dns) { this.sourcePort=sourcePort; this.sourceIp=sourceIp; this.dns=dns; }
    }
}