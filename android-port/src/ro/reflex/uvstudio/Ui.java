package ro.reflex.uvstudio;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Native widget factory for the app's dark theme.
 *
 * Everything is built in code (no XML) so a screen is one Java file and the
 * whole UI compiles with aapt2 + javac, without AppCompat or Gradle.
 */
final class Ui {

    static final int BG = 0xFF0B1220;
    static final int CARD = 0xFF131C2B;
    static final int LINE = 0xFF22304A;
    static final int TEXT = 0xFFE6EDF7;
    static final int MUTED = 0xFF8FA3BF;
    static final int ACCENT = 0xFFFF8A00;
    /** Text drawn on top of an accent-filled button. */
    static final int ACCENT_INK = 0xFF1A0D00;
    static final int ACCENT_SOFT = 0x1AFF8A00;
    static final int ACCENT_LINE = 0x55FF8A00;
    static final int ACCENT_GLOW = 0x33FF8A00;
    /** Radio LCD: white background, black pixels, visible 128x64 grid. */
    static final int LCD_BG = 0xFFFFFFFF;
    static final int LCD_INK = 0xFF000000;
    static final int WARN = 0xFFFBBF24;
    static final int DANGER = 0xFFF87171;
    static final int OK = 0xFF34D399;

    private Ui() { }

    /* ------------------------------ text --------------------------------- */

    static TextView text(Context ctx, CharSequence value, int sp, int color, boolean bold) {
        TextView view = new TextView(ctx);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        view.setTypeface(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL);
        return view;
    }

    static TextView label(Context ctx, CharSequence value) {
        TextView view = text(ctx, value, 11, ACCENT, true);
        view.setLetterSpacing(0.12f);
        view.setPadding(dp(ctx, 4), dp(ctx, 20), 0, dp(ctx, 8));
        return view;
    }

    static TextView title(Context ctx, CharSequence value) {
        return text(ctx, value, 22, TEXT, true);
    }

    static TextView body(Context ctx, CharSequence value) {
        TextView view = text(ctx, value, 12, MUTED, false);
        view.setLineSpacing(0, 1.3f);
        return view;
    }

    static TextView mono(Context ctx, CharSequence value) {
        TextView view = text(ctx, value, 12, TEXT, false);
        view.setTypeface(Typeface.MONOSPACE);
        view.setLineSpacing(0, 1.2f);
        return view;
    }

    /* ---------------------------- containers ------------------------------ */

    static LinearLayout column(Context ctx, int padding) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(padding, padding, padding, padding);
        return box;
    }

    static LinearLayout row(Context ctx) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        return box;
    }

    static ScrollView scroll(Context ctx, View content) {
        ScrollView scroll = new ScrollView(ctx);
        scroll.setBackgroundColor(BG);
        scroll.setFillViewport(true);
        scroll.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    static LinearLayout card(Context ctx) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(ctx, 14), dp(ctx, 14), dp(ctx, 14), dp(ctx, 14));
        box.setBackground(rounded(CARD, dp(ctx, 16), LINE, 1));
        return box;
    }

    static GradientDrawable rounded(int fill, int radiusPx, int strokeColor, int strokePx) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(fill);
        bg.setCornerRadius(radiusPx);
        if (strokePx > 0) bg.setStroke(strokePx, strokeColor);
        return bg;
    }

    /* ----------------------------- controls ------------------------------- */

    static TextView button(Context ctx, CharSequence label, int textColor, int fill, int stroke) {
        TextView view = text(ctx, label, 14, textColor, true);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(ctx, 16), dp(ctx, 12), dp(ctx, 16), dp(ctx, 12));
        view.setBackground(rounded(fill, dp(ctx, 12), stroke, stroke == 0 ? 0 : 1));
        view.setClickable(true);
        view.setFocusable(true);
        return view;
    }

    static TextView primary(Context ctx, CharSequence label) {
        return button(ctx, label, ACCENT_INK, ACCENT, 0);
    }

    static TextView secondary(Context ctx, CharSequence label) {
        return button(ctx, label, ACCENT, ACCENT_SOFT, ACCENT_LINE);
    }

    static TextView danger(Context ctx, CharSequence label) {
        return button(ctx, label, 0xFF2B0B0B, DANGER, 0);
    }

    static TextView quiet(Context ctx, CharSequence label) {
        return button(ctx, label, TEXT, CARD, LINE);
    }

    static TextView chip(Context ctx, CharSequence label, boolean active) {
        TextView view = button(ctx, label,
                active ? ACCENT_INK : MUTED,
                active ? ACCENT : CARD,
                active ? 0 : LINE);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        view.setPadding(dp(ctx, 12), dp(ctx, 8), dp(ctx, 12), dp(ctx, 8));
        return view;
    }

    static EditText input(Context ctx, String hint, String value) {
        EditText view = new EditText(ctx);
        view.setHint(hint);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        view.setTextColor(TEXT);
        view.setHintTextColor(MUTED);
        view.setInputType(InputType.TYPE_CLASS_TEXT);
        view.setBackground(rounded(0xFF0E1626, dp(ctx, 12), LINE, 1));
        view.setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10));
        return view;
    }

    static ProgressBar progress(Context ctx) {
        ProgressBar bar = new ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        bar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(LINE));
        bar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 6)));
        return bar;
    }

    static View spacer(Context ctx, int dp) {
        View view = new View(ctx);
        view.setLayoutParams(new LinearLayout.LayoutParams(1, dp(ctx, dp)));
        return view;
    }

    static View divider(Context ctx) {
        View view = new View(ctx);
        view.setBackgroundColor(LINE);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1);
        params.topMargin = dp(ctx, 12);
        params.bottomMargin = dp(ctx, 12);
        view.setLayoutParams(params);
        return view;
    }

    static LinearLayout.LayoutParams margins(int width, int height, int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height);
        params.setMargins(left, top, right, bottom);
        return params;
    }

    static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    static LinearLayout.LayoutParams fill() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    static int dp(Context ctx, int value) {
        return Math.round(value * ctx.getResources().getDisplayMetrics().density);
    }

    /** Colors used by the canvas screens. */
    static int alpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    static int blend(int a, int b, float t) {
        int ar = Color.red(a), ag = Color.green(a), ab = Color.blue(a);
        int br = Color.red(b), bg = Color.green(b), bb = Color.blue(b);
        return Color.rgb(
                Math.round(ar + (br - ar) * t),
                Math.round(ag + (bg - ag) * t),
                Math.round(ab + (bb - ab) * t));
    }
}
