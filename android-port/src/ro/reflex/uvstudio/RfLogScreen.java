package ro.reflex.uvstudio;

import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;

/**
 * RF log: the firmware streams the log on the AA 55 channel while it receives
 * the feature keepalive (55 AA 05 03). Rows are 25 bytes each; the screen shows
 * them live and exports exactly the CSV the web client produces.
 */
final class RfLogScreen {

    private static final int ROW_SIZE = 25;
    private static final int ROW_COUNT = 64;
    private static final int STATUS_SIZE = 4;
    private static final int MAIN_SIZE = STATUS_SIZE + ROW_SIZE * (ROW_COUNT + 1);
    private static final int HISTORY_SIZE = ROW_SIZE * ROW_COUNT;
    private static final int CHANNEL_NONE = 0xFFFF;
    private static final int BATT_UNKNOWN = 0xFF;
    private static final int BATT_OFFSET = 600;
    private static final int VISIBLE_TRAFFIC = 512;
    /* The radio exposes four power levels: USER, LOW, MID, HIGH. The firmware
     * still reports an 8-slot table, so the intermediate slots map to LOW. */
    private static final String[] POWER_LABELS =
            {"USER", "LOW", "LOW", "LOW", "LOW", "LOW", "MID", "HIGH"};

    private final MainActivity app;
    private final RadioLink link;
    private final TreeMap<Long, Row> rows = new TreeMap<>();
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());

    private TextView summary;
    private RfLogTable table;
    private TextView state;
    private volatile boolean active;
    private Thread keepalive;

    RfLogScreen(MainActivity app, RadioLink link) {
        this.app = app;
        this.link = link;
    }

    View create() {
        LinearLayout root = new LinearLayout(app);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Ui.dp(app, 16), Ui.dp(app, 12), Ui.dp(app, 16), Ui.dp(app, 12));
        root.setBackgroundColor(Ui.BG);

        summary = Ui.title(app, "RF log");
        root.addView(summary);

        state = Ui.body(app, "Connect the radio to stream the RF log. It must be enabled "
                + "and supported by the firmware.");
        state.setPadding(0, Ui.dp(app, 4), 0, Ui.dp(app, 10));
        root.addView(state);

        LinearLayout actions = Ui.row(app);
        root.addView(actions);

        TextView clear = Ui.quiet(app, "Clear");
        clear.setOnClickListener(v -> {
            rows.clear();
            render();
        });
        actions.addView(clear, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView export = Ui.primary(app, "Export CSV");
        export.setOnClickListener(v -> export());
        LinearLayout.LayoutParams exportParams = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        exportParams.leftMargin = Ui.dp(app, 8);
        actions.addView(export, exportParams);

        android.widget.ScrollView scroll = new android.widget.ScrollView(app);
        table = new RfLogTable(app);
        scroll.addView(table);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        scrollParams.topMargin = Ui.dp(app, 10);
        root.addView(scroll, scrollParams);

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

    /* --------------------------- streaming ------------------------------- */

    private void start() {
        active = true;
        link.setListener(new RadioLink.Listener() {
            @Override
            public void onPacket(RadioLink l, int type, byte[] data) { }

            @Override
            public void onFrame(RadioLink l, int type, int flags, byte[] payload) {
                if (type == UvFrames.TYPE_RF_LOG && (payload.length == MAIN_SIZE || payload.length == STATUS_SIZE)) {
                    parseMain(payload);
                } else if (type == UvFrames.TYPE_RF_LOG_HISTORY && payload.length == HISTORY_SIZE) {
                    parseHistory(payload);
                }
            }

            @Override
            public void onClosed(String reason) {
                handler.post(() -> state.setText("Radio disconnected."));
            }
        });

        app.connect(ok -> {
            if (!ok) {
                state.setText("No radio connected.");
                return;
            }
            state.setText("Streaming… waiting for the first RF log packet.");
        });

        keepalive = new Thread(() -> {
            boolean first = true;
            while (active) {
                if (link.isOpen()) {
                    link.sendRaw(UvFrames.KEEPALIVE);
                    link.sendRaw(UvFrames.featureKeepalive(first));
                    first = false;
                }
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "rf-keepalive");
        keepalive.setDaemon(true);
        keepalive.start();
    }

    private void stop() {
        active = false;
        link.setListener(null);
        Thread t = keepalive;
        keepalive = null;
        if (t != null) t.interrupt();
    }

    /* ---------------------------- parsing -------------------------------- */

    private static final class Row {
        long sequence;
        long frequency;
        int duration;
        int channel;
        int flags;
        int meter;
        int batt;

        String name = "";

        boolean session() {
            return (flags & 0x08) != 0;
        }

        boolean tx() {
            return (flags & 0x01) != 0;
        }
    }

    private static Row parseRow(byte[] data, int offset) {
        Row row = new Row();
        row.frequency = UvProtocol.u32(data, offset) & 0xFFFFFFFFL;
        row.sequence = UvProtocol.u32(data, offset + 4) & 0xFFFFFFFFL;
        row.duration = UvProtocol.u16(data, offset + 8);
        row.channel = UvProtocol.u16(data, offset + 10);
        row.flags = data[offset + 12] & 0xFF;
        row.meter = data[offset + 13] & 0xFF;
        row.batt = data[offset + 14] & 0xFF;
        row.name = Radio.ascii(data, offset + 15, 10);
        return row;
    }

    private static boolean stored(Row row) {
        return row.frequency > 0 || row.session();
    }

    private void parseMain(byte[] payload) {
        if (payload.length == STATUS_SIZE) {
            int flags = payload[1] & 0xFF;
            handler.post(() -> {
                if ((flags & 0x08) != 0) state.setText("RF log is disabled in the firmware settings.");
                else if ((flags & 0x01) != 0) state.setText("Recording · streaming live.");
                else state.setText("RF log idle (no traffic yet).");
            });
            return;
        }
        if ((payload[0] & 0xFF) != 2) return;

        List<Row> found = new ArrayList<>();
        Row live = parseRow(payload, STATUS_SIZE);
        if (live.frequency > 0) found.add(live);

        int count = Math.min(payload[2] & 0xFF, ROW_COUNT);
        int offset = STATUS_SIZE + ROW_SIZE;
        for (int i = 0; i < count; i++, offset += ROW_SIZE) {
            Row row = parseRow(payload, offset);
            if (stored(row)) found.add(row);
        }
        merge(found);
    }

    private void parseHistory(byte[] payload) {
        List<Row> found = new ArrayList<>();
        for (int i = 0, offset = 0; i < ROW_COUNT; i++, offset += ROW_SIZE) {
            Row row = parseRow(payload, offset);
            if (stored(row)) found.add(row);
        }
        merge(found);
    }

    private void merge(List<Row> found) {
        if (found.isEmpty()) return;
        handler.post(() -> {
            for (Row row : found) rows.put(row.sequence, row);
            trim();
            render();
        });
    }

    private void trim() {
        List<Row> traffic = new ArrayList<>();
        for (Row row : rows.values()) if (!row.session()) traffic.add(row);
        if (traffic.size() <= VISIBLE_TRAFFIC) return;
        long oldest = traffic.get(traffic.size() - VISIBLE_TRAFFIC).sequence;
        rows.entrySet().removeIf(entry -> {
            Row row = entry.getValue();
            return row.session() ? row.sequence <= oldest : row.sequence < oldest;
        });
    }

    /* ---------------------------- rendering ------------------------------ */

    private void render() {
        List<Row> all = new ArrayList<>(rows.values());
        all.sort(Comparator.comparingLong(r -> r.sequence));

        int traffic = 0;
        long airtime = 0;
        java.util.HashSet<Long> frequencies = new java.util.HashSet<>();
        for (Row row : all) {
            if (row.session()) continue;
            traffic++;
            airtime += row.duration;
            frequencies.add(row.frequency);
        }
        summary.setText("RF log · " + traffic + " events");
        state.setText(String.format(Locale.US, "Airtime %02d:%02d · %d distinct frequencies",
                airtime / 60, airtime % 60, frequencies.size()));

        List<String[]> data = new ArrayList<>();
        int from = Math.max(0, all.size() - 200);
        for (int i = from; i < all.size(); i++) {
            Row row = all.get(i);
            if (row.session()) {
                data.add(new String[] {String.valueOf(row.sequence), "PWR", "power on", "", "", "", ""});
                continue;
            }
            String name = row.name == null ? "" : row.name;
            data.add(new String[] {
                    String.valueOf(row.sequence),
                    name,
                    String.format(Locale.US, "%.5f", row.frequency / 100000.0),
                    row.tx() ? "TX" : "RX",
                    String.valueOf(row.duration),
                    meter(row),
                    row.batt == BATT_UNKNOWN ? "—"
                            : String.format(Locale.US, "%.2f", (BATT_OFFSET + row.batt) / 100.0)
            });
        }
        table.setData(data);
    }

    private static String meter(Row row) {
        if (row.meter == 0xFF) return "";
        if (row.tx()) return row.meter < POWER_LABELS.length ? POWER_LABELS[row.meter] : "P" + row.meter;
        int value = Math.max(1, row.meter);
        return value > 9 ? String.format(Locale.US, "S9+%02d", value - 9) : "S" + value;
    }

    /* ------------------------------ CSV ---------------------------------- */

    private void export() {
        List<Row> all = new ArrayList<>(rows.values());
        all.sort(Comparator.comparingLong(r -> r.sequence));
        if (all.isEmpty()) {
            app.toast("No RF log rows to export.");
            return;
        }

        StringBuilder csv = new StringBuilder();
        csv.append('\uFEFF');
        csv.append("sequence,event,frequency_mhz,frequency_10hz,duration_seconds,channel,channel_name,signal_or_power,battery_volts\r\n");
        for (Row row : all) {
            boolean session = row.session();
            csv.append(csvCell(String.valueOf(row.sequence))).append(',');
            csv.append(session ? "POWER_ON" : (row.tx() ? "TX" : "RX")).append(',');
            csv.append(session || row.frequency == 0 ? ""
                    : String.format(Locale.US, "%.5f", row.frequency / 100000.0)).append(',');
            csv.append(session ? "" : String.valueOf(row.frequency)).append(',');
            csv.append(session ? "" : String.valueOf(row.duration)).append(',');
            csv.append(session || row.channel == CHANNEL_NONE ? "" : String.valueOf(row.channel + 1)).append(',');
            csv.append(session ? "" : csvCell(row.name)).append(',');
            csv.append(session ? "" : meter(row)).append(',');
            csv.append(session || row.batt == BATT_UNKNOWN ? ""
                    : String.format(Locale.US, "%.2f", (BATT_OFFSET + row.batt) / 100.0)).append("\r\n");
        }
        app.saveFile("rf-log.csv", csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String csvCell(String value) {
        String text = value == null ? "" : value;
        if (!text.isEmpty() && "=+-@\t\r".indexOf(text.charAt(0)) >= 0) text = "'" + text;
        if (text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r")) {
            text = "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }
}
