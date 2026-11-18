package tv.clean.home;

import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextClock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The screensaver, in place of Amazon's (which carries sponsored content): the wallpaper gallery
 * (your photo, your film photos, the bundled ones) in a random order, one photo every 90 s with a
 * slow cross-fade, and the time small in the corner. Two image views, no motion, nothing downloaded.
 *
 * Set over adb (build-and-install.sh does this):
 *   settings put secure screensaver_components tv.clean.home/tv.clean.home.PhotoDream
 */
public class PhotoDream extends DreamService {
    private static final long EACH_MS = 90_000, FADE_MS = 1600;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final List<Wallpapers.W> order = new ArrayList<>();
    private ImageView front, back;
    private Bitmap shown;
    private int next;

    private final Runnable step = new Runnable() {
        @Override public void run() {
            showNext();
            main.postDelayed(this, EACH_MS);
        }
    };

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        float dp = getResources().getDisplayMetrics().density;

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(getColor(R.color.window));
        back = image();
        front = image();
        root.addView(back, new FrameLayout.LayoutParams(-1, -1));
        root.addView(front, new FrameLayout.LayoutParams(-1, -1));

        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x000A0806, 0x000A0806, 0x570A0806}));
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

        TextClock clock = new TextClock(this);
        Prefs.applyClock(this, clock);
        clock.setTextSize(30);
        clock.setTypeface(Fonts.get(this, 500));
        clock.setLetterSpacing(-0.03f);
        clock.setTextColor(getColor(R.color.ink));
        clock.setShadowLayer(6 * dp, 0, 1.5f * dp, 0x520E0A06);
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.BOTTOM);
        cp.setMargins(0, 0, Math.round(61 * dp), Math.round(40 * dp));
        root.addView(clock, cp);
        setContentView(root);
    }

    private ImageView image() {
        ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return v;
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        order.clear();
        order.addAll(Wallpapers.all(this));
        Collections.shuffle(order);
        next = 0;
        main.post(step);
    }

    @Override
    public void onDreamingStopped() {
        super.onDreamingStopped();
        main.removeCallbacks(step);
    }

    @Override
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        main.removeCallbacks(step);
        io.shutdownNow();
    }

    private void showNext() {
        if (order.isEmpty()) return;
        Wallpapers.W w = order.get(next);
        next = (next + 1) % order.size();
        DisplayMetrics dm = getResources().getDisplayMetrics();
        io.execute(() -> {
            Bitmap bm = Wallpapers.decode(this, w, dm.widthPixels);
            if (bm == null) return;
            main.post(() -> {
                // The new photo fades in on top; the old one is then moved underneath and released.
                back.setImageBitmap(shown);
                front.setAlpha(0f);
                front.setImageBitmap(bm);
                front.animate().alpha(1f).setDuration(FADE_MS).withEndAction(() -> {
                    back.setImageDrawable(null);
                    shown = bm;
                }).start();
            });
        });
    }
}
