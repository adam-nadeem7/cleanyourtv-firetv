package tv.clean.home;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Fire OS protects com.amazon.tv.launcher, so it stays the system Home. This service puts the clean
 * home in front whenever the Fire TV home comes up: after the Home button, at boot, when an app
 * exits to it. Only a visit the user started from "Fire TV UI" is let through, and that ends as soon
 * as any other app comes to the front.
 *
 * What Fire OS 8 does not allow (tested on the Xiaomi L65MB-FAEU): KEYCODE_HOME and the other remote
 * keys never reach accessibility key filters, and the "homekey" CLOSE_SYSTEM_DIALOGS broadcast needs
 * an Amazon signature, and the HOME_PRESSED broadcasts are sent only to Amazon's own apps. So the window
 * event is the signal. A Home press while the Fire TV home is already in front gives no window event;
 * for that case only (a "Fire TV UI" visit), the service reads the system log, where Fire OS writes
 * "START ... category.HOME ... com.amazon.tv.launcher ... from uid 0" for every real Home press, and
 * stops reading as soon as the visit ends. Needs READ_LOGS, granted over adb (build-and-install.sh);
 * without it, the "Home" app in the Your Apps row still leads back.
 *
 * From couch-tv's FireWatch: the Fire TV home can come up at boot before this service is bound, so
 * its window event is missed; bound within two minutes of a restart, the service puts the clean home
 * up once by itself. Quick repeated events are debounced. If the launcher keeps crashing, the
 * service stands aside (CrashGuard). WatchKeeper keeps it switched on and bound.
 *
 * Enable over adb (build-and-install.sh does this, and grants WRITE_SECURE_SETTINGS for WatchKeeper):
 *   settings put secure enabled_accessibility_services tv.clean.home/.HomeKeyService
 *   settings put secure accessibility_enabled 1
 */
public class HomeKeyService extends AccessibilityService {
    private static final String TAG = "CleanHome";
    private static final long BOOT_WINDOW_MS = 2 * 60_000L, DEBOUNCE_MS = 1200L;

    /** Set while Android has this service bound. */
    static volatile boolean connected;
    private long last;
    private final Handler main = new Handler(Looper.getMainLooper());
    /** The logcat reader, running only while the Fire TV home is open on purpose. */
    private Process logcat;

    /** Windows that sit on top of the Fire TV home without leaving it. */
    private static final String[] OVERLAYS = {
            Apps.AMAZON_HOME, "android", "com.android.systemui", "com.amazon.tv.quicksettings",
            "com.amazon.tv.ime", "com.amazon.tv.notificationcenter", "com.amazon.vizzini"};

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        connected = true;
        CrashGuard.install(this);
        Log.i(TAG, "Home watch connected");
        if (SystemClock.elapsedRealtime() < BOOT_WINDOW_MS && !CrashGuard.standingAside(this)) {
            Log.i(TAG, "bound just after a restart: putting the clean home in front");
            openHome();
        }
    }

    @Override
    public boolean onUnbind(Intent intent) {
        connected = false;
        watchLog(false);
        return super.onUnbind(intent);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence p = e.getPackageName(), cls = e.getClassName();
        if (p == null || cls == null) return;
        String pkg = p.toString();
        if (Apps.AMAZON_HOME.equals(pkg)) {
            // Only the home screen itself; Amazon's Settings live in the same package.
            if (!cls.toString().contains("HomeActivity")) return;
            if (Prefs.amazonHomeAllowed(this)) {
                watchLog(true);
                return;
            }
            if (CrashGuard.standingAside(this)) return;
            if (SystemClock.elapsedRealtime() - last < DEBOUNCE_MS) return;
            Log.i(TAG, "covering Fire TV home");
            openHome();
            return;
        }
        if (Prefs.amazonHomeAllowed(this) && !isOverlay(pkg)) {
            Log.i(TAG, "left the Fire TV home for " + pkg);
            Prefs.setAmazonHomeAllowed(this, false);
            watchLog(false);
        }
    }

    /** Read Fire OS's activity log while the visit lasts; a Home press there brings the clean home back. */
    private void watchLog(boolean on) {
        if (on == (logcat != null)) return;
        if (!on) {
            logcat.destroy();
            logcat = null;
            return;
        }
        if (checkSelfPermission("android.permission.READ_LOGS") != PackageManager.PERMISSION_GRANTED) return;
        try {
            // -T 1: start at the newest line (skipped below), so nothing from before the visit counts.
            Process p = new ProcessBuilder("logcat", "-v", "brief", "-T", "1", "-s", "ActivityTaskManager:I")
                    .redirectErrorStream(true).start();
            logcat = p;
            Log.i(TAG, "Fire TV home open: watching for Home presses");
            new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    String line;
                    boolean first = true;
                    while ((line = r.readLine()) != null) {
                        if (first) { first = false; continue; }
                        if (isHomePress(line)) main.post(() -> homePressed(p));
                    }
                } catch (Exception ignored) {
                }
            }, "home-press-watch").start();
        } catch (Exception ex) {
            Log.w(TAG, "could not read the log: " + ex.getMessage());
        }
    }

    /** Fire OS's own Home start (from uid 0); our "Fire TV home" opening comes from this app's uid. */
    static boolean isHomePress(String line) {
        return line.contains("START u0") && line.contains("android.intent.category.HOME")
                && line.contains("cmp=" + Apps.AMAZON_HOME + "/") && line.contains("from uid 0");
    }

    private void homePressed(Process from) {
        if (from != logcat || !Prefs.amazonHomeAllowed(this) || CrashGuard.standingAside(this)) return;
        Log.i(TAG, "Home pressed on the Fire TV home: back to the clean home");
        Prefs.setAmazonHomeAllowed(this, false);
        watchLog(false);
        openHome();
    }

    private static boolean isOverlay(String pkg) {
        for (String o : OVERLAYS) if (pkg.equals(o) || pkg.startsWith(o + ".")) return true;
        return false;
    }

    private void openHome() {
        last = SystemClock.elapsedRealtime();
        Intent i = new Intent(this, MainActivity.class)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                .putExtra(MainActivity.EXTRA_HOME_KEY, true);
        try {
            startActivity(i);
        } catch (Exception ex) {
            Log.w(TAG, "could not open the home: " + ex.getMessage());
        }
    }

    @Override
    public void onInterrupt() {
    }
}
