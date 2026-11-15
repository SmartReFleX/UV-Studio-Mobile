package ro.reflex.uvstudio;

import android.app.AlertDialog;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Calibration backup and restore: 512 bytes at 0xB000 (v5+) or 0x1E00,
 * transferred 16 bytes at a time through the EEPROM-compatible commands.
 */
final class CalibrationScreen extends ToolScreen {

    private TextView info;

    CalibrationScreen(MainActivity app, RadioLink link) {
        super(app, link, "Calibration",
                "Device-specific RF calibration. Back it up before you change anything, "
                + "and keep the file safe: a wrong restore cannot be undone.");
    }

    @Override
    protected void build() {
        LinearLayout card = plain();
        info = Ui.body(app, "No operation yet. “Back up” reads the radio and saves calibration.dat.");
        card.addView(info);

        LinearLayout row = Ui.row(app);
        row.setPadding(0, Ui.dp(app, 10), 0, 0);
        card.addView(row);
        rowButton(row, "Back up", 1, v -> dump());
        rowButton(row, "Restore…", 0, v -> restore());
    }

    private void dump() {
        withRadio("calibration-dump", progress -> {
            progress.publish(2, "Opening session…");
            Radio.sleep(500);
            String firmware = app.radio.deviceInfo(1500);
            byte[] data = app.radio.readCalibration(firmware, progress);
            progress.publish(100, "Saving…");
            app.saveFile("calibration.dat", data);
            return "512 bytes · offset 0x"
                    + Integer.toHexString(Radio.calibrationOffset(firmware)).toUpperCase();
        }, (value, error) -> {
            if (error != null) return;
            info.setTextColor(Ui.MUTED);
            info.setText("Calibration read and saved as calibration.dat (" + value + ").");
            finishProgress("Backup saved", Ui.OK);
        });
    }

    private void restore() {
        app.pickFile("*/*", null, (name, bytes) -> {
            if (bytes == null) return;
            if (bytes.length != Radio.CALIB_SIZE) {
                info.setTextColor(Ui.DANGER);
                info.setText("Invalid file: " + bytes.length + " bytes, expected exactly 512.");
                return;
            }
            new AlertDialog.Builder(app)
                    .setTitle("Restore calibration?")
                    .setMessage("This overwrites the RF calibration of the radio with " + name
                            + ". There is no undo and no read-back verification.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Restore", (dialog, which) -> write(bytes))
                    .show();
        });
    }

    private void write(byte[] data) {
        withRadio("calibration-restore", progress -> {
            progress.publish(2, "Opening session…");
            Radio.sleep(500);
            app.radio.writeCalibration(data, progress);
            return true;
        }, (value, error) -> {
            if (error != null) return;
            info.setTextColor(Ui.MUTED);
            info.setText("Calibration restored. The radio restarted.");
            finishProgress("Restore complete", Ui.OK);
        });
    }
}
