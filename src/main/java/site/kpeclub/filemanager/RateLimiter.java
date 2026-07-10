package site.kpeclub.filemanager;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Simple per-key fixed-window rate limiter. Not meant to be exact (a fixed
 * window lets a burst span two windows), just cheap and good enough to blunt
 * brute-force/scanning against auth endpoints without any external deps.
 */
public class RateLimiter {

    private static class Window {
        volatile long windowStart;
        final AtomicInteger count = new AtomicInteger(0);
    }

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final int maxAttempts;
    private final long windowMillis;

    public RateLimiter(int maxAttempts, long windowMillis) {
        this.maxAttempts = maxAttempts;
        this.windowMillis = windowMillis;
    }

    /** Returns true if this key is still within its allowance. */
    public boolean allow(String key) {
        long now = System.currentTimeMillis();
        Window w = windows.computeIfAbsent(key, k -> {
            Window nw = new Window();
            nw.windowStart = now;
            return nw;
        });
        synchronized (w) {
            if (now - w.windowStart > windowMillis) {
                w.windowStart = now;
                w.count.set(0);
            }
            return w.count.incrementAndGet() <= maxAttempts;
        }
    }

    /** Drops stale entries so this doesn't grow unbounded over a long uptime. */
    public void cleanup() {
        long now = System.currentTimeMillis();
        windows.values().removeIf(w -> now - w.windowStart > windowMillis * 4);
    }
}
