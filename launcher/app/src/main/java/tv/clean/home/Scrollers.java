package tv.clean.home;

import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.widget.ScrollView;

/**
 * A scroll view that only scrolls when told to. Android's own "scroll the focused child into view"
 * is switched off; the home screen decides positions itself, so Up/Down/Left/Right are exact and a
 * fast key repeat never leaves the list half-way.
 */
public final class Scrollers {
    private Scrollers() {}

    static final int DURATION = 180;   // CleanFrame: 180 ms, cubic-bezier(.2,.7,.2,1)

    public static class Vertical extends ScrollView {
        private ObjectAnimator anim;

        public Vertical(Context c, AttributeSet a) {
            super(c, a);
            setVerticalScrollBarEnabled(false);
            setVerticalFadingEdgeEnabled(true);
            setOverScrollMode(OVER_SCROLL_NEVER);
            setSmoothScrollingEnabled(false);
        }

        @Override
        protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) { return 0; }

        void glideTo(int y, boolean animate) {
            if (anim != null) anim.cancel();
            if (!animate) {
                setScrollY(y);
                return;
            }
            anim = ObjectAnimator.ofInt(this, "scrollY", y).setDuration(DURATION);
            anim.setInterpolator(GlassPlate.EASE);
            anim.start();
        }

        int maxScroll() {
            return getChildCount() == 0 ? 0 : Math.max(0, getChildAt(0).getHeight() - getHeight());
        }
    }
}
