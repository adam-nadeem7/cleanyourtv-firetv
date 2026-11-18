package tv.clean.home;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The app tiles: apps that declare a TV (or phone) launcher entry, in the configured order. */
final class Apps {
    private Apps() {}

    static final String AMAZON_HOME = "com.amazon.tv.launcher";

    static final class Entry {
        final String pkg;
        final CharSequence label;
        final Intent launch;
        final Drawable banner;   // 16:9 TV banner, or null
        final Drawable icon;
        int glow;                // haze colour sampled from the banner, filled lazily

        Entry(String pkg, CharSequence label, Intent launch, Drawable banner, Drawable icon) {
            this.pkg = pkg;
            this.label = label;
            this.launch = launch;
            this.banner = banner;
            this.icon = icon;
        }
    }

    /** The apps on Home: everything launchable, minus what was hidden in Settings > Apps. */
    static List<Entry> load(Context ctx) {
        List<Entry> out = loadAll(ctx);
        java.util.Set<String> hidden = Prefs.hiddenApps(ctx);
        out.removeIf(e -> hidden.contains(e.pkg));
        return out;
    }

    /** Every launchable app (system plumbing left out), in the configured order. */
    static List<Entry> loadAll(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        String[] hidden = ctx.getResources().getStringArray(R.array.hidden_prefixes);
        Map<String, Entry> all = new LinkedHashMap<>();

        for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(cat);
            for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
                ActivityInfo ai = ri.activityInfo;
                String pkg = ai.packageName;
                if (all.containsKey(pkg) || isHidden(pkg, hidden)) continue;
                Intent launch = Intent.CATEGORY_LEANBACK_LAUNCHER.equals(cat)
                        ? pm.getLeanbackLaunchIntentForPackage(pkg) : pm.getLaunchIntentForPackage(pkg);
                if (launch == null) launch = new Intent(Intent.ACTION_MAIN).addCategory(cat).setClassName(pkg, ai.name);
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                Drawable banner = ai.loadBanner(pm);
                if (banner == null) banner = ai.applicationInfo.loadBanner(pm);
                all.put(pkg, new Entry(pkg, ri.loadLabel(pm), launch, banner, ri.loadIcon(pm)));
            }
        }

        List<Entry> out = new ArrayList<>();
        for (String pkg : order(ctx)) {
            Entry e = all.remove(pkg);
            if (e != null) out.add(e);
        }
        List<Entry> rest = new ArrayList<>(all.values());
        Collator col = Collator.getInstance();
        rest.sort((a, b) -> col.compare(a.label.toString(), b.label.toString()));
        out.addAll(rest);
        return out;
    }

    private static boolean isHidden(String pkg, String[] hidden) {
        for (String h : hidden) if (pkg.startsWith(h)) return true;
        return false;
    }

    static File orderFile(Context ctx) {
        return new File(ctx.getFilesDir(), "order.txt");
    }

    /** Settings > Apps: write the whole order to files/order.txt (the same file set-order.sh pushes). */
    static void saveOrder(Context ctx, List<String> pkgs) {
        try (java.io.FileWriter w = new java.io.FileWriter(orderFile(ctx))) {
            w.write("# Written by Settings > Apps on the TV\n");
            for (String p : pkgs) w.write(p + "\n");
        } catch (Exception ignored) {
        }
        Prefs.touchApps(ctx);
    }

    /** files/order.txt (one package per line) overrides the built-in order. */
    static List<String> order(Context ctx) {
        File f = orderFile(ctx);
        if (f.isFile()) {
            List<String> out = new ArrayList<>();
            try (BufferedReader br = new BufferedReader(new FileReader(f))) {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty() && !line.startsWith("#")) out.add(line);
                }
            } catch (Exception ignored) {
            }
            if (!out.isEmpty()) return out;
        }
        return Arrays.asList(ctx.getResources().getStringArray(R.array.app_order));
    }
}
