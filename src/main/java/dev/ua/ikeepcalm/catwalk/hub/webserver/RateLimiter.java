package dev.ua.ikeepcalm.catwalk.hub.webserver;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class RateLimiter {

    private static final long WINDOW_MILLIS = 60_000;
    private static final int MAX_TRACKED_KEYS = 10_000;

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public boolean tryAcquire(String key, int limitPerWindow) {
        return count(key, true) <= limitPerWindow;
    }

    public boolean isExceeded(String key, int limitPerWindow) {
        return count(key, false) >= limitPerWindow;
    }

    public void record(String key) {
        count(key, true);
    }

    private int count(String key, boolean increment) {
        long now = System.currentTimeMillis();
        if (windows.size() > MAX_TRACKED_KEYS) {
            windows.values().removeIf(window -> now - window.start >= WINDOW_MILLIS);
        }
        Window window = windows.compute(key, (k, existing) ->
                existing == null || now - existing.start >= WINDOW_MILLIS ? new Window(now) : existing);
        synchronized (window) {
            return increment ? ++window.count : window.count;
        }
    }

    private static class Window {
        private final long start;
        private int count;

        private Window(long start) {
            this.start = start;
        }
    }
}
