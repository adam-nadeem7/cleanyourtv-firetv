package tv.clean.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.widget.TextClock;

import java.util.HashSet;
import java.util.Set;

/** Small persisted state shared by the activities and the Home-button service (same process). */
final class Prefs {
    private Prefs() {}

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("home", Context.MODE_PRIVATE);
    }

    static String wallpaper(Context c) { return sp(c).getString("wallpaper", Wallpapers.OWN); }
    static void setWallpaper(Context c, String id) { sp(c).edit().putString("wallpaper", id).apply(); }

    /** Timestamp of the last "Your photo" seen, so a newly pushed one is picked up once. */
    static long ownStamp(Context c) { return sp(c).getLong("own_stamp", 0); }
    static void setOwnStamp(Context c, long t) { sp(c).edit().putLong("own_stamp", t).apply(); }

    /** True after "Fire TV UI" was opened on purpose; the next Home press clears it. */
    static boolean amazonHomeAllowed(Context c) { return sp(c).getBoolean("amazon_home", false); }
    static void setAmazonHomeAllowed(Context c, boolean v) { sp(c).edit().putBoolean("amazon_home", v).apply(); }

    /** Minutes between wallpaper changes while Home is on screen; 0 = never. */
    static int shuffleMinutes(Context c) { return sp(c).getInt("shuffle_min", 3); }
    static void setShuffleMinutes(Context c, int m) { sp(c).edit().putInt("shuffle_min", m).apply(); }

    static final int CLOCK_TV = 0, CLOCK_24 = 1, CLOCK_12 = 2;
    static int clockMode(Context c) { return sp(c).getInt("clock_mode", CLOCK_TV); }
    static void setClockMode(Context c, int m) { sp(c).edit().putInt("clock_mode", m).apply(); }
    static boolean clockShown(Context c) { return sp(c).getBoolean("clock_shown", true); }
    static void setClockShown(Context c, boolean v) { sp(c).edit().putBoolean("clock_shown", v).apply(); }

    /** The clock's format: the TV's own 12/24 h setting, or forced either way. */
    static void applyClock(Context c, TextClock t) {
        int m = clockMode(c);
        String f = m == CLOCK_24 ? "H:mm" : m == CLOCK_12 ? "h:mm" : null;
        t.setFormat24Hour(f != null ? f : "H:mm");
        t.setFormat12Hour(f != null ? f : "h:mm");
    }

    /** Apps the user hid in Settings > Apps (package names). */
    static Set<String> hiddenApps(Context c) { return new HashSet<>(sp(c).getStringSet("hidden_apps", new HashSet<>())); }
    static void setHiddenApps(Context c, Set<String> s) {
        sp(c).edit().putStringSet("hidden_apps", new HashSet<>(s)).putLong("apps_stamp", System.currentTimeMillis()).apply();
    }
    /** Changes when the app list's order or hidden set changes, so Home rebuilds its list. */
    static long appsStamp(Context c) { return sp(c).getLong("apps_stamp", 0); }
    static void touchApps(Context c) { sp(c).edit().putLong("apps_stamp", System.currentTimeMillis()).apply(); }
}
