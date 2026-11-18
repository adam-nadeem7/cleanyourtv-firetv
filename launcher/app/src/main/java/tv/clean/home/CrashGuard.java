package tv.clean.home;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * If the launcher keeps crashing (3 times in 10 minutes), the Home watch stands aside and leaves the
 * Fire TV home on screen, so a broken build can never hold the TV in a loop. An update starts afresh.
 * (After couch-tv's CrashGuard.)
 */
final class CrashGuard {
    private CrashGuard() {}

    private static final int LIMIT = 3;
    private static final long WINDOW_MS = 10 * 60 * 1000;

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("crashguard", Context.MODE_PRIVATE);
    }

    /** Call once per process: records an uncaught crash, then lets Android handle it as usual. */
    static void install(Context c) {
        Context app = c.getApplicationContext();
        Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        if (prev instanceof Recorder) return;
        Thread.setDefaultUncaughtExceptionHandler(new Recorder(app, prev));
    }

    static boolean standingAside(Context c) {
        SharedPreferences p = sp(c);
        long first = p.getLong("first", 0);
        return p.getInt("count", 0) >= LIMIT && System.currentTimeMillis() - first < WINDOW_MS;
    }

    static void reset(Context c) {
        sp(c).edit().clear().commit();
    }

    private static final class Recorder implements Thread.UncaughtExceptionHandler {
        private final Context c;
        private final Thread.UncaughtExceptionHandler prev;

        Recorder(Context c, Thread.UncaughtExceptionHandler prev) {
            this.c = c;
            this.prev = prev;
        }

        @Override
        public void uncaughtException(Thread t, Throwable e) {
            try {
                SharedPreferences p = sp(c);
                long now = System.currentTimeMillis(), first = p.getLong("first", 0);
                int n = now - first < WINDOW_MS ? p.getInt("count", 0) + 1 : 1;
                p.edit().putInt("count", n).putLong("first", n == 1 ? now : first).commit();
            } catch (Throwable ignored) {
            }
            if (prev != null) prev.uncaughtException(t, e);
        }
    }
}
