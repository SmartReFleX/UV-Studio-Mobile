package ro.reflex.uvstudio;

import android.app.AlertDialog;
import android.hardware.usb.UsbDevice;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * Firmware flashing, natively.
 *
 * Two devices are involved: the bootloader (only in DFU mode, identified by its
 * 0x0518 notices, VID/PID not assumed) and the running firmware
 * (0x36B7:0xFFFF), which comes back after the reboot. The image is programmed
 * 256 bytes at a time; assets are written afterwards when the .bin carries an
 * FWA1 pack.
 */
final class FlashScreen extends ToolScreen {

    private TextView fileLabel;
    private FirmwarePackage pack;

    FlashScreen(MainActivity app, RadioLink link) {
        super(app, link, "Flash firmware",
                "The radio must be in bootloader (DFU) mode: hold PTT while powering on, "
                + "then plug the USB-C cable. Do not unplug anything until the end.");
    }

    @Override
    protected void build() {
        LinearLayout card = plain();
        fileLabel = Ui.body(app, "No firmware file selected.");
        card.addView(fileLabel);

        LinearLayout row = Ui.row(app);
        row.setPadding(0, dp(10), 0, 0);
        card.addView(row);
        rowButton(row, "Choose .bin…", 0, v -> choose());
        rowButton(row, "Flash", 1, v -> confirmFlash());

        LinearLayout catalogRow = Ui.row(app);
        catalogRow.setPadding(0, dp(8), 0, 0);
        card.addView(catalogRow);
        rowButton(catalogRow, "Firmware catalog…", 3, v -> openCatalog());

        /* Progress under the buttons, before the explanatory block. */
        addProgressBlock();

        LinearLayout help = plain();
        help.addView(Ui.label(app, "BOOTLOADER MODE"));
        help.addView(Ui.body(app,
                "1. Unplug the radio.\n"
                + "2. Hold PTT and switch the radio on — the screen stays dark.\n"
                + "3. Plug the USB-C cable.\n"
                + "4. Tap Flash. Android may ask for USB permission once more.\n\n"
                + "The image is written page by page (256 bytes). When the bootloader is "
                + "done it restarts by itself; the app then waits up to two minutes for the "
                + "running firmware and writes the assets if the package has them."));
    }

    private void choose() {
        app.pickFile("application/octet-stream", new String[] {"application/octet-stream", "*/*"},
                (name, bytes) -> {
                    if (bytes == null) return;
                    pack = FirmwarePackage.parse(bytes);
                    if (pack.error != null) {
                        fileLabel.setTextColor(Ui.DANGER);
                        fileLabel.setText(pack.error);
                        return;
                    }
                    fileLabel.setTextColor(Ui.MUTED);
                    fileLabel.setText(name + " · image " + pack.image.length + " bytes"
                            + (pack.assets != null
                            ? " · assets " + pack.assets.length + " bytes (" + pack.buildId() + ")"
                            : " · no asset pack"));
                });
    }

    /* ------------------------------ catalog ------------------------------- */

    private void openCatalog() {
        runLocal("catalog", progress -> Catalog.firmware(), (value, error) -> {
            if (error != null) {
                app.toast("Catalog: " + error.getMessage());
                return;
            }
            if (value.isEmpty()) {
                app.toast("No firmware in the catalog.");
                return;
            }
            String[] labels = new String[value.size()];
            for (int i = 0; i < value.size(); i++) labels[i] = value.get(i).label();
            new AlertDialog.Builder(app)
                    .setTitle("Firmware catalog")
                    .setItems(labels, (dialog, which) -> downloadFirmware(value.get(which)))
                    .show();
        });
    }

    private void downloadFirmware(Catalog.Entry entry) {
        runLocal("catalog-download", progress -> Catalog.download(entry.url, progress, 0, 100),
                (bytes, error) -> {
                    if (error != null) {
                        app.toast("Download failed: " + error.getMessage());
                        return;
                    }
                    FirmwarePackage parsed = FirmwarePackage.parse(bytes);
                    if (parsed.error != null) {
                        fileLabel.setTextColor(Ui.DANGER);
                        fileLabel.setText(parsed.error);
                        return;
                    }
                    pack = parsed;
                    fileLabel.setTextColor(Ui.MUTED);
                    fileLabel.setText(entry.name + " · " + entry.label()
                            + (parsed.assets != null ? " · assets " + parsed.assets.length + " B" : ""));
                    finishProgress("Firmware downloaded", Ui.OK);
                });
    }

    private void confirmFlash() {
        if (pack == null) {
            app.toast("Choose a firmware file first.");
            return;
        }
        String extra = pack.assets != null ? "\n\nThe package also carries firmware assets, "
                + "which are written after the radio restarts." : "";
        new AlertDialog.Builder(app)
                .setTitle("Flash this firmware?")
                .setMessage("The application region of the radio is overwritten. Keep the cable "
                        + "plugged in. A half-written image means re-flashing from the start."
                        + extra)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Flash", (dialog, which) -> startFlash())
                .show();
    }

    private void startFlash() {
        if (isBusy()) {
            app.toast("Please wait for the current operation.");
            return;
        }
        setProgress(0, "Checking USB devices…");
        clearLog();

        /* Permissions must be granted on the UI thread, one dialog at a time. */
        List<UsbDevice> devices = app.usb.devices();
        requestNext(devices, 0, () -> {
            final FirmwarePackage firmware = pack;
            runLocal("flash-firmware", progress -> {
                progress.publish(1, "Looking for the bootloader…");
                Radio.Bootloader boot = findBootloader(progress);
                progress.publish(5, "Bootloader " + boot.version + " · handshake");
                app.radio.bootloaderHandshake(boot.version);

                progress.publish(8, "Writing the image…");
                app.radio.programImage(firmware.image, progress);

                progress.publish(96, "Waiting for the radio to restart…");
                waitForFirmware(120000, progress);

                if (firmware.assets != null) {
                    progress.publish(97, "Writing firmware assets…");
                    writeAssets(firmware, progress);
                }
                return true;
            }, (value, error) -> {
                if (error != null) {
                    finishProgress("Flashing failed: " + error.getMessage(), Ui.DANGER);
                    return;
                }
                finishProgress("Firmware installed. You can unplug the radio.", Ui.OK);
            });
        });
    }

    private void requestNext(List<UsbDevice> devices, int index, Runnable done) {
        if (index >= devices.size()) {
            done.run();
            return;
        }
        app.requestDeviceAccess(devices.get(index), ok -> requestNext(devices, index + 1, done));
    }

    /* --------------------------- bootloader ------------------------------- */

    private Radio.Bootloader findBootloader(Worker.Progress progress) throws Exception {
        List<UsbDevice> devices = app.usb.devices();
        for (UsbDevice device : devices) {
            if (app.usb.manager() == null || !app.usb.manager().hasPermission(device)) continue;
            progress.publish(2, "Probing " + UsbSession.name(device));
            if (!app.openDevice(device)) continue;
            try {
                return app.radio.waitForBootloader(progress);
            } catch (Exception ignored) {
                /* not this one */
            }
        }
        throw new Exception("No bootloader. Hold PTT, power the radio on, then plug the cable.");
    }

    private void waitForFirmware(long timeoutMs, Worker.Progress progress) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        int silent = 0;
        boolean askedPermission = false;

        while (System.currentTimeMillis() < deadline) {
            if (!app.usb.attached(UsbSession.VID, UsbSession.PID)) {
                app.link.close();
                Thread.sleep(500);
                continue;
            }
            UsbDevice device = app.usb.find(UsbSession.VID, UsbSession.PID);
            if (device == null) {
                Thread.sleep(500);
                continue;
            }
            if (app.usb.manager() == null || !app.usb.manager().hasPermission(device)) {
                if (!askedPermission) {
                    askedPermission = true;
                    final UsbDevice target = device;
                    app.runOnUi(() -> app.requestDeviceAccess(target, ok -> { }));
                }
                Thread.sleep(700);
                continue;
            }

            askedPermission = false;
            if (!app.link.isOpen()) app.openDevice(device);
            if (app.radio.probe(1000)) {
                progress.publish(99, "Radio is back: " + app.radio.deviceInfo(1000));
                return;
            }

            if (++silent >= 5) {
                silent = 0;
                app.link.close();
                Thread.sleep(300);
                app.openDevice(device);
            }
            Thread.sleep(500);
        }
        throw new Exception("The radio did not come back after flashing. Unplug and replug it.");
    }

    private void writeAssets(FirmwarePackage firmware, Worker.Progress progress) throws Exception {
        Radio.AssetInfo info = app.radio.assetInfo(Radio.ASSET_RUNNING_SLOT, 3000);
        String buildId = firmware.buildId();
        if (!buildId.isEmpty() && !info.runningBuildId.isEmpty() && !buildId.equals(info.runningBuildId)) {
            throw new Exception("The running firmware does not match the asset pack ("
                    + buildId + " vs " + info.runningBuildId + ")");
        }
        app.radio.writeAssetPack(Radio.ASSET_RUNNING_SLOT, firmware.assets, progress);
    }
}
