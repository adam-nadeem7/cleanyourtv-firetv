package tv.clean.home;

import android.app.Activity;
import android.graphics.Color;
import android.media.tv.TvContract;
import android.media.tv.TvView;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * Shows one HDMI / AV input full screen through the TV input framework. Fire OS's own input
 * switcher needs an Amazon signature, so the launcher plays passthrough inputs itself.
 * Back (or Home) leaves; the input session is released in onStop.
 *
 * While the input shows a picture the screen is kept on: the TV's screensaver (5 min) and sleep
 * (14 min) timers only count remote presses, so a console session would otherwise look idle. With no
 * signal the flag is dropped again, so an empty input can't keep the TV awake.
 */
public class InputPlayerActivity extends Activity {
    static final String EXTRA_INPUT_ID = "input_id";
    static final String EXTRA_LABEL = "label";

    private TvView tv;
    private TextView message;
    private String label;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        tv = new TvView(this);
        root.addView(tv, new FrameLayout.LayoutParams(-1, -1));
        message = new TextView(this);
        message.setTextColor(0xCCFFFFFF);
        message.setTextSize(22);
        message.setTypeface(Fonts.get(this, 500));
        message.setGravity(Gravity.CENTER);
        root.addView(message, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);

        String inputId = getIntent().getStringExtra(EXTRA_INPUT_ID);
        label = getIntent().getStringExtra(EXTRA_LABEL);
        if (inputId == null) {
            finish();
            return;
        }
        message.setText(label);
        tv.setCallback(new TvView.TvInputCallback() {
            @Override public void onVideoAvailable(String id) {
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                message.animate().alpha(0f).setStartDelay(1200).setDuration(400).start();
            }

            @Override public void onVideoUnavailable(String id, int reason) {
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                show(getString(R.string.no_signal, label));
            }

            @Override public void onConnectionFailed(String id) {
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                show(getString(R.string.input_failed, label));
            }

            @Override public void onDisconnected(String id) {
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                show(getString(R.string.input_failed, label));
            }
        });
        tv.tune(inputId, TvContract.buildChannelUriForPassthroughInput(inputId));
    }

    private void show(String text) {
        message.animate().cancel();
        message.setText(text);
        message.setAlpha(1f);
        message.setVisibility(View.VISIBLE);
    }

    @Override
    protected void onStop() {
        super.onStop();
        tv.reset();
        finish();
    }
}
