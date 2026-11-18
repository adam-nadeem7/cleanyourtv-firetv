package tv.clean.home;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.provider.Settings;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Inet4Address;

/**
 * The TV-level switches the settings page changes itself, through the one-time WRITE_SECURE_SETTINGS
 * (and WRITE_SETTINGS app-op) grants from build-and-install.sh, and the Fire OS settings screens
 * a normal app is allowed to open. Fire OS locks Picture, Display & sound, Date & time, Language,
 * Privacy and Accessibility behind Amazon's own permission; those stay in "All Fire TV settings".
 */
final class TvSettings {
    private TvSettings() {}

    private static final String TAG = "CleanHome";
    static final String V2 = "com.amazon.tv.settings.v2";
    static final String ADGUARD = "dns.adguard-dns.com";

    // ---- Screensaver and sleep ----

    /** Minutes without a key press before the screensaver starts (system screen_off_timeout). */
    static int screensaverMinutes(Context c) {
        return Math.round(Settings.System.getInt(c.getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, 300_000) / 60_000f);
    }

    static boolean setScreensaverMinutes(Context c, int min) {
        try {
            return Settings.System.putInt(c.getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, min * 60_000);
        } catch (Exception e) {
            Log.w(TAG, "screensaver delay: " + e.getMessage());
            return false;
        }
    }

    /** Minutes without a key press before the TV goes to standby (secure sleep_timeout); -1 = never. */
    static int sleepMinutes(Context c) {
        int ms = Settings.Secure.getInt(c.getContentResolver(), "sleep_timeout", -1);
        return ms < 0 ? -1 : Math.round(ms / 60_000f);
    }

    static boolean setSleepMinutes(Context c, int min) {
        return putSecure(c, "sleep_timeout", min < 0 ? "-1" : String.valueOf(min * 60_000));
    }

    static boolean photoScreensaver(Context c) {
        ContentResolver r = c.getContentResolver();
        String comp = Settings.Secure.getString(r, "screensaver_components");
        return Settings.Secure.getInt(r, "screensaver_enabled", 0) == 1
                && comp != null && comp.startsWith(c.getPackageName() + "/");
    }

    /** On: our photo screensaver. Off: no screensaver at all (the screen stays on until sleep). */
    static boolean setPhotoScreensaver(Context c, boolean on) {
        boolean ok = true;
        if (on) ok = putSecure(c, "screensaver_components", c.getPackageName() + "/" + PhotoDream.class.getName());
        return ok && putSecure(c, "screensaver_enabled", on ? "1" : "0");
    }

    // ---- Network ----

    static boolean adBlockDns(Context c) {
        ContentResolver r = c.getContentResolver();
        return "hostname".equals(Settings.Global.getString(r, "private_dns_mode"))
                && ADGUARD.equals(Settings.Global.getString(r, "private_dns_specifier"));
    }

    /** AdGuard DNS (ads and Amazon metrics blocked) or the network's own DNS. */
    static boolean setAdBlockDns(Context c, boolean on) {
        try {
            ContentResolver r = c.getContentResolver();
            if (on) Settings.Global.putString(r, "private_dns_specifier", ADGUARD);
            return Settings.Global.putString(r, "private_dns_mode", on ? "hostname" : "opportunistic");
        } catch (Exception e) {
            Log.w(TAG, "private dns: " + e.getMessage());
            return false;
        }
    }

    /** The TV's IPv4 address on the home network, or null when offline. */
    static String ipAddress(Context c) {
        try {
            ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
            Network n = cm.getActiveNetwork();
            LinkProperties lp = n == null ? null : cm.getLinkProperties(n);
            if (lp == null) return null;
            for (LinkAddress a : lp.getLinkAddresses()) {
                if (a.getAddress() instanceof Inet4Address) return a.getAddress().getHostAddress();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    // ---- Developer ----

    static boolean adb(Context c) {
        return Settings.Global.getInt(c.getContentResolver(), Settings.Global.ADB_ENABLED, 0) == 1;
    }

    static boolean setAdb(Context c, boolean on) {
        try {
            return Settings.Global.putInt(c.getContentResolver(), Settings.Global.ADB_ENABLED, on ? 1 : 0);
        } catch (Exception e) {
            Log.w(TAG, "adb: " + e.getMessage());
            return false;
        }
    }

    static String fireOs() {
        String v = prop("ro.build.version.name");
        if (v == null || v.isEmpty()) return android.os.Build.DISPLAY;
        return v.replaceAll("\\s*\\(.*\\)$", "").replaceFirst("^Fire OS\\s*", "");
    }

    private static String prop(String key) {
        try {
            Process p = new ProcessBuilder("getprop", key).start();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                return br.readLine();
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean putSecure(Context c, String key, String value) {
        try {
            return Settings.Secure.putString(c.getContentResolver(), key, value);
        } catch (Exception e) {
            Log.w(TAG, key + ": " + e.getMessage());
            return false;
        }
    }

    // ---- Fire OS screens that open for a normal app (tested on Fire OS 8.1.8.5) ----

    static Intent network() { return v2("android.settings.WIFI_SETTINGS", ".tv.network.NetworkActivity"); }
    static Intent bluetooth() { return v2("com.amazon.device.settings.action.CONTROLLERS", ".tv.controllers_bluetooth_devices.ControllersAndBluetoothActivity"); }
    static Intent applications() { return v2("com.amazon.device.settings.action.APPLICATIONS", ".tv.applications.ApplicationsActivity"); }
    /** Device & Software: About, updates, developer options, restart (Fire OS opens its top page for any sub-action). */
    static Intent myFireTv() { return v2("com.amazon.device.settings.action.DEVICE", ".tv.device.DeviceActivity"); }

    /** Fire OS's Settings grid; android.settings.SETTINGS only bounces through the Amazon home. */
    static Intent all() {
        return new Intent("amazon.intent.action.MAINSETTINGS")
                .addCategory("android.intent.category.MAINSETTINGS")
                .setClassName(Apps.AMAZON_HOME, Apps.AMAZON_HOME + ".ui.MainSettingsActivity");
    }

    private static Intent v2(String action, String cls) {
        return new Intent(action).setClassName(V2, V2 + cls);
    }
}
