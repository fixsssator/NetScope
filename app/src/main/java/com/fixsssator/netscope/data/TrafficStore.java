package com.fixsssator.netscope.data;

import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicLong;

/** In-memory ring buffer of captured traffic, shared by the proxy threads and the UI. */
public final class TrafficStore {
    public interface Listener {
        void onTrafficChanged();
    }

    private static final int MAX_ITEMS = 3000;
    private static final TrafficStore INSTANCE = new TrafficStore();

    public static TrafficStore get() {
        return INSTANCE;
    }

    private final List<Exchange> items = new ArrayList<>();
    private final AtomicLong ids = new AtomicLong(1);
    private final Set<Listener> listeners = new CopyOnWriteArraySet<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean pending;
    private final Runnable notifier = () -> {
        pending = false;
        for (Listener l : listeners) l.onTrafficChanged();
    };

    private TrafficStore() {}

    public Exchange create() {
        Exchange e = new Exchange(ids.getAndIncrement(), System.currentTimeMillis());
        synchronized (items) {
            items.add(e);
            if (items.size() > MAX_ITEMS) items.remove(0);
        }
        changed();
        return e;
    }

    public List<Exchange> snapshot() {
        synchronized (items) {
            return new ArrayList<>(items);
        }
    }

    public Exchange find(long id) {
        synchronized (items) {
            for (Exchange e : items) if (e.id == id) return e;
        }
        return null;
    }

    public void clear() {
        synchronized (items) {
            items.clear();
        }
        changed();
    }

    public int size() {
        synchronized (items) {
            return items.size();
        }
    }

    /** Called from any thread; listeners are notified on the main thread, throttled to ~6 per second. */
    public void changed() {
        if (!pending) {
            pending = true;
            main.postDelayed(notifier, 150);
        }
    }

    public void addListener(Listener l) {
        listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }
}
