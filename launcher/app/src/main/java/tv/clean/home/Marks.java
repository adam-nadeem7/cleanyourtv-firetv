package tv.clean.home;

import java.util.HashMap;
import java.util.Map;

/** Monochrome brand marks and display names for the apps on Home. Others get a monogram. */
final class Marks {
    private Marks() {}

    private static final Map<String, Integer> MARK = new HashMap<>();
    private static final Map<String, String> NAME = new HashMap<>();

    private static void add(String pkg, int mark, String name) {
        MARK.put(pkg, mark);
        NAME.put(pkg, name);
    }

    static {
        add("com.amazon.firetv.youtube", R.drawable.mark_youtube, "YouTube");
        add("com.spotify.tv.android", R.drawable.mark_spotify, "Spotify");
        add("com.apple.atve.amazon.appletv", R.drawable.mark_appletv, "Apple TV");
        add("com.hbo.hbonow", R.drawable.mark_hbomax, "HBO Max");
        add("com.netflix.ninja", R.drawable.mark_netflix, "Netflix");
        add("com.amazon.firebat", R.drawable.mark_primevideo, "Prime Video");
        add("com.disney.disneyplus", R.drawable.mark_disneyplus, "Disney+");
        add("com.plexapp.android", R.drawable.mark_plex, "Plex");
        add("com.nordvpn.android", R.drawable.mark_nordvpn, "NordVPN");
    }

    /** Drawable id, or 0 for a monogram. */
    static int mark(String pkg) {
        Integer m = MARK.get(pkg);
        return m == null ? 0 : m;
    }

    static CharSequence name(String pkg, CharSequence label) {
        String n = NAME.get(pkg);
        return n != null ? n : label;
    }
}
