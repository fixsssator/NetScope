package com.fixsssator.netscope.rules;

import com.fixsssator.netscope.data.Exchange;
import com.fixsssator.netscope.data.TrafficStore;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Holds requests that are paused at a breakpoint until the user edits and resumes (or drops) them. */
public final class BreakpointManager {
    /** A paused request. The UI edits the public fields, then calls resume() or drop(). */
    public static final class Pending {
        public final Exchange exchange;
        public volatile String method;
        public volatile String url;
        public volatile String headers;
        public volatile String body;
        public final boolean bodyEditable;
        public volatile boolean drop;
        private final CountDownLatch latch = new CountDownLatch(1);

        Pending(Exchange e, String method, String url, String headers, String body, boolean bodyEditable) {
            this.exchange = e;
            this.method = method;
            this.url = url;
            this.headers = headers;
            this.body = body;
            this.bodyEditable = bodyEditable;
        }

        public void resume() {
            latch.countDown();
        }

        public void dropRequest() {
            drop = true;
            latch.countDown();
        }
    }

    /** A paused request is released automatically after this long. */
    private static final long TIMEOUT_MINUTES = 5;
    private static final BreakpointManager INSTANCE = new BreakpointManager();

    public static BreakpointManager get() {
        return INSTANCE;
    }

    private final List<Pending> pending = new CopyOnWriteArrayList<>();

    private BreakpointManager() {}

    /** Blocks the calling proxy thread until the user resumes, drops, or the timeout expires. */
    public Pending pause(Exchange e, String method, String url, String headers, String body, boolean bodyEditable)
            throws InterruptedException {
        Pending p = new Pending(e, method, url, headers, body, bodyEditable);
        pending.add(p);
        e.state = Exchange.State.PAUSED;
        TrafficStore.get().changed();
        try {
            p.latch.await(TIMEOUT_MINUTES, TimeUnit.MINUTES);
        } finally {
            pending.remove(p);
            TrafficStore.get().changed();
        }
        return p;
    }

    public int count() {
        return pending.size();
    }

    public Pending first() {
        return pending.isEmpty() ? null : pending.get(0);
    }

    public Pending forExchange(long id) {
        for (Pending p : pending) if (p.exchange.id == id) return p;
        return null;
    }
}
