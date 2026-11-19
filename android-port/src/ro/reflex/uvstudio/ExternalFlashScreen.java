package ro.reflex.uvstudio;

import android.app.AlertDialog;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Raw 2 MiB SPI flash: physical addresses, 128-byte reads, sector erase and
 * 128-byte writes. The 4 KiB calibration sector (0x010000) is always skipped.
 */
final class ExternalFlashScreen extends ToolScreen {

    private TextView info;

    ExternalFlashScreen(MainActivity app, RadioLink link) {
        super(app, link, "External flash",
                "Full backup and restore of the 2 MiB SPI flash. Reading takes a few "
                + "minutes over USB; restoring is slow and rewrites everything except "
                + "the calibration sector at 0x010000.");
    }

    @Override
    protected void build() {
        LinearLayout card = plain();
        info = Ui.body(app, "No backup yet.");
        card.addView(info);

        LinearLayout row = Ui.row(app);
        row.setPadding(0, dp(10), 0, 0);
        card.addView(row);
        rowButton(row, "Dump 2 MiB", 1, v -> dump());
        rowButton(row, "Restore…", 2, v -> restore());

        button("Cancel running operation", 3, v -> {
            cancel();
            setStatus("Cancelling…", Ui.WARN);
        });
    }

    private void dump() {
        withRadio("flash-dump", progress -> {
            progress.publish(1, "Opening session…");
            Radio.sleep(500);
            String firmware = app.radio.deviceInfo(1500);
            if (!app.radio.hasExternalFlash()) {
                throw new Exception(externalFlashProblem(firmware));
            }
            byte[] image = app.radio.dumpExternalFlash(progress);
            progress.publish(99, "Saving 2 MiB…");
            app.saveFile("external-flash.bin", image);
            return image.length;
        }, (value, error) -> {
            if (error != null) return;
            info.setTextColor(Ui.MUTED);
            info.setText(value + " bytes read and saved as external-flash.bin.");
            finishProgress("Backup complete", Ui.OK);
        });
    }

    private void restore() {
        app.pickFile("application/octet-stream", new String[] {"application/octet-stream", "application/binary", "*/*"},
                (name, bytes) -> {
                    if (bytes == null) return;
                    if (bytes.length != Radio.FLASH_TOTAL) {
                        info.setTextColor(Ui.DANGER);
                        info.setText("Invalid file: " + bytes.length + " bytes, expected exactly 2097152.");
                        return;
                    }
                    new AlertDialog.Builder(app)
                            .setTitle("Restore external flash?")
                            .setMessage("This erases and rewrites 511 of the 512 sectors of the radio's "
                                    + "external flash. The calibration sector is preserved, but a "
                                    + "wrong image can leave the radio unbootable. Do not unplug the cable.")
                            .setNegativeButton("Cancel", null)
                            .setPositiveButton("Restore", (dialog, which) -> write(bytes))
                            .show();
                });
    }

    private void write(byte[] image) {
        withRadio("flash-restore", progress -> {
            progress.publish(1, "Opening session…");
            Radio.sleep(500);
            String firmware = app.radio.deviceInfo(1500);
            if (!app.radio.hasExternalFlash()) {
                throw new Exception(externalFlashProblem(firmware));
            }
            app.radio.restoreExternalFlash(image, progress, false);
            app.radio.reboot();
            return true;
        }, (value, error) -> {
            if (error != null) return;
            info.setTextColor(Ui.MUTED);
            info.setText("External flash restored and verified. The radio restarted.");
            finishProgress("Restore complete", Ui.OK);
        });
    }

    /**
     * The external-flash commands are only compiled into the Service edition of
     * the F4HWN firmware: 7.0.x ships Fusion, Service and All builds, and Fusion
     * has no AirCopy, no BEAM and no external-flash operations.
     */
    private static String externalFlashProblem(String firmware) {
        String name = firmware == null ? "" : firmware.trim();
        boolean v7 = name.matches("(?i).*v7(\\.[0-9]+)*.*");
        String edition = v7 ? "Service" : "Labs";
        return "External flash needs the " + edition + " edition of the firmware; this radio answers: "
                + (name.isEmpty() ? "unknown firmware" : name);
    }
}
