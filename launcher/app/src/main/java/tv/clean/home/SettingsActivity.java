package tv.clean.home;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.LruCache;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The settings page, after couch-tv's Home settings: sections on the left, their rows on the right,
 * over a frosted copy of the wallpaper. Home's own settings (wallpaper, apps, clock) plus the TV
 * switches a normal app may change (screensaver, sleep, ad-blocking DNS, ADB) and shortcuts to the
 * Fire OS screens that open for it.
 *
 * Like Home, nothing takes Android focus: this activity keeps the state and handles every key.
 * Up/Down move, Right or OK opens a section, OK changes a row, holding OK on an app picks it up
 * (Up/Down move it, OK or Back puts it down), Left or Back steps back and then closes.
 */
public class SettingsActivity extends Activity {
    private static final int S_WALL = 0, S_APPS = 1, S_CLOCK = 2, S_SLEEP = 3, S_NET = 4, S_TV = 5, S_ABOUT = 6;
    private static final int[] SECTION_NAMES = {R.string.s_wallpaper, R.string.s_apps, R.string.s_clock,
            R.string.s_sleep, R.string.s_network, R.string.s_tv, R.string.s_about};
    private static final int ZONE_LEFT = 0, ZONE_RIGHT = 1;
    private static final int MARK_NONE = 0, MARK_CHECK = 1, MARK_LINK = 2;

    private static final int[] SHUFFLE = {0, 3, 10, 30};
    private static final int[] SCREENSAVER = {5, 10, 15, 30};
    private static final int[] SLEEP = {15, 30, 60, 120, 240, -1};

    /** One line on the right: a caption, or a row with a label, a value and maybe a picture. */
    private static final class Item {
        boolean caption;
        String label, value, pkg;
        int mark;
        Wallpapers.W wall;
        int markRes;
        Runnable act;
        View view;
        TextView labelView, valueView;
    }

    private final List<TextView> sectionViews = new ArrayList<>();
    private final List<Item> items = new ArrayList<>();
    private int section, zone = ZONE_LEFT, sel;
    private boolean okDown, okLong, moving;

    private GlassPlate glass;
    private TextView header, hint;
    private Scrollers.Vertical scroll;
    private LinearLayout rowsBox;

    private float dp;
    private int ink, solid;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private static final LruCache<String, Bitmap> thumbs = new LruCache<>(48);

    private List<Wallpapers.W> walls = new ArrayList<>();
    private List<Apps.Entry> apps = new ArrayList<>();
    private String note;   // a one-off message in the hint line (e.g. Fire OS refused a change)

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        CrashGuard.install(this);
        dp = getResources().getDisplayMetrics().density;
        ink = getColor(R.color.ink);
        solid = getColor(R.color.solid_ink);
        setContentView(build());
        walls = Wallpapers.all(this);
        showBackground();
        io.execute(() -> {
            List<Apps.Entry> a = Apps.loadAll(this);
            runOnUiThread(() -> {
                apps = a;
                if (section == S_APPS) refresh();
            });
        });
        selectSection(0);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Back from a Fire OS screen: values may have changed there.
        if (!items.isEmpty()) refresh();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdown();
    }

    // ---- Layout ---------------------------------------------------------------------------------

    private View build() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0E1012);
        root.setFocusable(true);
        root.setFocusableInTouchMode(true);

        glass = new GlassPlate(this);
        glass.setRadius(0);
        root.addView(glass, new FrameLayout.LayoutParams(-1, -1));
        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0x8C0A0806, 0x660A0806, 0x4D0A0806}));
        root.addView(shade, new FrameLayout.LayoutParams(-1, -1));

        // Left: the title and the sections.
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        TextView title = text(getString(R.string.settings), 30, 600);
        title.setLetterSpacing(-0.02f);
        title.setPadding(px(16), 0, 0, 0);
        left.addView(title, new LinearLayout.LayoutParams(-2, px(44)));
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-1, px(18));
        left.addView(new View(this), gap);
        for (int name : SECTION_NAMES) {
            TextView t = text(getString(name), 18, 500);
            t.setGravity(Gravity.CENTER_VERTICAL);
            t.setPadding(px(16), 0, px(16), 0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, px(42));
            lp.bottomMargin = px(4);
            left.addView(t, lp);
            sectionViews.add(t);
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(px(236), -2);
        lp.leftMargin = px(40);
        lp.topMargin = px(40);
        root.addView(left, lp);

        // Right: the section's name and its rows.
        header = text("", 22, 600);
        header.setPadding(px(16), 0, 0, 0);
        header.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(px(560), px(44));
        hp.leftMargin = px(330);
        hp.topMargin = px(40);
        root.addView(header, hp);

        scroll = new Scrollers.Vertical(this, null);
        scroll.setFadingEdgeLength(px(28));
        rowsBox = new LinearLayout(this);
        rowsBox.setOrientation(LinearLayout.VERTICAL);
        rowsBox.setPadding(0, px(8), 0, px(40));
        scroll.addView(rowsBox, new FrameLayout.LayoutParams(-1, -2));
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(px(560), px(392));
        sp.leftMargin = px(330);
        sp.topMargin = px(92);
        root.addView(scroll, sp);

        hint = text("", 12, 500);
        hint.setAlpha(0.6f);
        hint.setPadding(px(16), 0, 0, 0);
        FrameLayout.LayoutParams np = new FrameLayout.LayoutParams(px(560), -2, Gravity.BOTTOM);
        np.leftMargin = px(330);
        np.bottomMargin = px(24);
        root.addView(hint, np);
        return root;
    }

    private void showBackground() {
        if (walls.isEmpty()) return;
        Wallpapers.W w = walls.get(0);
        String want = Prefs.wallpaper(this);
        for (Wallpapers.W x : walls) if (x.id.equals(want)) w = x;
        final Wallpapers.W cur = w;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        io.execute(() -> {
            Bitmap full = Wallpapers.decode(this, cur, dm.widthPixels / 2);
            if (full == null) return;
            Bitmap field = Wallpapers.field(full, cur, dm.widthPixels / (float) dm.heightPixels);
            full.recycle();
            runOnUiThread(() -> glass.setField(field, 0.38f));
        });
    }

    private TextView text(String s, int sp, int weight) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTypeface(Fonts.get(this, weight));
        t.setTextColor(ink);
        t.setIncludeFontPadding(false);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    private int px(float v) { return Math.round(v * dp); }

    private GradientDrawable pill(int color, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(radius * dp);
        g.setColor(color);
        return g;
    }

    // ---- Sections -------------------------------------------------------------------------------

    private void selectSection(int s) {
        section = Math.max(0, Math.min(s, SECTION_NAMES.length - 1));
        header.setText(SECTION_NAMES[section]);
        sel = -1;
        refresh();
        scroll.glideTo(0, false);
        paintSections();
    }

    private void paintSections() {
        for (int i = 0; i < sectionViews.size(); i++) {
            TextView t = sectionViews.get(i);
            boolean on = i == section;
            if (on && zone == ZONE_LEFT) {
                t.setBackground(pill(ink, 14));
                t.setTextColor(solid);
                t.setAlpha(1f);
            } else {
                t.setBackground(on ? pill(0x2EFFFFFF, 14) : null);
                t.setTextColor(ink);
                t.setAlpha(on ? 1f : 0.72f);
            }
        }
    }

    /** Rebuild the right side for the current section, keeping the selected row. */
    private void refresh() {
        int keep = sel;
        items.clear();
        rowsBox.removeAllViews();
        switch (section) {
            case S_WALL: wallpaper(); break;
            case S_APPS: apps(); break;
            case S_CLOCK: clock(); break;
            case S_SLEEP: sleep(); break;
            case S_NET: network(); break;
            case S_TV: tvSettings(); break;
            default: about(); break;
        }
        for (Item it : items) rowsBox.addView(it.view);
        sel = keep >= 0 && keep < items.size() && selectable(items.get(keep)) ? keep : firstRow();
        paintRows();
        String h = note != null ? note : getString(section == S_APPS ? R.string.hint_apps : R.string.hint);
        if (moving) h = getString(R.string.hint_moving);
        hint.setText(h);
        note = null;
    }

    private int firstRow() {
        for (int i = 0; i < items.size(); i++) if (selectable(items.get(i))) return i;
        return -1;
    }

    private int lastRow() {
        for (int i = items.size() - 1; i >= 0; i--) if (selectable(items.get(i))) return i;
        return -1;
    }

    /** Captions and plain information (version, IP) are skipped by Up/Down. */
    private static boolean selectable(Item it) { return !it.caption && it.act != null; }

    private void wallpaper() {
        int cur = Prefs.shuffleMinutes(this);
        row(getString(R.string.shuffle), cur == 0 ? getString(R.string.off) : getString(R.string.every_min, cur), () ->
                Prefs.setShuffleMinutes(this, next(SHUFFLE, cur)));
        caption(getString(R.string.photos));
        String want = Prefs.wallpaper(this);
        for (Wallpapers.W w : walls) {
            Item it = row(w.name, null, () -> {
                Prefs.setWallpaper(this, w.id);
                showBackground();
            });
            it.wall = w;
            it.mark = w.id.equals(want) ? MARK_CHECK : MARK_NONE;
            dress(it);
        }
    }

    private void apps() {
        Set<String> hidden = Prefs.hiddenApps(this);
        if (apps.isEmpty()) {
            caption(getString(R.string.loading));
            return;
        }
        for (Apps.Entry e : apps) {
            boolean off = hidden.contains(e.pkg);
            Item it = row(Marks.name(e.pkg, e.label).toString(), getString(off ? R.string.hidden : R.string.shown), () -> {
                Set<String> h = Prefs.hiddenApps(this);
                if (!h.remove(e.pkg)) h.add(e.pkg);
                Prefs.setHiddenApps(this, h);
            });
            it.pkg = e.pkg;
            it.markRes = Marks.mark(e.pkg);
            dress(it);
            if (off) it.labelView.setAlpha(0.55f);
        }
    }

    private void clock() {
        int m = Prefs.clockMode(this);
        boolean tv24 = android.text.format.DateFormat.is24HourFormat(this);
        String like = getString(R.string.like_tv, getString(tv24 ? R.string.h24 : R.string.h12));
        row(getString(R.string.clock_format), m == Prefs.CLOCK_24 ? getString(R.string.h24_long)
                : m == Prefs.CLOCK_12 ? getString(R.string.h12_long) : like, () ->
                Prefs.setClockMode(this, (m + 1) % 3));
        boolean shown = Prefs.clockShown(this);
        row(getString(R.string.show_clock), onOff(shown), () -> Prefs.setClockShown(this, !shown));
    }

    private void sleep() {
        boolean photo = TvSettings.photoScreensaver(this);
        row(getString(R.string.photo_screensaver), onOff(photo), () -> check(TvSettings.setPhotoScreensaver(this, !photo)));
        int ss = TvSettings.screensaverMinutes(this);
        row(getString(R.string.screensaver_after), getString(R.string.min, ss), () ->
                check(TvSettings.setScreensaverMinutes(this, next(SCREENSAVER, ss))));
        int sl = TvSettings.sleepMinutes(this);
        row(getString(R.string.sleep_after), minutes(sl), () -> check(TvSettings.setSleepMinutes(this, next(SLEEP, sl))));
        note(getString(R.string.sleep_note));
    }

    private void network() {
        String ip = TvSettings.ipAddress(this);
        link(getString(R.string.wifi), ip != null ? getString(R.string.connected) : getString(R.string.offline), TvSettings.network());
        boolean dns = TvSettings.adBlockDns(this);
        row(getString(R.string.adblock_dns), dns ? getString(R.string.on_adguard) : getString(R.string.off), () ->
                check(TvSettings.setAdBlockDns(this, !dns)));
        note(getString(R.string.dns_note));
    }

    private void tvSettings() {
        link(getString(R.string.tv_network), null, TvSettings.network());
        link(getString(R.string.tv_bluetooth), null, TvSettings.bluetooth());
        link(getString(R.string.tv_apps), null, TvSettings.applications());
        link(getString(R.string.tv_device), getString(R.string.tv_device_value), TvSettings.myFireTv());
        link(getString(R.string.tv_all), null, TvSettings.all());
        note(getString(R.string.tv_note));
    }

    private void about() {
        String version = "";
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            version = pi.versionName;
        } catch (Exception ignored) {
        }
        info(getString(R.string.app_name), version);
        info(getString(R.string.fire_os), TvSettings.fireOs());
        info(getString(R.string.tv_model), Build.MODEL);
        String ip = TvSettings.ipAddress(this);
        info(getString(R.string.ip_address), ip != null ? ip : getString(R.string.offline));
        info(getString(R.string.home_watch), getString(HomeKeyService.connected ? R.string.connected : R.string.not_connected));
        caption(getString(R.string.developer));
        boolean adb = TvSettings.adb(this);
        row(getString(R.string.adb), onOff(adb), () -> check(TvSettings.setAdb(this, !adb)));
        row(getString(R.string.restart_home), null, this::restartHome).mark = MARK_LINK;
        dress(items.get(items.size() - 1));
    }

    // ---- Rows -----------------------------------------------------------------------------------

    private void caption(String s) {
        Item it = new Item();
        it.caption = true;
        TextView t = text(s.toUpperCase(), 11, 700);
        t.setLetterSpacing(0.08f);
        t.setAlpha(0.6f);
        t.setPadding(px(16), px(14), px(16), px(8));
        it.view = t;
        items.add(it);
    }

    /** A quiet sentence under the rows. */
    private void note(String s) {
        Item it = new Item();
        it.caption = true;
        TextView t = text(s, 13, 500);
        t.setAlpha(0.62f);
        t.setSingleLine(false);
        t.setMaxLines(4);
        t.setPadding(px(16), px(10), px(24), px(8));
        t.setLineSpacing(0, 1.3f);
        it.view = t;
        items.add(it);
    }

    private Item row(String label, String value, Runnable act) {
        Item it = new Item();
        it.label = label;
        it.value = value;
        it.act = act;
        items.add(it);
        dress(it);
        return it;
    }

    private void info(String label, String value) {
        row(label, value, null);
    }

    private void link(String label, String value, Intent target) {
        Item it = row(label, value, () -> open(target));
        it.mark = MARK_LINK;
        dress(it);
    }

    /** (Re)build an item's view from its fields. */
    private void dress(Item it) {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.HORIZONTAL);
        v.setGravity(Gravity.CENTER_VERTICAL);
        v.setPadding(px(16), 0, px(16), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, px(it.wall != null ? 50 : 44));
        lp.bottomMargin = px(6);
        v.setLayoutParams(lp);

        if (it.wall != null) {
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View view, Outline o) {
                    o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), 6 * dp);
                }
            });
            iv.setClipToOutline(true);
            iv.setBackgroundColor(0x33FFFFFF);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(px(60), px(34));
            ip.rightMargin = px(14);
            v.addView(iv, ip);
            loadThumb(it.wall, iv);
        } else if (it.pkg != null) {
            ImageView iv = new ImageView(this);
            if (it.markRes != 0) {
                iv.setImageResource(it.markRes);
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            }
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(px(26), px(20));
            ip.rightMargin = px(14);
            v.addView(iv, ip);
        }

        TextView label = text(it.label, 17, 500);
        v.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));
        it.labelView = label;

        TextView value = text(it.value != null ? it.value : "", 15, 500);
        value.setAlpha(0.72f);
        if (it.value == null) value.setVisibility(View.GONE);
        LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(-2, -2);
        vp.leftMargin = px(12);
        v.addView(value, vp);
        it.valueView = value;

        if (it.mark != MARK_NONE) {
            TextView m = text(it.mark == MARK_CHECK ? "✓" : "›", it.mark == MARK_CHECK ? 17 : 22, 600);
            LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-2, -2);
            mp.leftMargin = px(12);
            v.addView(m, mp);
        }
        it.view = v;
    }

    private void loadThumb(Wallpapers.W w, ImageView iv) {
        Bitmap b = thumbs.get(w.id);
        if (b != null) {
            iv.setImageBitmap(b);
            return;
        }
        iv.setTag(w.id);
        io.execute(() -> {
            Bitmap t = Wallpapers.thumb(this, w, px(60) * 2);
            if (t == null) return;
            thumbs.put(w.id, t);
            runOnUiThread(() -> {
                if (w.id.equals(iv.getTag())) iv.setImageBitmap(t);
            });
        });
    }

    /** Paint the rows: the selected one solid while the right side has the keys. */
    private void paintRows() {
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            if (it.caption) continue;
            boolean on = i == sel && zone == ZONE_RIGHT;
            int color = on ? solid : ink;
            it.view.setBackground(pill(on ? (moving ? 0xFFE9E4D8 : ink) : 0x1AFFFFFF, 14));
            LinearLayout v = (LinearLayout) it.view;
            for (int k = 0; k < v.getChildCount(); k++) {
                View c = v.getChildAt(k);
                if (c instanceof TextView) ((TextView) c).setTextColor(color);
                else if (c instanceof ImageView && it.pkg != null) {
                    ((ImageView) c).setImageTintList(android.content.res.ColorStateList.valueOf(color));
                }
            }
            if (it.pkg != null && i == sel && moving) it.valueView.setText(R.string.moving);
        }
    }

    private void selectRow(int i) {
        if (items.isEmpty()) return;
        int dir = i >= sel ? 1 : -1;
        while (i >= 0 && i < items.size() && !selectable(items.get(i))) i += dir;
        if (i < 0 || i >= items.size()) return;
        sel = i;
        paintRows();
        ensureVisible(true);
    }

    private void ensureVisible(boolean animate) {
        if (sel < 0 || sel >= items.size()) return;
        View v = items.get(sel).view;
        v.post(() -> {
            int h = scroll.getHeight(), top = v.getTop(), y = scroll.getScrollY();
            int margin = px(56);
            int target = y;
            if (top - y < margin) target = top - margin;
            else if (top + v.getHeight() - y > h - margin) target = top + v.getHeight() - h + margin;
            // Just above the first row: show the captions above it too.
            if (sel == firstRow()) target = 0;
            else if (sel == lastRow()) target = scroll.maxScroll();
            target = Math.max(0, Math.min(target, scroll.maxScroll()));
            if (target != y) scroll.glideTo(target, animate);
        });
    }

    // ---- Actions --------------------------------------------------------------------------------

    private void click() {
        if (sel < 0 || sel >= items.size()) return;
        Item it = items.get(sel);
        if (it.act == null) return;
        it.act.run();
        if (!isFinishing()) refresh();
    }

    /** A change the TV refused shows once in the hint line. */
    private void check(boolean ok) {
        if (!ok) note = getString(R.string.refused);
    }

    private void open(Intent i) {
        try {
            startActivity(new Intent(i).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            note = getString(R.string.refused);
            refresh();
        }
    }

    /** Start Home again from scratch (a fresh activity, wallpapers and app list read anew). */
    private void restartHome() {
        Intent i = new Intent(this, MainActivity.class)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra(MainActivity.EXTRA_HOME_KEY, true);
        startActivity(i);
        finish();
    }

    // ---- Moving apps ----------------------------------------------------------------------------

    private void startMove() {
        if (section != S_APPS || sel < 0 || items.get(sel).pkg == null) return;
        moving = true;
        refresh();
    }

    private void moveBy(int d) {
        int j = sel + d;
        if (j < 0 || j >= apps.size()) return;
        Apps.Entry e = apps.remove(sel);
        apps.add(j, e);
        sel = j;
        refresh();
        ensureVisible(true);
    }

    private void endMove() {
        moving = false;
        List<String> order = new ArrayList<>();
        for (Apps.Entry e : apps) order.add(e.pkg);
        Apps.saveOrder(this, order);
        refresh();
    }

    // ---- Keys -----------------------------------------------------------------------------------

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
        boolean dpad = code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
                || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT;
        if (!ok && !dpad) return super.dispatchKeyEvent(e);

        if (ok) {
            // OK acts when released, so holding it (on an app) can pick the app up instead.
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                if (e.getRepeatCount() == 0) {
                    okDown = true;
                    okLong = false;
                } else if (okDown && !okLong && zone == ZONE_RIGHT && !moving && section == S_APPS) {
                    okLong = true;
                    startMove();
                }
            } else if (e.getAction() == KeyEvent.ACTION_UP && okDown) {
                okDown = false;
                if (okLong) return true;
                if (zone == ZONE_LEFT) setZone(ZONE_RIGHT);
                else if (moving) endMove();
                else click();
            }
            return true;
        }
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        if (zone == ZONE_LEFT) {
            if (code == KeyEvent.KEYCODE_DPAD_UP) selectSection(section - 1);
            else if (code == KeyEvent.KEYCODE_DPAD_DOWN) selectSection(section + 1);
            else if (code == KeyEvent.KEYCODE_DPAD_RIGHT) setZone(ZONE_RIGHT);
        } else if (moving) {
            if (code == KeyEvent.KEYCODE_DPAD_UP) moveBy(-1);
            else if (code == KeyEvent.KEYCODE_DPAD_DOWN) moveBy(1);
        } else {
            if (code == KeyEvent.KEYCODE_DPAD_UP) selectRow(sel - 1);
            else if (code == KeyEvent.KEYCODE_DPAD_DOWN) selectRow(sel + 1);
            else if (code == KeyEvent.KEYCODE_DPAD_LEFT) setZone(ZONE_LEFT);
        }
        return true;
    }

    private void setZone(int z) {
        if (z == ZONE_RIGHT && firstRow() < 0) return;
        zone = z;
        if (z == ZONE_RIGHT && (sel < 0 || sel >= items.size() || !selectable(items.get(sel)))) sel = firstRow();
        paintSections();
        paintRows();
        if (z == ZONE_RIGHT) ensureVisible(true);
    }

    @Override
    public void onBackPressed() {
        if (moving) endMove();
        else if (zone == ZONE_RIGHT) setZone(ZONE_LEFT);
        else finish();
    }

    // ---- Small helpers --------------------------------------------------------------------------

    /** The option after the current one (or the first one above it, for a value set elsewhere). */
    private static int next(int[] opts, int cur) {
        for (int i = 0; i < opts.length; i++) if (opts[i] == cur) return opts[(i + 1) % opts.length];
        for (int o : opts) if (o > cur) return o;
        return opts[0];
    }

    private String onOff(boolean on) { return getString(on ? R.string.on : R.string.off); }

    private String minutes(int m) {
        if (m < 0) return getString(R.string.never);
        if (m >= 60 && m % 60 == 0) return getString(R.string.hours, m / 60);
        return getString(R.string.min, m);
    }
}
