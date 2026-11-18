package tv.clean.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * The wallpaper gallery: your own photo (files/wallpaper.jpg, set with set-wallpaper.sh), then your
 * film photos (files/photos/, listed in files/photos/names.txt, pushed by set-photos.sh), then
 * CleanFrame's fifteen Unsplash photos from assets/wallpapers/catalog.txt. Each photo also gets a
 * small blurred "field" once, which the glass focus plate shows through; nothing is blurred live.
 */
final class Wallpapers {
    private Wallpapers() {}

    static final String OWN = "own", PHOTO = "p:";

    static final class W {
        final String id, name;
        final float veil;      // the plate's veil strength for this photo (CleanFrame's frostVeil)
        boolean light;         // bright photo: darker dim behind the text

        W(String id, String name, boolean light, float veil) {
            this.id = id;
            this.name = name;
            this.light = light;
            this.veil = veil;
        }
    }

    static File ownFile(Context c) { return new File(c.getFilesDir(), "wallpaper.jpg"); }

    static List<W> all(Context c) {
        List<W> out = new ArrayList<>();
        if (ownFile(c).isFile()) out.add(new W(OWN, c.getString(R.string.your_photo), false, 0.16f));
        File dir = new File(c.getFilesDir(), "photos");
        try (BufferedReader br = new BufferedReader(new java.io.FileReader(new File(dir, "names.txt")))) {
            String line;
            while ((line = br.readLine()) != null) {
                String[] p = line.split("\\|", 2);
                if (p.length == 2 && new File(dir, p[0] + ".jpg").isFile()) out.add(new W(PHOTO + p[0], p[1], false, 0.16f));
            }
        } catch (Exception ignored) {
        }
        try (InputStream in = c.getAssets().open("wallpapers/catalog.txt");
             BufferedReader br = new BufferedReader(new InputStreamReader(in))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("#") || line.trim().isEmpty()) continue;
                String[] p = line.split("\\|");
                out.add(new W(p[0], p[1], "light".equals(p[2]), Float.parseFloat(p[3])));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** Full-screen bitmap, decoded at no more than the screen width. */
    static Bitmap decode(Context c, W w, int screenW) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        try {
            if (mine(w)) {
                String path = OWN.equals(w.id) ? ownFile(c).getPath()
                        : new File(new File(c.getFilesDir(), "photos"), w.id.substring(PHOTO.length()) + ".jpg").getPath();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(path, o);
                o.inSampleSize = 1;
                while (o.outWidth / (o.inSampleSize * 2) >= screenW) o.inSampleSize *= 2;
                o.inJustDecodeBounds = false;
                return BitmapFactory.decodeFile(path, o);
            }
            try (InputStream in = c.getAssets().open("wallpapers/" + w.id + ".jpg")) {
                return BitmapFactory.decodeStream(in, null, o);
            }
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The glass field: the photo centre-cropped to the screen's 16:9, halved repeatedly (each halving
     * averages 2x2 pixels) down to about 120 px wide, then box-blurred. Drawn back up with bilinear
     * filtering it reads as a heavy frosted blur. Also decides whether the photo is "light".
     */
    static Bitmap field(Bitmap full, W w, float screenAspect) {
        int fw = full.getWidth(), fh = full.getHeight();
        int cw = fw, ch = Math.round(fw / screenAspect);
        if (ch > fh) {
            ch = fh;
            cw = Math.round(fh * screenAspect);
        }
        Bitmap b = Bitmap.createBitmap(full, (fw - cw) / 2, (fh - ch) / 2, cw, ch);
        while (b.getWidth() > 240) {
            Bitmap half = Bitmap.createScaledBitmap(b, b.getWidth() / 2, Math.max(1, b.getHeight() / 2), true);
            if (b != full) b.recycle();
            b = half;
        }
        Bitmap small = b.copy(Bitmap.Config.ARGB_8888, true);
        if (b != full) b.recycle();
        boxBlur(small, 3);
        boxBlur(small, 3);
        if (mine(w)) w.light = luminance(small) > 0.55f;
        return small;
    }

    /** Your photos (not the bundled ones): brightness measured, not catalogued. */
    static boolean mine(W w) { return OWN.equals(w.id) || w.id.startsWith(PHOTO); }

    private static void boxBlur(Bitmap bm, int r) {
        int w = bm.getWidth(), h = bm.getHeight();
        int[] px = new int[w * h], tmp = new int[w * h];
        bm.getPixels(px, 0, w, 0, 0, w, h);
        pass(px, tmp, w, h, r, true);
        pass(tmp, px, w, h, r, false);
        bm.setPixels(px, 0, w, 0, 0, w, h);
    }

    private static void pass(int[] in, int[] out, int w, int h, int r, boolean horizontal) {
        int lines = horizontal ? h : w, len = horizontal ? w : h;
        for (int l = 0; l < lines; l++) {
            for (int i = 0; i < len; i++) {
                int rs = 0, gs = 0, bs = 0, n = 0;
                for (int k = Math.max(0, i - r); k <= Math.min(len - 1, i + r); k++) {
                    int p = in[horizontal ? l * w + k : k * w + l];
                    rs += (p >> 16) & 0xFF;
                    gs += (p >> 8) & 0xFF;
                    bs += p & 0xFF;
                    n++;
                }
                out[horizontal ? l * w + i : i * w + l] = 0xFF000000 | (rs / n) << 16 | (gs / n) << 8 | (bs / n);
            }
        }
    }

    private static float luminance(Bitmap bm) {
        double sum = 0;
        int w = bm.getWidth(), h = bm.getHeight();
        for (int y = 0; y < h; y += 2) {
            for (int x = 0; x < w; x += 2) {
                int p = bm.getPixel(x, y);
                sum += (0.2126 * Color.red(p) + 0.7152 * Color.green(p) + 0.0722 * Color.blue(p)) / 255.0;
            }
        }
        return (float) (sum / ((w + 1) / 2 * ((h + 1) / 2)));
    }
}
