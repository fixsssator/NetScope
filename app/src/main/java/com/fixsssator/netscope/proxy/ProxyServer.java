package com.fixsssator.netscope.proxy;

import android.content.Context;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Local HTTP/HTTPS debugging proxy. Listens on loopback only. */
public final class ProxyServer {
    private final Context ctx;
    private final int port;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private ServerSocket server;
    private volatile boolean running;

    public ProxyServer(Context ctx, int port) {
        this.ctx = ctx.getApplicationContext();
        this.port = port;
    }

    public void start() throws IOException {
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 128);
        running = true;
        Thread t = new Thread(this::acceptLoop, "netscope-accept");
        t.setDaemon(true);
        t.start();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket s = server.accept();
                pool.execute(new ProxyConnection(ctx, s));
            } catch (IOException e) {
                if (!running) return;
            }
        }
    }

    public void stop() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
            // already closed
        }
        pool.shutdownNow();
    }
}
