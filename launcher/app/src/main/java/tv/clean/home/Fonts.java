package tv.clean.home;

import android.content.Context;
import android.graphics.Typeface;
import android.util.SparseArray;

/** Manrope (OFL, variable) at fixed weights, built once. */
final class Fonts {
    private Fonts() {}

    private static final SparseArray<Typeface> cache = new SparseArray<>();

    static Typeface get(Context c, int weight) {
        Typeface t = cache.get(weight);
        if (t == null) {
            t = new Typeface.Builder(c.getAssets(), "fonts/manrope.ttf")
                    .setFontVariationSettings("'wght' " + weight).build();
            if (t == null) t = Typeface.DEFAULT;
            cache.put(weight, t);
        }
        return t;
    }
}
