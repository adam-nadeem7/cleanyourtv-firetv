package tv.clean.home;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;

import java.util.ArrayList;
import java.util.List;

/** The TV's inputs for the Inputs panel and the PS5 tile: Live TV, the HDMI ports and AV. */
final class Inputs {
    private Inputs() {}

    private static final String LIVE_TV = "com.amazon.tv.livetv";

    static final class Item {
        final String label, inputId;   // inputId null = Live TV
        Item(String label, String inputId) { this.label = label; this.inputId = inputId; }
    }

    static List<Item> items(Activity a) {
        List<Item> out = new ArrayList<>();
        out.add(new Item(a.getString(R.string.live_tv), null));
        TvInputManager tim = a.getSystemService(TvInputManager.class);
        if (tim == null) return out;
        List<TvInputInfo> hdmi = new ArrayList<>(), other = new ArrayList<>();
        for (TvInputInfo info : tim.getTvInputList()) {
            if (!info.isPassthroughInput() || info.getParentId() != null || info.isHidden(a)) continue;
            if (info.getType() == TvInputInfo.TYPE_HDMI) hdmi.add(info);
            else if (info.getType() != TvInputInfo.TYPE_TUNER && info.getType() != TvInputInfo.TYPE_OTHER) other.add(info);
        }
        hdmi.sort((x, y) -> label(a, x).compareTo(label(a, y)));
        String ps5 = a.getString(R.string.ps5_input);
        for (TvInputInfo i : hdmi) {
            String l = label(a, i);
            out.add(new Item(i.getId().equals(ps5) ? l + " \u00b7 PS5" : l, i.getId()));
        }
        for (TvInputInfo i : other) {
            String l = i.getType() == TvInputInfo.TYPE_COMPOSITE && i.loadCustomLabel(a) == null ? "AV" : label(a, i);
            out.add(new Item(l, i.getId()));
        }
        return out;
    }

    private static String label(Activity a, TvInputInfo i) {
        CharSequence c = i.loadCustomLabel(a);
        if (c == null || c.length() == 0) c = i.loadLabel(a);
        return c.toString().replaceFirst("^HDMI\\s*(\\d)", "HDMI $1");
    }

    static void open(Activity a, Item it) {
        if (it.inputId != null) {
            a.startActivity(new Intent(a, InputPlayerActivity.class)
                    .putExtra(InputPlayerActivity.EXTRA_INPUT_ID, it.inputId)
                    .putExtra(InputPlayerActivity.EXTRA_LABEL, it.label));
            return;
        }
        // Live TV: Amazon's player tunes a channel URI from any app (its own deep links are locked).
        long id = firstChannel(a);
        Uri uri = id > 0 ? TvContract.buildChannelUri(id) : Uri.parse("content://android.media.tv/channel/1");
        try {
            a.startActivity(new Intent(Intent.ACTION_VIEW, uri).setPackage(LIVE_TV)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException ignored) {
        }
    }

    /** Lowest-numbered browsable channel, or -1 if the channel list can't be read. */
    private static long firstChannel(Activity a) {
        String[] proj = {TvContract.Channels._ID, TvContract.Channels.COLUMN_DISPLAY_NUMBER};
        try (Cursor c = a.getContentResolver().query(TvContract.Channels.CONTENT_URI, proj,
                TvContract.Channels.COLUMN_BROWSABLE + "=1", null, null)) {
            if (c == null) return -1;
            long best = -1;
            double bestNum = Double.MAX_VALUE;
            while (c.moveToNext()) {
                double n;
                try {
                    n = Double.parseDouble(c.getString(1).replace('-', '.'));
                } catch (Exception e) {
                    n = Double.MAX_VALUE - 1;
                }
                if (n < bestNum) {
                    bestNum = n;
                    best = c.getLong(0);
                }
            }
            return best;
        } catch (Exception e) {
            return -1;
        }
    }
}
