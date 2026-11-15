package ro.reflex.uvstudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Live view, natively: the radio pushes 1 bpp screen frames (type 0x01 full,
 * 0x02 diff) on the AA 55 stream, we paint them into a 128x64 bitmap and send
 * key events back as AA 55 03/04 key.
 *
 * The firmware only emits frames while it receives a keepalive every 200 ms,
 * so this screen owns that heartbeat for as long as it is visible.
 */
final class LiveScreen {

    private static final int KEEPALIVE_MS = 200;
    private static final int LONG_PRESS_MS = 500;

    private final MainActivity app;
    private final RadioLink link;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private LcdView lcd;
    private TextView status;
    private TextView fpsLabel;
    private TextView ledRed;
    private TextView ledGreen;
    private TextView deepSleepLabel;
    private TextView k1Chip;
    private TextView k5Chip;
    private TextView upButton;
    private TextView downButton;

    private final byte[] frameBuffer = new byte[UvFrames.FRAME_SIZE];
    private volatile boolean active;
    private Thread keepalive;
    private volatile boolean streaming;
    private boolean k1 = true;

    private int frames;
    private long framesSince = SystemClock.elapsedRealtime();

    LiveScreen(MainActivity app, RadioLink link) {
        this.app = app;
        this.link = link;
    }

    View create() {
        LinearLayout root = new LinearLayout(app);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setPadding(Ui.dp(app, 12), Ui.dp(app, 10), Ui.dp(app, 12), Ui.dp(app, 12));

        root.addView(buildHeader());

        /* The LCD is its own frame: no card padding around it, so the screen
         * fills the full width instead of floating inside a dark border. */
        lcd = new LcdView(app);
        LinearLayout.LayoutParams lcdParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lcdParams.topMargin = Ui.dp(app, 10);
        root.addView(lcd, lcdParams);

        root.addView(buildIndicators());
        root.addView(buildKeypad(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView hint = Ui.text(app, "Long-press a key for the radio's long press. "
                + "PTT is not available over serial.", 10, Ui.MUTED, false);
        hint.setPadding(0, Ui.dp(app, 6), 0, 0);
        root.addView(hint);

        root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                start();
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                stop();
            }
        });
        return root;
    }

    /* ------------------------------ header -------------------------------- */

    private View buildHeader() {
        LinearLayout row = Ui.row(app);

        status = Ui.text(app, "Connecting…", 12, Ui.MUTED, true);
        row.addView(status, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        fpsLabel = Ui.text(app, "", 11, Ui.MUTED, false);
        fpsLabel.setPadding(0, 0, Ui.dp(app, 8), 0);
        row.addView(fpsLabel);

        TextView restart = Ui.secondary(app, "Restart");
        restart.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
        restart.setPadding(Ui.dp(app, 10), Ui.dp(app, 7), Ui.dp(app, 10), Ui.dp(app, 7));
        restart.setOnClickListener(v -> restartRadio());
        row.addView(restart);
        return row;
    }

    private View buildIndicators() {
        LinearLayout row = Ui.row(app);
        LinearLayout.LayoutParams params = Ui.matchWrap();
        params.topMargin = Ui.dp(app, 8);
        row.setLayoutParams(params);
        row.setGravity(Gravity.CENTER_VERTICAL);

        k1Chip = Ui.chip(app, "UV-K1", true);
        k1Chip.setOnClickListener(v -> setModel(true));
        row.addView(k1Chip);
        k5Chip = Ui.chip(app, "UV-K5", false);
        k5Chip.setOnClickListener(v -> setModel(false));
        LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chipParams.leftMargin = Ui.dp(app, 6);
        row.addView(k5Chip, chipParams);

        View spacer = new View(app);
        row.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        ledRed = Ui.text(app, "●", 14, 0x33F87171, false);
        ledRed.setPadding(0, 0, Ui.dp(app, 4), 0);
        row.addView(ledRed);
        ledGreen = Ui.text(app, "●", 14, 0x3334D399, false);
        row.addView(ledGreen);

        deepSleepLabel = Ui.text(app, "", 10, Ui.WARN, true);
        deepSleepLabel.setPadding(Ui.dp(app, 10), 0, 0, 0);
        row.addView(deepSleepLabel);
        return row;
    }

    private void setModel(boolean isK1) {
        k1 = isK1;
        k1Chip.setBackground(Ui.rounded(isK1 ? Ui.ACCENT : Ui.CARD, Ui.dp(app, 12), isK1 ? 0 : Ui.LINE, isK1 ? 0 : 1));
        k1Chip.setTextColor(isK1 ? Ui.ACCENT_INK : Ui.MUTED);
        k5Chip.setBackground(Ui.rounded(!isK1 ? Ui.ACCENT : Ui.CARD, Ui.dp(app, 12), !isK1 ? 0 : Ui.LINE, !isK1 ? 0 : 1));
        k5Chip.setTextColor(!isK1 ? Ui.ACCENT_INK : Ui.MUTED);
        if (upButton != null) upButton.setText(isK1 ? "◀" : "▲");
        if (downButton != null) downButton.setText(isK1 ? "▶" : "▼");
    }

    /* ------------------------------ keypad -------------------------------- */

    private View buildKeypad() {
        GridLayout keypad = new GridLayout(app);
        keypad.setColumnCount(4);
        keypad.setPadding(0, Ui.dp(app, 10), 0, 0);
        keypad.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        addKey(keypad, "MENU", null, 0x0A, 1.15f);
        upButton = addKey(keypad, k1 ? "◀" : "▲", null, 0x0B, 1f);
        downButton = addKey(keypad, k1 ? "▶" : "▼", null, 0x0C, 1f);
        addKey(keypad, "EXIT", null, 0x0D, 1.15f);

        addKey(keypad, "1", "Band ,.?", 0x01, 1f);
        addKey(keypad, "2", "A/B abc", 0x02, 1f);
        addKey(keypad, "3", "V/M def", 0x03, 1f);
        addKey(keypad, "✱", "Scan", 0x0E, 1f);

        addKey(keypad, "4", "FC ghi", 0x04, 1f);
        addKey(keypad, "5", "WX jkl", 0x05, 1f);
        addKey(keypad, "6", "H/L mno", 0x06, 1f);
        addKey(keypad, "0", "FM", 0x00, 1f);

        addKey(keypad, "7", "VOX pqrs", 0x07, 1f);
        addKey(keypad, "8", "R tuv", 0x08, 1f);
        addKey(keypad, "9", "Call wxyz", 0x09, 1f);
        addKey(keypad, "#", "F", 0x0F, 1f);

        addKey(keypad, "F1", "SIDE1", 0x12, 1f);
        addKey(keypad, "F2", "SIDE2", 0x11, 1f);
        return keypad;
    }

    private TextView addKey(GridLayout grid, String label, String sub, final int code, float weight) {
        LinearLayout key = new LinearLayout(app);
        key.setOrientation(LinearLayout.VERTICAL);
        key.setGravity(Gravity.CENTER);
        key.setPadding(0, Ui.dp(app, 6), 0, Ui.dp(app, 6));
        key.setBackground(Ui.rounded(Ui.CARD, Ui.dp(app, 10), Ui.ACCENT_LINE, 1));

        TextView main = Ui.text(app, label, 14, Ui.ACCENT, true);
        main.setGravity(Gravity.CENTER);
        key.addView(main);
        if (sub != null) {
            TextView small = Ui.text(app, sub, 8, Ui.MUTED, false);
            small.setGravity(Gravity.CENTER);
            small.setSingleLine(true);
            key.addView(small);
        }

        GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.width = 0;
        params.height = 0;
        params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, weight);
        params.rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f);
        params.setMargins(Ui.dp(app, 3), Ui.dp(app, 3), Ui.dp(app, 3), Ui.dp(app, 3));
        key.setLayoutParams(params);

        key.setOnTouchListener(new View.OnTouchListener() {
            private boolean longSent;
            private final Runnable longPress = () -> {
                longSent = true;
                sendKey(code, true);
                key.setBackground(Ui.rounded(Ui.ACCENT_GLOW, Ui.dp(app, 10), Ui.ACCENT, 1));
            };

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        longSent = false;
                        v.setBackground(Ui.rounded(Ui.ACCENT_GLOW, Ui.dp(app, 10), Ui.ACCENT, 1));
                        handler.postDelayed(longPress, LONG_PRESS_MS);
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        handler.removeCallbacks(longPress);
                        v.setBackground(Ui.rounded(Ui.CARD, Ui.dp(app, 10), Ui.ACCENT_LINE, 1));
                        return true;
                    case MotionEvent.ACTION_UP:
                        handler.removeCallbacks(longPress);
                        v.setBackground(Ui.rounded(Ui.CARD, Ui.dp(app, 10), Ui.ACCENT_LINE, 1));
                        if (!longSent) sendKey(code, false);
                        return true;
                    default:
                        return false;
                }
            }
        });
        grid.addView(key);
        return main;
    }

    private void sendKey(int code, boolean longPress) {
        if (!link.isOpen()) {
            app.toast("Connect the radio first.");
            return;
        }
        link.sendRaw(UvFrames.key(code, longPress));
    }

    /* --------------------------- lifecycle -------------------------------- */

    private void start() {
        active = true;
        link.setListener(new RadioLink.Listener() {
            @Override
            public void onPacket(RadioLink l, int type, byte[] data) { }

            @Override
            public void onFrame(RadioLink l, int type, int flags, byte[] payload) {
                handleFrame(type, flags, payload);
            }

            @Override
            public void onClosed(String reason) {
                handler.post(() -> {
                    status.setText("Radio disconnected");
                    status.setTextColor(Ui.WARN);
                });
            }
        });

        app.connect(ok -> {
            if (!ok || !active) {
                status.setText("No radio");
                status.setTextColor(Ui.WARN);
                return;
            }
            status.setText("Waiting for frames…");
            status.setTextColor(Ui.MUTED);
            link.sendRaw(UvFrames.KEEPALIVE);
        });
        startKeepalive();
    }

    private void stop() {
        active = false;
        streaming = false;
        link.setListener(null);
        Thread t = keepalive;
        keepalive = null;
        if (t != null) t.interrupt();
    }

    private void startKeepalive() {
        if (keepalive != null) return;
        keepalive = new Thread(() -> {
            while (active) {
                if (link.isOpen()) {
                    link.sendRaw(UvFrames.KEEPALIVE);
                    link.sendRaw(UvFrames.featureKeepalive(false));
                }
                try {
                    Thread.sleep(KEEPALIVE_MS);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "uv-keepalive");
        keepalive.setDaemon(true);
        keepalive.start();
    }

    private void restartRadio() {
        if (!link.isOpen()) {
            app.toast("Connect the radio first.");
            return;
        }
        status.setText("Restarting…");
        status.setTextColor(Ui.WARN);
        link.sendRaw(UvFrames.REBOOT);
        handler.postDelayed(() -> {
            link.close();
            if (!active) return;
            status.setText("Radio restarted · reconnect");
            status.setTextColor(Ui.MUTED);
        }, 800);
    }

    /* ----------------------------- rendering ------------------------------ */

    private void handleFrame(int type, int flags, byte[] payload) {
        if (type == UvFrames.TYPE_SCREENSHOT && payload.length == UvFrames.FRAME_SIZE) {
            System.arraycopy(payload, 0, frameBuffer, 0, frameBuffer.length);
        } else if (type == UvFrames.TYPE_DIFF && payload.length % 9 == 0) {
            UvFrames.applyDiff(frameBuffer, payload);
        } else {
            return;
        }

        boolean deepSleep = (flags & UvFrames.FLAG_DEEP_SLEEP) != 0;
        boolean red = (flags & UvFrames.FLAG_LED_RED) != 0;
        boolean green = (flags & UvFrames.FLAG_LED_GREEN) != 0;
        streaming = true;

        handler.post(() -> {
            frames++;
            if (lcd != null) lcd.invalidateFrame();
            ledRed.setTextColor(red ? Ui.DANGER : 0x33F87171);
            ledGreen.setTextColor(green ? Ui.OK : 0x3334D399);
            deepSleepLabel.setText(deepSleep ? "DEEP SLEEP" : "");
            status.setText(deepSleep ? "Connected · deep sleep" : "Live");
            status.setTextColor(deepSleep ? Ui.WARN : Ui.ACCENT);

            long now = SystemClock.elapsedRealtime();
            if (now - framesSince >= 1000) {
                fpsLabel.setText(frames + " fps");
                frames = 0;
                framesSince = now;
            }
        });
    }

    /* ------------------------------- LCD ---------------------------------- */

    private final class LcdView extends View {
        private final Bitmap bitmap = Bitmap.createBitmap(
                UvFrames.WIDTH, UvFrames.HEIGHT, Bitmap.Config.ARGB_8888);
        private final int[] pixels = new int[UvFrames.WIDTH * UvFrames.HEIGHT];
        private final Paint paint = new Paint();
        private final Paint grid = new Paint();
        private final Rect src = new Rect(0, 0, UvFrames.WIDTH, UvFrames.HEIGHT);
        private final Rect dst = new Rect();

        LcdView(Context context) {
            super(context);
            paint.setFilterBitmap(false);
            paint.setAntiAlias(false);
            grid.setColor(Ui.LCD_BG);
            grid.setStyle(Paint.Style.STROKE);
            grid.setStrokeWidth(1f);
            grid.setAntiAlias(false);
            setBackground(Ui.rounded(Ui.LCD_BG, 0, 0, 0));
        }

        void invalidateFrame() {
            for (int i = 0; i < pixels.length; i++) {
                pixels[i] = UvFrames.bit(frameBuffer, i % UvFrames.WIDTH, i / UvFrames.WIDTH) != 0
                        ? Ui.LCD_INK : Ui.LCD_BG;
            }
            bitmap.setPixels(pixels, 0, UvFrames.WIDTH, 0, 0, UvFrames.WIDTH, UvFrames.HEIGHT);
            invalidate();
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec);
            if (width <= 0) width = Ui.dp(app, 320);
            /* one extra pixel of white under the 2:1 bitmap, so the last LCD
             * row is not glued to the frame */
            setMeasuredDimension(width, Math.max(1, width / 2 + 1));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int contentH = Math.max(1, getHeight() - 1);
            dst.set(0, 0, getWidth(), contentH);
            canvas.drawBitmap(bitmap, src, dst, paint);

            /* Separate every LCD pixel with a hairline of background, so the
             * 128x64 grid stays visible instead of melting into solid blocks.
             * On a phone the cells are only ~3 px, so a full-strength gap would
             * shred the glyphs; the line gets lighter until the cells are big. */
            float cw = getWidth() / (float) UvFrames.WIDTH;
            float ch = contentH / (float) UvFrames.HEIGHT;
            if (cw >= 2.2f) {
                int alpha = cw >= 4f ? 255 : cw >= 3.2f ? 170 : 110;
                grid.setColor((Ui.LCD_BG & 0x00FFFFFF) | (alpha << 24));
                for (int x = 1; x < UvFrames.WIDTH; x++) {
                    float px = x * cw;
                    canvas.drawLine(px, 0, px, contentH, grid);
                }
                for (int y = 1; y < UvFrames.HEIGHT; y++) {
                    float py = y * ch;
                    canvas.drawLine(0, py, getWidth(), py, grid);
                }
            }

            if (!streaming) {
                paint.setColor(0xCC000000);
                paint.setTextSize(Ui.dp(app, 13));
                paint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText("Waiting for the radio screen…",
                        getWidth() / 2f, getHeight() / 2f, paint);
            }
        }
    }
}
