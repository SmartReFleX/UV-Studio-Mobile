package ro.reflex.uvstudio;

import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Shared scaffolding for the tool screens: a scrolling column, a progress bar,
 * a status line, a small console and the "radio required" dance.
 *
 * Subclasses only describe their controls and the actual radio task.
 */
abstract class ToolScreen {

    protected final MainActivity app;
    protected final RadioLink link;
    protected final LinearLayout column;

    private ProgressBar bar;
    private TextView status;
    private TextView console;
    private final StringBuilder transcript = new StringBuilder();
    private boolean busy;
    private Worker.Progress current;
    private ScrollView scroll;

    protected ToolScreen(MainActivity app, RadioLink link, String title, String subtitle) {
        this.app = app;
        this.link = link;
        this.column = Ui.column(app, 0);
        this.column.setPadding(Ui.dp(app, 16), Ui.dp(app, 14), Ui.dp(app, 16), Ui.dp(app, 26));

        TextView heading = Ui.title(app, title);
        column.addView(heading);
        TextView sub = Ui.body(app, subtitle);
        sub.setPadding(0, Ui.dp(app, 4), 0, Ui.dp(app, 6));
        column.addView(sub);
    }

    /** Returns the view to push on the navigation stack. */
    public View create() {
        /* Progress first: during a flash or an install the bar must be visible
         * without scrolling past the help text. Screens that want it elsewhere
         * override progressAtTop() and call addProgressBlock() themselves. */
        if (progressAtTop()) addProgressBlock();
        build();
        if (bar == null) addProgressBlock();
        scroll = new ScrollView(app);
        scroll.setBackgroundColor(Ui.BG);
        scroll.setFillViewport(true);
        scroll.addView(column, Ui.matchWrap());
        return scroll;
    }

    /** False when the progress block goes under the screen's controls. */
    protected boolean progressAtTop() {
        return false;
    }

    /** Builds the tool-specific controls into {@link #column}. */
    protected abstract void build();

    /* ----------------------------- widgets ------------------------------- */

    protected LinearLayout section(String label) {
        column.addView(Ui.label(app, label));
        LinearLayout card = Ui.card(app);
        column.addView(card);
        return card;
    }

    protected LinearLayout plain() {
        LinearLayout card = Ui.card(app);
        column.addView(card);
        return card;
    }

    protected TextView note(String text) {
        TextView view = Ui.body(app, text);
        view.setPadding(0, Ui.dp(app, 8), 0, Ui.dp(app, 8));
        column.addView(view);
        return view;
    }

    protected TextView button(String label, int style, View.OnClickListener listener) {
        TextView view;
        switch (style) {
            case 1: view = Ui.primary(app, label); break;
            case 2: view = Ui.danger(app, label); break;
            case 3: view = Ui.quiet(app, label); break;
            default: view = Ui.secondary(app, label); break;
        }
        view.setOnClickListener(listener);
        column.addView(view, Ui.matchWrap());
        return view;
    }

    protected TextView rowButton(LinearLayout row, String label, int style, View.OnClickListener listener) {
        TextView view;
        switch (style) {
            case 1: view = Ui.primary(app, label); break;
            case 2: view = Ui.danger(app, label); break;
            case 3: view = Ui.quiet(app, label); break;
            default: view = Ui.secondary(app, label); break;
        }
        view.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        params.rightMargin = Ui.dp(app, 8);
        row.addView(view, params);
        return view;
    }

    protected LinearLayout actionRow() {
        LinearLayout row = Ui.row(app);
        row.setPadding(0, Ui.dp(app, 6), 0, 0);
        column.addView(row);
        return row;
    }

    /* ----------------------------- progress ------------------------------ */

    protected void addProgressBlock() {
        if (bar != null) return;
        column.addView(Ui.spacer(app, 16));
        bar = Ui.progress(app);
        bar.setVisibility(View.GONE);
        column.addView(bar);

        status = Ui.text(app, "", 12, Ui.MUTED, false);
        status.setPadding(0, Ui.dp(app, 8), 0, 0);
        status.setVisibility(View.GONE);
        column.addView(status);

        console = Ui.text(app, "", 11, Ui.MUTED, false);
        console.setTypeface(android.graphics.Typeface.MONOSPACE);
        console.setLineSpacing(0, 1.15f);
        console.setPadding(Ui.dp(app, 10), Ui.dp(app, 10), Ui.dp(app, 10), Ui.dp(app, 10));
        console.setBackground(Ui.rounded(0xFF0E1626, Ui.dp(app, 10), Ui.LINE, 1));
        console.setVisibility(View.GONE);
        LinearLayout.LayoutParams params = Ui.matchWrap();
        params.topMargin = Ui.dp(app, 12);
        column.addView(console, params);
    }

    protected void setProgress(int percent, String message) {
        if (bar == null) return;
        int clamped = Math.max(0, Math.min(100, percent));
        bar.setVisibility(View.VISIBLE);
        bar.setProgress(clamped * 10);
        status.setVisibility(View.VISIBLE);
        String text = message == null ? "" : message;
        status.setText(text + "  ·  " + clamped + "%");
        status.setTextColor(Ui.MUTED);
    }

    protected void setStatus(String message, int color) {
        if (status == null) return;
        status.setVisibility(View.VISIBLE);
        status.setText(message);
        status.setTextColor(color);
    }

    protected void finishProgress(String message, int color) {
        if (bar != null) bar.setVisibility(View.GONE);
        setStatus(message, color);
    }

    protected void log(String line) {
        transcript.append(line).append('\n');
        if (transcript.length() > 12000) {
            transcript.delete(0, transcript.length() - 9000);
        }
        if (console != null) {
            console.setVisibility(View.VISIBLE);
            console.setText(transcript.toString().trim());
        }
    }

    protected void clearLog() {
        transcript.setLength(0);
        if (console != null) console.setText("");
    }

    /* ------------------------------ helpers ------------------------------ */

    protected boolean isBusy() {
        return busy;
    }

    /** Connects if needed, then runs the task on a worker thread. */
    protected <T> void withRadio(final String name, final Worker.Task<T> task,
                                 final Worker.Callback<T> done) {
        if (busy) {
            app.toast("Please wait for the current operation.");
            return;
        }
        busy = true;
        setProgress(0, "Connecting…");
        app.connect(ok -> {
            if (!ok) {
                busy = false;
                finishProgress("Radio not available. Check the OTG cable and USB permission.", Ui.WARN);
                done.done(null, new IllegalStateException("radio not connected"));
                return;
            }
            startWorker(name, task, done);
        });
    }

    /** Runs a task that does not need the radio (file conversion, save). */
    protected <T> void runLocal(final String name, final Worker.Task<T> task,
                                final Worker.Callback<T> done) {
        startWorker(name, task, done);
    }

    private <T> void startWorker(String name, Worker.Task<T> task, Worker.Callback<T> done) {
        setProgress(0, "Working…");
        if (scroll != null && bar != null) {
            scroll.post(() -> scroll.smoothScrollTo(0, Math.max(0, bar.getTop() - Ui.dp(app, 8))));
        }
        final Worker.Progress[] holder = new Worker.Progress[1];
        Worker.Callback<T> wrapped = (value, error) -> {
            busy = false;
            current = null;
            if (error != null) {
                finishProgress("Failed: " + error.getMessage(), Ui.DANGER);
                log("! " + error.getMessage());
            }
            done.done(value, error);
        };
        holder[0] = Worker.run(name, task, wrapped);
        current = holder[0];
        holder[0].attach(this::setProgress);
    }

    /** Asks the running radio task to stop at the next chunk boundary. */
    protected void cancel() {
        Worker.Progress progress = current;
        if (progress != null) progress.cancel();
    }

    protected void requireRadioThen(final Runnable action) {
        withRadio("probe", progress -> true, (value, error) -> {
            if (error == null) action.run();
        });
    }

    protected String hex(byte[] data, int max) {
        return UvProtocol.hex(data, max);
    }

    protected int dp(int value) {
        return Ui.dp(app, value);
    }
}
