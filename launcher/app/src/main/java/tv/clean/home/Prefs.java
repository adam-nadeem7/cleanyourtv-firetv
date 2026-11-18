package tv.clean.home;

import android.content.Context;
import android.content.SharedPreferences;

/** Small persisted state shared by the activity and the Home-button service (same process). */
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
}
