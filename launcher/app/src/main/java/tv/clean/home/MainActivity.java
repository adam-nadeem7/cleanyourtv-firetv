package tv.clean.home;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvInputManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Home, after CleanFrame's List layout: the apps as large names with monochrome marks over a
 * photograph, one glass plate that stays put while the list scrolls under it, the time top right
 * and four quiet utilities bottom right (Wallpaper, Inputs, Fire TV UI, Settings).
 *
 * Nothing takes Android focus; this activity holds the state and handles every key, so Up/Down
 * move exactly one row, nothing wraps, Right goes to the utilities, Left or Back comes back.
 */
public class MainActivity extends Activity {
    static final String EXTRA_HOME_KEY = "home_key";

    private static final int ZONE_LIST = 0, ZONE_UTILS = 1, ZONE_SHEET = 2;
    private static final int U_WALL = 0, U_INPUTS = 1, U_FIRETV = 2, U_SETTINGS = 3;
    private static final float ROW_IDLE = 0.84f, UTIL_IDLE = 0.66f;

    private static final class Row {
        Apps.Entry app;            // null = the PS5
        View view, name, status;
    }

    private View root;
    private ImageView wallpaperView;
    private View dim, scrim;
    private GlassPlate plate, sheetGlass;
    private Scrollers.Vertical listScroll;
    private LinearLayout column, utils, sheetRows;
    private TextView utilLabel, clock;
    private FrameLayout sheet;

    private final List<Row> rows = new ArrayList<>();
    private final List<View> utilViews = new ArrayList<>();
    private final List<TextView> sheetItems = new ArrayList<>();
    private final List<Runnable> sheetActions = new ArrayList<>();
    private int zone = ZONE_LIST, sel, utilSel, sheetSel;

    private List<Wallpapers.W> walls = new ArrayList<>();
    private int wallIdx;
    private long ownStamp = -1;

    private TvInputManager tim;
    private String ps5Input;
    private boolean ps5On;
    private CapybaraView capy;

    private float dp;
    private int ink, rowH, plateH, anchor;
    private long orderStamp = -1;
    private boolean appsDirty = true;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Runnable hideLabel = () -> utilLabel.animate().alpha(0f).setDuration(160).start();
    private static final long SHUFFLE_MS = 3 * 60 * 1000;
    private final java.util.Random random = new java.util.Random();
    /** Every 3 minutes on screen: a random other photo. Paused while an app or input is open. */
    private final Runnable shuffle = new Runnable() {
        @Override public void run() {
            if (walls.size() > 1) {
                int next = random.nextInt(walls.size() - 1);
                wallIdx = next >= wallIdx ? next + 1 : next;
                Prefs.setWallpaper(MainActivity.this, walls.get(wallIdx).id);
                showWallpaper(true);
            }
            main.postDelayed(this, SHUFFLE_MS);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        CrashGuard.install(this);
        WatchKeeper.ensure(this);
        setContentView(R.layout.activity_main);
        dp = getResources().getDisplayMetrics().density;
        ink = getColor(R.color.ink);
        rowH = getResources().getDimensionPixelSize(R.dimen.row_h);
        plateH = getResources().getDimensionPixelSize(R.dimen.plate_h);
        anchor = getResources().getDimensionPixelSize(R.dimen.anchor);

        root = findViewById(R.id.root);
        wallpaperView = findViewById(R.id.wallpaper);
        dim = findViewById(R.id.dim);
        scrim = findViewById(R.id.scrim);
        plate = findViewById(R.id.plate);
        listScroll = findViewById(R.id.list_scroll);
        column = findViewById(R.id.column);
        utils = findViewById(R.id.utils);
        utilLabel = findViewById(R.id.util_label);
        clock = findViewById(R.id.clock);
        sheet = findViewById(R.id.sheet);
        sheetGlass = findViewById(R.id.sheet_glass);
        sheetRows = findViewById(R.id.sheet_rows);

        // The list owns the keys; nothing below takes focus.
        root.setFocusable(true);
        root.setFocusableInTouchMode(true);
        root.requestFocus();

        int listH = Math.round(472 * dp);   // the list's height in the layout
        column.setPadding(0, anchor, 0, listH - anchor - rowH);
        clock.setTypeface(Fonts.get(this, 500));
        shadow(clock);
        utilLabel.setTypeface(Fonts.get(this, 500));
        shadow(utilLabel);
        sheetGlass.setRadius(22 * dp);
        buildUtils();

        ps5Input = getString(R.string.ps5_input);
        tim = getSystemService(TvInputManager.class);
        if (tim != null) {
            tim.registerCallback(inputs, main);
            ps5On = tim.getInputState(ps5Input) == TvInputManager.INPUT_STATE_CONNECTED;
        }

        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pf.addDataScheme("package");
        registerReceiver(packages, pf);
        // Once Android has had its chance to bind the Home watch, fix it if it didn't (after an update).
        main.postDelayed(() -> WatchKeeper.rebindIfUnbound(this), 6000);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Also runs on Home while already here, so set-order.sh / set-wallpaper.sh apply at once.
        long ownT = Wallpapers.ownFile(this).lastModified();
        long own = ownT + new java.io.File(new java.io.File(getFilesDir(), "photos"), "names.txt").lastModified();
        if (own != ownStamp) {
            // A photo just pushed with set-wallpaper.sh is shown straight away (remembered across
            // activity instances: Fire OS recreates Home now and then). New film photos just join.
            long seen = Prefs.ownStamp(this);
            if (ownT > 0 && ownT != seen) {
                if (seen != 0) Prefs.setWallpaper(this, Wallpapers.OWN);
                Prefs.setOwnStamp(this, ownT);
            }
            ownStamp = own;
            walls = Wallpapers.all(this);
            String want = Prefs.wallpaper(this);
            wallIdx = 0;
            for (int i = 0; i < walls.size(); i++) if (walls.get(i).id.equals(want)) wallIdx = i;
            showWallpaper(false);
        }
        main.removeCallbacks(shuffle);
        main.postDelayed(shuffle, SHUFFLE_MS);
        long stamp = Apps.orderFile(this).lastModified();
        if (appsDirty || stamp != orderStamp) {
            appsDirty = false;
            orderStamp = stamp;
            reloadApps();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        main.removeCallbacks(shuffle);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(packages);
        if (tim != null) tim.unregisterCallback(inputs);
        io.shutdown();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home: back to the top of the list, like a fresh start.
        if (intent.getBooleanExtra(EXTRA_HOME_KEY, false)) resetToTop();
    }

    private void resetToTop() {
        closeSheet();
        setZone(ZONE_LIST);
        select(0, false);
    }

    // ---- The list -------------------------------------------------------------------------------

    private void reloadApps() {
        io.execute(() -> {
            List<Apps.Entry> apps = Apps.load(this);
            runOnUiThread(() -> buildList(apps));
        });
    }

    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            appsDirty = true;
            if (hasWindowFocus()) {
                appsDirty = false;
                reloadApps();
            }
        }
    };

    private void buildList(List<Apps.Entry> apps) {
        String keep = sel < rows.size() && rows.get(sel).app != null ? rows.get(sel).app.pkg : null;
        column.removeAllViews();
        rows.clear();
        rows.add(row(null));
        for (Apps.Entry e : apps) rows.add(row(e));
        for (Row r : rows) column.addView(r.view, new LinearLayout.LayoutParams(-2, rowH));

        int target = 0;
        for (int i = 0; keep != null && i < rows.size(); i++) {
            if (rows.get(i).app != null && keep.equals(rows.get(i).app.pkg)) target = i;
        }
        final int t = target;
        column.post(() -> select(t, false));
    }

    private Row row(Apps.Entry app) {
        Row r = new Row();
        r.app = app;
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.HORIZONTAL);
        v.setGravity(Gravity.CENTER_VERTICAL);
        v.setPadding(Math.round(13 * dp), 0, Math.round(25 * dp), 0);
        v.setAlpha(ROW_IDLE);
        v.setPivotX(28 * dp);
        v.setPivotY(rowH / 2f);

        // The mark, in a 36 x 28 dp box; wordmarks fill its width, square marks its height.
        View mark;
        if (app == null) {
            capy = new CapybaraView(this);
            capy.setMono(ink);
            capy.setAwake(ps5On);
            capy.setLive(false);
            mark = capy;
        } else {
            int res = Marks.mark(app.pkg);
            if (res != 0) {
                ImageView iv = new ImageView(this);
                iv.setImageResource(res);
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                iv.setImageTintList(android.content.res.ColorStateList.valueOf(ink));
                mark = iv;
            } else {
                mark = monogram(app.label);
            }
        }
        v.addView(mark, new LinearLayout.LayoutParams(Math.round((app == null ? 41 : 32) * dp), Math.round(25 * dp)));

        TextView name = new TextView(this);
        name.setText(app == null ? getString(R.string.ps5) : Marks.name(app.pkg, app.label));
        name.setTextSize(40);
        name.setTypeface(Fonts.get(this, 600));
        name.setLetterSpacing(-0.02f);
        name.setTextColor(ink);
        name.setIncludeFontPadding(false);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setMaxWidth(Math.round(470 * dp));
        shadow(name);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-2, -2);
        np.leftMargin = Math.round((app == null ? 7 : 13) * dp);   // names line up
        v.addView(name, np);
        r.name = name;

        if (app == null) {
            TextView status = new TextView(this);
            status.setTextSize(16);
            status.setTypeface(Fonts.get(this, 500));
            status.setTextColor(ink);
            status.setIncludeFontPadding(false);
            status.setAlpha(0f);
            status.setText(ps5On ? R.string.ps5_awake : R.string.ps5_asleep);
            shadow(status);
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, -2);
            sp.leftMargin = Math.round(13 * dp);
            sp.topMargin = Math.round(7 * dp);
            v.addView(status, sp);
            r.status = status;
        }
        r.view = v;
        return r;
    }

    private View monogram(CharSequence label) {
        TextView t = new TextView(this);
        t.setText(label.length() > 0 ? label.subSequence(0, 1).toString().toUpperCase() : "?");
        t.setTextSize(15);
        t.setTypeface(Fonts.get(this, 700));
        t.setTextColor(ink);
        t.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(8 * dp);
        g.setStroke(Math.round(1.6f * dp), (ink & 0x00FFFFFF) | 0x61000000);
        t.setBackground(g);
        return t;
    }

    /** Select list row i: the list glides so the row sits under the plate; the plate fits it. */
    private void select(int i, boolean animate) {
        if (rows.isEmpty()) return;
        i = Math.max(0, Math.min(i, rows.size() - 1));
        Row old = sel < rows.size() ? rows.get(sel) : null;
        sel = i;
        Row r = rows.get(i);
        listScroll.glideTo(i * rowH, animate);
        for (int k = 0; k < rows.size(); k++) {
            Row x = rows.get(k);
            boolean on = k == i;
            float a = on ? (zone == ZONE_LIST ? 1f : 0.92f) : ROW_IDLE;
            x.view.animate().alpha(a).setDuration(160).start();
            if (x.status != null) x.status.animate().alpha(on ? 0.62f : 0f).setDuration(160).start();
        }
        if (capy != null) capy.setLive(i == 0 && zone == ZONE_LIST);
        if (old != null && old != r) old.view.animate().scaleX(1f).scaleY(1f).setDuration(180).setInterpolator(GlassPlate.EASE).start();
        r.view.post(() -> fitPlate(animate));
    }

    private void fitPlate(boolean animate) {
        if (sel >= rows.size()) return;
        Row r = rows.get(sel);
        View last = r.status != null ? r.status : r.name;
        int width = last.getRight() + Math.round(25 * dp);
        int left = getResources().getDimensionPixelSize(R.dimen.list_left);
        int top = getResources().getDimensionPixelSize(R.dimen.list_top) + anchor - (plateH - rowH) / 2;
        plate.setPlate(left, top, width, plateH, animate);
    }

    private void updatePs5Status() {
        for (Row r : rows) {
            if (r.status != null) ((TextView) r.status).setText(ps5On ? R.string.ps5_awake : R.string.ps5_asleep);
        }
        if (capy != null) capy.setAwake(ps5On);
        if (!rows.isEmpty() && sel == 0) rows.get(0).view.post(() -> fitPlate(true));
    }

    private void openRow(int i) {
        Row r = rows.get(i);
        if (r.app == null) {
            Inputs.open(this, new Inputs.Item(getString(R.string.ps5_port) + " · PS5", ps5Input));
        } else {
            open(r.app.launch);
        }
    }

    // ---- Utilities and sheets ---------------------------------------------------------------------

    private void buildUtils() {
        int[] icons = {R.drawable.u_art, R.drawable.u_input, R.drawable.u_apps, R.drawable.u_settings};
        int cell = Math.round(32 * dp), icon = Math.round(16 * dp);
        for (int i = 0; i < icons.length; i++) {
            FrameLayout f = new FrameLayout(this);
            ImageView iv = new ImageView(this);
            iv.setImageResource(icons[i]);
            iv.setImageTintList(android.content.res.ColorStateList.valueOf(ink));
            f.addView(iv, new FrameLayout.LayoutParams(icon, icon, Gravity.CENTER));
            f.setAlpha(UTIL_IDLE);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cell, cell);
            if (i > 0) lp.topMargin = Math.round(4 * dp);
            utils.addView(f, lp);
            utilViews.add(f);
        }
    }

    private String utilName(int u) {
        switch (u) {
            case U_WALL: return getString(R.string.wallpaper);
            case U_INPUTS: return getString(R.string.inputs);
            case U_FIRETV: return getString(R.string.fire_tv_ui);
            default: return getString(R.string.settings);
        }
    }

    private void setZone(int z) {
        zone = z;
        if (capy != null) capy.setLive(sel == 0 && z == ZONE_LIST);
        plate.setLevel(z == ZONE_LIST ? 1f : 0.5f);
        for (int i = 0; i < utilViews.size(); i++) {
            View v = utilViews.get(i);
            boolean on = z != ZONE_LIST && i == utilSel;
            v.animate().alpha(z == ZONE_LIST ? UTIL_IDLE : on ? 1f : 0.5f)
                    .scaleX(on ? 1.15f : 1f).scaleY(on ? 1.15f : 1f).setDuration(140).start();
        }
        if (!rows.isEmpty()) {
            float a = z == ZONE_LIST ? 1f : 0.92f;
            rows.get(sel).view.animate().alpha(a).setDuration(160).start();
        }
        if (z == ZONE_UTILS) showLabel(utilName(utilSel), false);
        else if (z == ZONE_LIST) hideLabel.run();
    }

    private void selectUtil(int u) {
        utilSel = Math.max(0, Math.min(u, utilViews.size() - 1));
        setZone(ZONE_UTILS);
    }

    /** The focused utility's name, to the left of its icon. */
    private void showLabel(String text, boolean linger) {
        main.removeCallbacks(hideLabel);
        utilLabel.setText(text);
        View v = utilViews.get(utilSel);
        v.post(() -> {
            int[] at = new int[2];
            v.getLocationInWindow(at);
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) utilLabel.getLayoutParams();
            lp.topMargin = at[1];
            lp.rightMargin = root.getWidth() - at[0] + Math.round(10 * dp);
            utilLabel.setLayoutParams(lp);
            utilLabel.animate().alpha(1f).setDuration(140).start();
        });
        if (linger) main.postDelayed(hideLabel, 2200);
    }

    private void onUtil(int u) {
        switch (u) {
            case U_WALL:
                if (walls.isEmpty()) return;
                wallIdx = (wallIdx + 1) % walls.size();
                Prefs.setWallpaper(this, walls.get(wallIdx).id);
                showWallpaper(true);
                showLabel(walls.get(wallIdx).name, false);
                main.removeCallbacks(shuffle);
                main.postDelayed(shuffle, SHUFFLE_MS);
                break;
            case U_INPUTS: {
                List<String> labels = new ArrayList<>();
                List<Runnable> acts = new ArrayList<>();
                for (Inputs.Item it : Inputs.items(this)) {
                    labels.add(it.label);
                    acts.add(() -> Inputs.open(this, it));
                }
                openSheet(getString(R.string.inputs), labels, acts);
                break;
            }
            case U_FIRETV: {
                List<String> labels = new ArrayList<>();
                List<Runnable> acts = new ArrayList<>();
                Intent store = getPackageManager().getLaunchIntentForPackage("com.amazon.venezia");
                if (store != null) {
                    labels.add(getString(R.string.appstore));
                    acts.add(() -> open(store));
                }
                labels.add(getString(R.string.open_fire_tv));
                acts.add(this::openFireTv);
                openSheet(getString(R.string.fire_tv_ui), labels, acts);
                break;
            }
            case U_SETTINGS:
                openSettings();
                break;
        }
    }

    /** A compact glass sheet beside the utilities: a caption and a few rows. */
    private void openSheet(String caption, List<String> labels, List<Runnable> acts) {
        sheetRows.removeAllViews();
        sheetItems.clear();
        sheetActions.clear();
        sheetActions.addAll(acts);
        TextView cap = new TextView(this);
        cap.setText(caption);
        cap.setTextSize(13);
        cap.setTypeface(Fonts.get(this, 600));
        cap.setTextColor((ink & 0x00FFFFFF) | 0x99000000);
        cap.setPadding(Math.round(14 * dp), Math.round(6 * dp), 0, Math.round(8 * dp));
        sheetRows.addView(cap);
        for (String l : labels) {
            TextView t = new TextView(this);
            t.setText(l);
            t.setTextSize(18);
            t.setTypeface(Fonts.get(this, 500));
            t.setGravity(Gravity.CENTER_VERTICAL);
            t.setSingleLine(true);
            t.setPadding(Math.round(14 * dp), 0, Math.round(14 * dp), 0);
            sheetRows.addView(t, new LinearLayout.LayoutParams(-1, Math.round(42 * dp)));
            sheetItems.add(t);
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) sheet.getLayoutParams();
        lp.rightMargin = utils.getWidth() + ((FrameLayout.LayoutParams) utils.getLayoutParams()).rightMargin + Math.round(44 * dp);
        lp.bottomMargin = Math.round(28 * dp);
        sheet.setLayoutParams(lp);
        sheet.setVisibility(View.VISIBLE);
        sheet.setTranslationX(12 * dp);
        sheet.animate().alpha(1f).translationX(0).setDuration(180).setInterpolator(GlassPlate.EASE).start();
        zone = ZONE_SHEET;
        utilLabel.animate().alpha(0f).setDuration(120).start();
        selectSheet(0);
    }

    private void selectSheet(int i) {
        sheetSel = Math.max(0, Math.min(i, sheetItems.size() - 1));
        for (int k = 0; k < sheetItems.size(); k++) {
            TextView t = sheetItems.get(k);
            boolean on = k == sheetSel;
            if (on) {
                GradientDrawable g = new GradientDrawable();
                g.setCornerRadius(21 * dp);
                g.setColor(ink);
                t.setBackground(g);
                t.setTextColor(getColor(R.color.solid_ink));
            } else {
                t.setBackground(null);
                t.setTextColor((ink & 0x00FFFFFF) | 0xD9000000);
            }
        }
    }

    private void closeSheet() {
        if (sheet.getVisibility() != View.VISIBLE) return;
        sheet.animate().alpha(0f).setDuration(140).withEndAction(() -> sheet.setVisibility(View.GONE)).start();
        if (zone == ZONE_SHEET) selectUtil(utilSel);
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
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        if (ok) {
            if (e.getRepeatCount() > 0) return true;     // held OK acts once
            if (zone == ZONE_LIST) openRow(sel);
            else if (zone == ZONE_UTILS) onUtil(utilSel);
            else if (sheetSel < sheetActions.size()) {
                Runnable act = sheetActions.get(sheetSel);
                closeSheet();
                act.run();
            }
            return true;
        }
        switch (zone) {
            case ZONE_LIST:
                if (code == KeyEvent.KEYCODE_DPAD_UP && sel > 0) select(sel - 1, true);
                else if (code == KeyEvent.KEYCODE_DPAD_DOWN && sel < rows.size() - 1) select(sel + 1, true);
                else if (code == KeyEvent.KEYCODE_DPAD_RIGHT) selectUtil(utilSel);
                break;
            case ZONE_UTILS:
                if (code == KeyEvent.KEYCODE_DPAD_UP && utilSel > 0) selectUtil(utilSel - 1);
                else if (code == KeyEvent.KEYCODE_DPAD_DOWN && utilSel < utilViews.size() - 1) selectUtil(utilSel + 1);
                else if (code == KeyEvent.KEYCODE_DPAD_LEFT) setZone(ZONE_LIST);
                break;
            case ZONE_SHEET:
                if (code == KeyEvent.KEYCODE_DPAD_UP) selectSheet(sheetSel - 1);
                else if (code == KeyEvent.KEYCODE_DPAD_DOWN) selectSheet(sheetSel + 1);
                else if (code == KeyEvent.KEYCODE_DPAD_LEFT) closeSheet();
                break;
        }
        return true;
    }

    @Override
    public void onBackPressed() {
        // Never fall through to the Fire TV home. Sheet -> utilities -> list -> top of the list.
        if (zone == ZONE_SHEET) closeSheet();
        else if (zone == ZONE_UTILS) setZone(ZONE_LIST);
        else if (sel != 0) select(0, true);
    }

    // ---- The PS5 --------------------------------------------------------------------------------

    private final TvInputManager.TvInputCallback inputs = new TvInputManager.TvInputCallback() {
        @Override
        public void onInputStateChanged(String inputId, int state) {
            if (!ps5Input.equals(inputId)) return;
            boolean on = state == TvInputManager.INPUT_STATE_CONNECTED;
            if (on == ps5On) return;
            ps5On = on;
            updatePs5Status();
        }
    };

    // ---- Wallpaper ------------------------------------------------------------------------------

    /** Decode the photo and its glass field off the main thread, then cross-fade it in. */
    private void showWallpaper(boolean fade) {
        if (walls.isEmpty()) return;
        Wallpapers.W w = walls.get(wallIdx);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int sw = dm.widthPixels;
        float aspect = dm.widthPixels / (float) dm.heightPixels;
        io.execute(() -> {
            Bitmap full = Wallpapers.decode(this, w, sw);
            if (full == null) return;
            Bitmap field = Wallpapers.field(full, w, aspect);
            runOnUiThread(() -> {
                Runnable apply = () -> {
                    wallpaperView.setImageBitmap(full);
                    plate.setField(field, w.veil);
                    sheetGlass.setField(field, Math.max(0.42f, w.veil + 0.24f));
                    applyDim(w.light);
                    if (fade) wallpaperView.animate().alpha(1f).setDuration(260).start();
                };
                if (fade) wallpaperView.animate().alpha(0f).setDuration(160).withEndAction(apply).start();
                else apply.run();
            });
        });
    }

    /** CleanFrame's photo dim (stronger on bright photos) and lower scrim. */
    private void applyDim(boolean light) {
        int a0 = light ? 0x57 : 0x42, a1 = light ? 0x3D : 0x29, a2 = light ? 0x24 : 0x14;
        dim.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{a0 << 24 | 0x0A0806, a1 << 24 | 0x0A0806, a2 << 24 | 0x0A0806}));
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x000A0806, 0x420A0806, 0x660A0806}));
    }

    // ---- Actions --------------------------------------------------------------------------------

    private void shadow(TextView t) {
        t.setShadowLayer(6 * dp, 0, 1.5f * dp, 0x520E0A06);
    }

    private void open(Intent i) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) {
        }
    }

    /** Fire OS's Settings grid; android.settings.SETTINGS only bounces through the Amazon home. */
    private void openSettings() {
        open(new Intent("amazon.intent.action.MAINSETTINGS")
                .addCategory("android.intent.category.MAINSETTINGS")
                .setClassName(Apps.AMAZON_HOME, Apps.AMAZON_HOME + ".ui.MainSettingsActivity"));
    }

    /** The original Fire TV home; let through until another app comes to the front (HomeKeyService). */
    private void openFireTv() {
        Prefs.setAmazonHomeAllowed(this, true);
        open(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(Apps.AMAZON_HOME));
    }
}
