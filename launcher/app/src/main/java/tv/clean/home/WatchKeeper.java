package tv.clean.home;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps the Home watch (HomeKeyService) switched on and bound by itself. Android drops the service
 * when the app is updated or force-stopped, and Fire OS doesn't always rebind it; with the one-time
 * WRITE_SECURE_SETTINGS grant (build-and-install.sh) the app puts it back at boot, after an update,
 * and whenever the home starts while it is listed but not bound. (After couch-tv's FireWatch.)
 */
public class WatchKeeper extends BroadcastReceiver {
    private static final String TAG = "CleanHome";
    private static final String KEY = "enabled_accessibility_services";

    @Override
    public void onReceive(Context c, Intent i) {
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(i.getAction())) CrashGuard.reset(c);
        ensure(c);
        // Give Android a few seconds to bind it by itself, then rebind if it didn't.
        PendingResult r = goAsync();
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            rebindIfUnbound(c);
            r.finish();
        }, 6000);
    }

    private static boolean granted(Context c) {
        return c.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED;
    }

    private static String me(Context c) { return c.getPackageName() + "/" + HomeKeyService.class.getName(); }

    private static List<String> others(Context c) {
        String s = Settings.Secure.getString(c.getContentResolver(), KEY);
        List<String> out = new ArrayList<>();
        if (s == null) return out;
        for (String x : s.split(":")) {
            if (x.isEmpty() || "null".equals(x) || x.startsWith(c.getPackageName() + "/")) continue;
            out.add(x);
        }
        return out;
    }

    /** Listed and on, keeping any other accessibility services. */
    static void ensure(Context c) {
        if (!granted(c)) return;
        try {
            String s = Settings.Secure.getString(c.getContentResolver(), KEY);
            if (s == null || !s.contains(c.getPackageName() + "/")) {
                List<String> list = others(c);
                list.add(me(c));
                Settings.Secure.putString(c.getContentResolver(), KEY, String.join(":", list));
                Log.i(TAG, "switched the Home watch back on");
            }
            Settings.Secure.putInt(c.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
        } catch (Exception e) {
            Log.w(TAG, "could not switch the Home watch on: " + e.getMessage());
        }
    }

    /** Listed but not bound (what an update or force-stop leaves): take it out and put it back. */
    static void rebindIfUnbound(Context c) {
        if (HomeKeyService.connected || !granted(c)) return;
        new Thread(() -> {
            try {
                if (HomeKeyService.connected) return;
                List<String> list = others(c);
                Log.i(TAG, "Home watch listed but not bound: rebinding");
                Settings.Secure.putString(c.getContentResolver(), KEY, String.join(":", list));
                Thread.sleep(1500);
                list.add(me(c));
                Settings.Secure.putString(c.getContentResolver(), KEY, String.join(":", list));
                Settings.Secure.putInt(c.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            } catch (Exception e) {
                Log.w(TAG, "could not rebind the Home watch: " + e.getMessage());
            }
        }).start();
    }
}
