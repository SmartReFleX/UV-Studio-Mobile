package ro.reflex.uvstudio;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * RF log table drawn on a Canvas.
 *
 * A TextView with padded monospace text aligns only when the typeface is really
 * monospaced; on phones whose system font falls back to a proportional face the
 * columns drift.  Here every cell is measured and centred inside a fixed pixel
 * column, and the separators are real dashed vertical lines.
 */
final class RfLogTable extends View {

    private static final float[] WEIGHTS = {0.09f, 0.20f, 0.24f, 0.08f, 0.09f, 0.15f, 0.15f};
    private static final int COLUMNS = WEIGHTS.length;

    private final Paint cellPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint headPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final float density;
    private final float rowHeight;
    private final float gap;
    private final float pad;

    private String[] header = {"SEQ", "NAME", "MHZ", "DIR", "TIME", "PWR", "BATT"};
    private final List<String[]> rows = new ArrayList<>();

    RfLogTable(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        rowHeight = 18f * density;
        gap = 6f * density;
        pad = 8f * density;

        cellPaint.setColor(Ui.TEXT);
        cellPaint.setTextSize(12f * density);
        cellPaint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));

        headPaint.set(cellPaint);
        headPaint.setColor(Ui.MUTED);
        headPaint.setTextSize(11f * density);

        linePaint.setColor(Ui.alpha(Ui.MUTED, 150));
        linePaint.setStrokeWidth(Math.max(1f, density));
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setPathEffect(new DashPathEffect(new float[] {2f * density, 3f * density}, 0));

        setBackground(Ui.rounded(0xFF0E1626, Ui.dp(context, 10), Ui.LINE, 1));
    }

    void setData(List<String[]> data) {
        rows.clear();
        if (data != null) rows.addAll(data);
        requestLayout();
        invalidate();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        if (width <= 0) width = Ui.dp(getContext(), 320);
        int height = (int) (pad * 2 + rowHeight * (rows.size() + 1));
        setMeasuredDimension(width, Math.max(1, height));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float left = pad;
        float right = getWidth() - pad;
        float usable = right - left - gap * (COLUMNS - 1);
        if (usable <= 0) return;

        float[] xs = new float[COLUMNS];
        float[] ws = new float[COLUMNS];
        float cursor = left;
        for (int i = 0; i < COLUMNS; i++) {
            xs[i] = cursor;
            ws[i] = usable * WEIGHTS[i];
            cursor += ws[i] + gap;
        }

        float y = pad + rowHeight * 0.72f;
        drawRow(canvas, header, xs, ws, y, headPaint);

        for (String[] row : rows) {
            y += rowHeight;
            drawRow(canvas, row, xs, ws, y, cellPaint);
        }

        float top = pad;
        float bottom = pad + rowHeight * (rows.size() + 1);
        for (int i = 1; i < COLUMNS; i++) {
            float x = xs[i] - gap / 2f;
            canvas.drawLine(x, top, x, bottom, linePaint);
        }
    }

    private void drawRow(Canvas canvas, String[] cells, float[] xs, float[] ws,
                         float baseline, Paint paint) {
        for (int i = 0; i < COLUMNS && i < cells.length; i++) {
            String text = cells[i] == null ? "" : cells[i];
            float size = paint.getTextSize();
            float width = paint.measureText(text);
            float max = ws[i] - 2f * density;
            if (width > max && width > 0) {
                paint.setTextSize(size * (max / width));
            }
            float drawWidth = paint.measureText(text);
            float x = xs[i] + (ws[i] - drawWidth) / 2f;
            canvas.drawText(text, x, baseline, paint);
            paint.setTextSize(size);
        }
    }
}
