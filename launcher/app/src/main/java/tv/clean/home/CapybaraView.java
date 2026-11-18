package tv.clean.home;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * The PS5 mascot: a capybara that sleeps (yuzu on its head, drifting z's) while the PlayStation
 * is in standby and runs while it is on. On Home it is the PS5's glyph, drawn in the list's ink. Drawn with a handful of shapes in a 160 x 90 box scaled
 * to the view; animates only while visible (no bitmaps, no video).
 */
public class CapybaraView extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
    private boolean awake;
    private float t;
    private Typeface font;

    private int FUR = 0xFFB07A45, FUR_DARK = 0xFF8A5A30, NOSE = 0xFF5E3B1E, EYE = 0xFF1E140B,
            YUZU = 0xFFFFB534, LEAF = 0xFF5FBF5A, SHADOW = 0x33000000, LINES = 0x59FFFFFF, ZEE = 0xFFFFFF;
    private boolean mono, live = true, visible;
    private long lastFrame = -1000;

    public CapybaraView(Context c) { this(c, null); }

    public CapybaraView(Context c, AttributeSet a) {
        super(c, a);
        anim.setRepeatCount(ValueAnimator.INFINITE);
        anim.setInterpolator(new LinearInterpolator());
        anim.addUpdateListener(v -> {
            // 20 frames a second is plenty for a 40 dp glyph and a third of the work on this TV.
            long now = v.getCurrentPlayTime();
            if (now - lastFrame < 50) return;
            lastFrame = now;
            t = (float) v.getAnimatedValue();
            invalidate();
        });
        setAwake(false);
        try {
            font = Fonts.get(c, 600);
        } catch (Exception ignored) {
        }
    }

    /** Glyph mode for the list: no card, everything in the list's warm ink. */
    void setMono(int ink) {
        mono = true;
        int rgb = ink & 0x00FFFFFF;
        FUR = ink;
        FUR_DARK = 0xB3000000 | rgb;
        NOSE = 0xFF3A332B;
        EYE = 0xFF241E18;
        YUZU = 0xE6000000 | rgb;
        LEAF = 0x99000000 | rgb;
        SHADOW = 0x00000000;
        LINES = 0x73000000 | rgb;
        ZEE = rgb;
        invalidate();
    }

    void setAwake(boolean on) {
        if (awake != on && mono) setLive(true);   // show the change
        awake = on;
        anim.setDuration(on ? 520 : 2600);
        invalidate();
    }

    private static final long BURST_MS = 8000;
    private final Runnable rest = () -> {
        live = false;
        update();
    };

    /**
     * Animate for a few seconds when wanted (the PS5 row just got selected, or the PS5 changed state),
     * then hold a still frame. Each frame also redraws the list's fading edges, so idling still keeps
     * the TV's CPU free.
     */
    void setLive(boolean on) {
        removeCallbacks(rest);
        live = on;
        if (on) postDelayed(rest, BURST_MS);
        update();
    }

    @Override
    public void onVisibilityAggregated(boolean v) {
        super.onVisibilityAggregated(v);
        visible = v;
        update();
    }

    private void update() {
        if (live && visible) {
            if (!anim.isStarted()) anim.start();
        } else if (anim.isStarted()) {
            anim.cancel();
            t = 0.3f;   // a still frame with the z's and eye where they read well
            invalidate();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        if (!mono) bg.setShader(new LinearGradient(0, 0, w, h, 0xFF0B3D91, 0xFF0070D1, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float rad = 14 * getResources().getDisplayMetrics().density;
        r.set(0, 0, w, h);
        if (!mono) c.drawRoundRect(r, rad, rad, bg);
        c.save();
        if (mono) {
            // Glyph: just the head in a 48 x 32 box, so it reads at the size of a brand mark.
            float s = Math.min(w / 48f, h / 32f);
            c.translate((w - 48 * s) / 2f, (h - 32 * s) / 2f);
            c.scale(s, s);
            drawHead(c);
            c.restore();
            return;
        }
        float s = Math.min(w / 160f, h / 90f);
        c.translate((w - 160 * s) / 2f, (h - 90 * s) / 2f);
        c.scale(s, s);
        if (awake) drawRunning(c);
        else drawSleeping(c);
        c.restore();
    }

    /** The glyph: a capybara head in profile. Asleep: eye shut, yuzu on top, z's. On: eye open, bobbing, speed lines. */
    private void drawHead(Canvas c) {
        double a = t * 2 * Math.PI;
        float bob = awake ? (float) Math.abs(Math.sin(a)) * -1.6f : (float) Math.sin(a) * 0.35f;
        if (awake) {
            p.setColor(LINES);
            for (int i = 0; i < 3; i++) {
                float x = 7 - ((t * 14 + i * 5) % 14);
                round(c, x, 13 + i * 5, x + 6, 14.4f + i * 5, 0.7f);
            }
        }
        c.save();
        c.translate(awake ? 4 : 0, bob);
        p.setColor(FUR_DARK);
        c.drawCircle(13, 10, 3.6f, p);                       // ear
        p.setColor(FUR);
        round(c, 5, 9, 39, 30, 10);                          // the blocky head
        p.setColor(NOSE);
        round(c, 33.5f, 14.5f, 39.5f, 21, 2.4f);             // snout
        p.setColor(EYE);
        if (awake) {
            c.drawCircle(24.5f, 16.5f, 2.1f, p);
        } else {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.5f);
            p.setStrokeCap(Paint.Cap.ROUND);
            c.drawArc(new RectF(21, 14.5f, 28, 19), 15, 150, false, p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(YUZU);
            c.drawCircle(21, 5.6f, 4.2f, p);                 // yuzu
            p.setColor(LEAF);
            oval(c, 21, 0.2f, 26.5f, 3.2f);
        }
        c.restore();
        if (!awake) {
            if (font != null) p.setTypeface(font);
            for (int i = 0; i < 2; i++) {
                float ph = (t + i / 2f) % 1f;
                p.setColor(((int) (235 * Math.sin(ph * Math.PI)) << 24) | ZEE);
                p.setTextSize(6 + 3 * ph);
                c.drawText("z", 40 + 4 * ph, 12 - 10 * ph, p);
            }
        }
    }

    private void drawSleeping(Canvas c) {
        float breathe = (float) Math.sin(t * 2 * Math.PI);
        // ground shadow
        p.setColor(SHADOW);
        oval(c, 34, 70, 132, 78);
        // body, lying down, breathing
        c.save();
        c.scale(1f, 1f + 0.035f * breathe, 80, 72);
        p.setColor(FUR);
        round(c, 36, 44, 120, 74, 16);
        p.setColor(FUR_DARK);
        round(c, 44, 64, 60, 74, 5);                 // tucked back paw
        c.restore();
        // head resting on the ground
        p.setColor(FUR);
        round(c, 98, 46, 136, 74, 12);
        p.setColor(FUR_DARK);
        oval(c, 102, 42, 110, 50);                   // ear
        p.setColor(NOSE);
        round(c, 128, 54, 138, 64, 4);               // snout
        // closed eye
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.6f);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setColor(EYE);
        c.drawArc(new RectF(112, 52, 120, 57), 10, 160, false, p);
        p.setStyle(Paint.Style.FILL);
        // yuzu on the head
        p.setColor(YUZU);
        c.drawCircle(114, 41 + 0.6f * breathe, 6, p);
        p.setColor(LEAF);
        oval(c, 113, 32 + 0.6f * breathe, 120, 36 + 0.6f * breathe);
        // z z Z drifting up
        if (font != null) p.setTypeface(font);
        for (int i = 0; i < 3; i++) {
            float ph = (t + i / 3f) % 1f;
            p.setColor(((int) (255 * Math.sin(ph * Math.PI)) << 24) | ZEE);
            p.setTextSize(7 + 5 * ph);
            c.drawText(i == 2 ? "Z" : "z", 134 + 10 * ph, 40 - 26 * ph, p);
        }
    }

    private void drawRunning(Canvas c) {
        double a = t * 2 * Math.PI;
        float bob = (float) Math.abs(Math.sin(a)) * -3f;
        // speed lines and ground dashes moving left
        p.setColor(LINES);
        for (int i = 0; i < 3; i++) {
            float x = 30 - ((t * 60 + i * 22) % 60);
            round(c, x, 40 + i * 9, x + 18, 42 + i * 9, 1);
        }
        p.setColor(mono ? 0 : 0x40FFFFFF);
        for (int i = 0; i < 6; i++) {
            float x = 160 - ((t * 160 + i * 32) % 192);
            round(c, x, 79, x + 12, 80.5f, 1);
        }
        p.setColor(SHADOW);
        oval(c, 44, 74, 128, 80);
        // legs: front and back pairs swing in opposite phase
        float swing = (float) Math.sin(a) * 28f;
        leg(c, 56, 62 + bob, swing);
        leg(c, 66, 62 + bob, -swing);
        leg(c, 98, 62 + bob, -swing);
        leg(c, 108, 62 + bob, swing);
        // body and head
        p.setColor(FUR);
        round(c, 46, 36 + bob, 118, 66 + bob, 15);
        round(c, 104, 28 + bob, 140, 56 + bob, 11);
        p.setColor(FUR_DARK);
        oval(c, 108, 24 + bob, 116, 32 + bob);       // ear
        p.setColor(NOSE);
        round(c, 132, 36 + bob, 142, 46 + bob, 4);   // snout
        p.setColor(EYE);
        c.drawCircle(124, 37 + bob, mono ? 2.6f : 2.1f, p);   // open eye
        if (!mono) {
            p.setColor(0xFFFFFFFF);
            c.drawCircle(124.7f, 36.3f + bob, 0.7f, p);
        }
    }

    private void leg(Canvas c, float x, float top, float deg) {
        c.save();
        c.rotate(deg, x + 3.5f, top);
        p.setColor(FUR_DARK);
        round(c, x, top, x + 7, top + 15, 3);
        c.restore();
    }

    private void round(Canvas c, float l, float t, float rr, float b, float rad) {
        r.set(l, t, rr, b);
        c.drawRoundRect(r, rad, rad, p);
    }

    private void oval(Canvas c, float l, float t, float rr, float b) {
        r.set(l, t, rr, b);
        c.drawOval(r, p);
    }
}
