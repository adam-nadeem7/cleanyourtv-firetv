package tv.clean.home;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

/**
 * Fire OS protects com.amazon.tv.launcher, so it stays the system Home. This service puts the clean
 * home in front whenever the Fire TV home comes up: after the Home button, at boot, when an app
 * exits to it. Only a visit the user started from "Fire TV UI" is let through, and that ends as soon
 * as any other app comes to the front.
 *
 * What Fire OS 8 does not allow (tested on the Xiaomi L65MB-FAEU): KEYCODE_HOME and the other remote
 * keys never reach accessibility key filters, and the "homekey" CLOSE_SYSTEM_DIALOGS broadcast needs
 * an Amazon signature. So the window event is the signal, and a Home press while the Fire TV home is
 * already in front can't be seen (the "Home" app in its Your Apps row leads back).
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
            if (!cls.toString().contains("HomeActivity") || Prefs.amazonHomeAllowed(this)) return;
            if (CrashGuard.standingAside(this)) return;
            if (SystemClock.elapsedRealtime() - last < DEBOUNCE_MS) return;
            Log.i(TAG, "covering Fire TV home");
            openHome();
            return;
        }
        if (Prefs.amazonHomeAllowed(this) && !isOverlay(pkg)) {
            Log.i(TAG, "left the Fire TV home for " + pkg);
            Prefs.setAmazonHomeAllowed(this, false);
        }
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
