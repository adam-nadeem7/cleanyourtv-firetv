package tv.clean.home;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.PathInterpolator;

/**
 * CleanFrame's glass: a rounded window onto the pre-blurred wallpaper field, aligned with the photo
 * behind it, under a warm veil, with a one-pixel highlight along the top edge. No live blur.
 * Used for the list's focus plate (rect animates) and for sheet backgrounds (rect = bounds).
 */
public class GlassPlate extends View {
    static final PathInterpolator EASE = new PathInterpolator(0.2f, 0.7f, 0.2f, 1f);

    private Bitmap field;
    private final Paint fieldPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint veil = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF(), dst = new RectF();
    private final Path clip = new Path();
    private final int[] loc = new int[2];
    private float radius, level = 1f;
    private boolean fullBounds = true;
    private ValueAnimator widthAnim, levelAnim;
    private float veilAlpha = 0.12f;

    public GlassPlate(Context c) { this(c, null); }

    public GlassPlate(Context c, AttributeSet a) {
        super(c, a);
        float dp = getResources().getDisplayMetrics().density;
        radius = 17 * dp;
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(1f * dp);
        setWillNotDraw(false);
    }

    void setRadius(float r) { radius = r; invalidate(); }

    /** Never asks for space: as a sheet background it takes the size its parent settles on. */
    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(
                MeasureSpec.getMode(w) == MeasureSpec.EXACTLY ? MeasureSpec.getSize(w) : 0,
                MeasureSpec.getMode(h) == MeasureSpec.EXACTLY ? MeasureSpec.getSize(h) : 0);
    }

    /** Field bitmap and veil (0..1, darkening) for the current photo. */
    void setField(Bitmap b, float veil) {
        field = b;
        veilAlpha = veil;
        invalidate();
    }

    /** Draw at a fixed rect (list plate). Width changes animate; position jumps. */
    void setPlate(float left, float top, float width, float height, boolean animate) {
        fullBounds = false;
        float from = rect.width();
        rect.set(left, top, left + (animate && from > 0 ? from : width), top + height);
        if (widthAnim != null) widthAnim.cancel();
        if (!animate || from <= 0) {
            invalidate();
            return;
        }
        widthAnim = ValueAnimator.ofFloat(from, width).setDuration(180);
        widthAnim.setInterpolator(EASE);
        widthAnim.addUpdateListener(a -> {
            rect.right = rect.left + (float) a.getAnimatedValue();
            invalidate();
        });
        widthAnim.start();
    }

    /** 1 = focused, 0.5 = selected (focus is elsewhere), 0 = hidden. */
    void setLevel(float to) {
        if (levelAnim != null) levelAnim.cancel();
        levelAnim = ValueAnimator.ofFloat(level, to).setDuration(160);
        levelAnim.addUpdateListener(a -> {
            level = (float) a.getAnimatedValue();
            invalidate();
        });
        levelAnim.start();
    }

    @Override
    protected void onDraw(Canvas c) {
        if (level <= 0.01f) return;
        RectF r = rect;
        if (fullBounds) rect.set(0, 0, getWidth(), getHeight());
        int save = c.saveLayerAlpha(r.left - 2, r.top - 2, r.right + 2, r.bottom + 2, Math.round(255 * level));
        clip.reset();
        clip.addRoundRect(r, radius, radius, Path.Direction.CW);
        c.clipPath(clip);
        if (field != null) {
            // Map the field onto the whole screen, so the plate shows the blur of what is behind it.
            getLocationOnScreen(loc);
            int sw = getRootView().getWidth(), sh = getRootView().getHeight();
            dst.set(-loc[0], -loc[1], sw - loc[0], sh - loc[1]);
            c.drawBitmap(field, null, dst, fieldPaint);
        } else {
            c.drawColor(0x33FFFFFF);
        }
        veil.setColor(((int) (255 * veilAlpha) << 24) | 0x0C0A08);
        c.drawRect(r, veil);
        c.restoreToCount(save);
        edge.setShader(new LinearGradient(0, r.top, 0, r.top + radius,
                ((int) (0x3D * level) << 24) | 0xFFFAF0, 0x00FFFAF0, Shader.TileMode.CLAMP));
        c.drawRoundRect(r.left + 0.5f, r.top + 0.5f, r.right - 0.5f, r.bottom - 0.5f, radius, radius, edge);
    }
}
