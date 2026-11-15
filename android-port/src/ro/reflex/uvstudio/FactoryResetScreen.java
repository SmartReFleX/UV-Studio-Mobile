package ro.reflex.uvstudio;

import android.app.AlertDialog;
import android.hardware.usb.UsbDevice;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.List;

/**
 * Factory reset (Labs), in two explicit steps:
 *
 *   1. download + verify the reconstructed external flash and the stock
 *      firmware, then restore the external flash (calibration preserved);
 *   2. ask the user to enter DFU mode and flash the stock image.
 *
 * The split matters: the radio must be power-cycled into bootloader mode
 * between the two, exactly like the web client's "waiting for DFU" modal.
 */
final class FactoryResetScreen extends ToolScreen {

    private static final String BASE = "https://armel.github.io/uvstudio/assets/factory/";

    private static final class Target {
        final String label;
        final String flashFile;
        final String flashSha;
        final String firmwareFile;
        final String firmwareSha;

        Target(String label, String flashFile, String flashSha, String firmwareFile, String firmwareSha) {
            this.label = label;
            this.flashFile = flashFile;
            this.flashSha = flashSha;
            this.firmwareFile = firmwareFile;
            this.firmwareSha = firmwareSha;
        }
    }

    private static final Target K1 = new Target(
            "UV-K1",
            "K1_External_Flash_Factory_Reconstructed.bin",
            "a2383aa050dc0963fee7b7c99692b8330d9455a6bbb2bb7498b2bba4174c9d55",
            "quansheng.k1.stock.firmware.v7.03.01.bin",
            "55ec0daffc5668bdb41dcc118475d7e6e23ad953d70f57a9bca64a6202734ba0");

    private static final Target K5V3 = new Target(
            "UV-K5 V3",
            "K5V3_External_Flash_Factory_Reconstructed.bin",
            "b85c8066a1b0885d1cf3e430533f45c9c8ea91c8b936a94aba99221fd1bff79b",
            "quansheng.k5v3.stock.firmware.v7.00.11.bin",
            "f4e5264a6f9a5436a6c75f7b217c60ba002cec04928f247392b9968c5c9458cb");

    private TextView status;
    private byte[] stockFirmware;
    private String stockLabel = "";

    FactoryResetScreen(MainActivity app, RadioLink link) {
        super(app, link, "Factory reset",
                "Restores the reconstructed factory external flash and the stock firmware "
                + "matching the radio. Device-specific calibration is preserved.");
    }

    @Override
    protected void build() {
        LinearLayout card = plain();
        status = Ui.body(app, "Pick the model that matches your radio. Both files are downloaded "
                + "from the UV Studio site and verified with SHA-256 before anything is erased.");
        card.addView(status);

        LinearLayout row = Ui.row(app);
        row.setPadding(0, dp(10), 0, 0);
        card.addView(row);
        rowButton(row, "Restore UV-K1", 2, v -> confirm(K1));
        rowButton(row, "Restore UV-K5 V3", 2, v -> confirm(K5V3));

        button("Flash stock firmware (DFU step)", 0, v -> {
            if (stockFirmware == null) {
                app.toast("Restore the external flash first.");
                return;
            }
            stepDfu();
        });
    }

    private void confirm(Target target) {
        new AlertDialog.Builder(app)
                .setTitle("Factory reset " + target.label + "?")
                .setMessage("Everything except the calibration sector is erased and rewritten "
                        + "with the factory image. The stock firmware is flashed afterwards, "
                        + "in bootloader mode.\n\nDo not unplug the cable during the operation.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Start", (dialog, which) -> start(target))
                .show();
    }

    private void start(Target target) {
        if (isBusy()) {
            app.toast("Please wait for the current operation.");
            return;
        }
        setProgress(0, "Reading the radio…");
        clearLog();

        List<UsbDevice> devices = app.usb.devices();
        requestNext(devices, 0, () -> runLocal("factory-restore", progress -> {
            progress.publish(1, "Opening session…");
            Radio.sleep(1000);
            app.radio.deviceInfo(5000);
            if (!app.radio.hasExternalFlash()) {
                throw new Exception("This firmware does not support the external flash commands.");
            }

            progress.publish(3, "Downloading the factory external flash…");
            byte[] external = download(BASE + target.flashFile, target.flashSha, progress, 3, 18);
            progress.publish(18, "Downloading the stock firmware…");
            byte[] firmware = download(BASE + target.firmwareFile, target.firmwareSha, progress, 18, 30);

            progress.publish(30, "Restoring the external flash (calibration preserved)…");
            app.radio.restoreExternalFlash(external, progress, true);
            return firmware;
        }, (firmware, error) -> {
            if (error != null) return;
            stockFirmware = firmware;
            stockLabel = target.label;
            finishProgress("External flash restored. Next: the stock firmware in DFU mode.", Ui.WARN);
            askDfu();
        }));
    }

    private void askDfu() {
        new AlertDialog.Builder(app)
                .setTitle("Enter bootloader mode")
                .setMessage("1. Unplug the radio.\n"
                        + "2. Hold PTT and switch it on — the screen stays dark.\n"
                        + "3. Plug the USB-C cable.\n\n"
                        + "Then tap Continue to flash the stock firmware.")
                .setNegativeButton("Later", null)
                .setPositiveButton("Continue", (dialog, which) -> stepDfu())
                .show();
    }

    private void stepDfu() {
        if (stockFirmware == null) {
            app.toast("Nothing to flash.");
            return;
        }
        if (isBusy()) {
            app.toast("Please wait for the current operation.");
            return;
        }
        List<UsbDevice> devices = app.usb.devices();
        requestNext(devices, 0, () -> runLocal("factory-dfu", progress -> {
            progress.publish(5, "Looking for the bootloader…");
            Radio.Bootloader boot = findBootloader(progress);
            progress.publish(10, "Bootloader " + boot.version + " · handshake");
            app.radio.bootloaderHandshake(boot.version);
            progress.publish(15, "Flashing " + stockLabel + " stock firmware…");
            app.radio.programImage(stockFirmware, progress);
            return true;
        }, (value, error) -> {
            if (error != null) return;
            stockFirmware = null;
            finishProgress("Factory reset complete (" + stockLabel + "). You can unplug the radio.", Ui.OK);
        }));
    }

    private void requestNext(List<UsbDevice> devices, int index, Runnable done) {
        if (index >= devices.size()) {
            done.run();
            return;
        }
        app.requestDeviceAccess(devices.get(index), ok -> requestNext(devices, index + 1, done));
    }

    private Radio.Bootloader findBootloader(Worker.Progress progress) throws Exception {
        for (UsbDevice device : app.usb.devices()) {
            if (app.usb.manager() == null || !app.usb.manager().hasPermission(device)) continue;
            if (!app.openDevice(device)) continue;
            try {
                return app.radio.waitForBootloader(progress);
            } catch (Exception ignored) { }
        }
        throw new Exception("No bootloader. Put the radio in DFU mode (hold PTT, power on) and retry.");
    }

    /* --------------------------- download -------------------------------- */

    private static byte[] download(String url, String expectedSha, Worker.Progress progress,
                                   int from, int to) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(60000);
        connection.setRequestProperty("Accept-Encoding", "identity");
        try {
            if (connection.getResponseCode() != 200) {
                throw new Exception("Download failed: HTTP " + connection.getResponseCode());
            }
            int total = connection.getContentLength();
            ByteArrayOutputStream out = new ByteArrayOutputStream(total > 0 ? total : 1 << 20);
            try (InputStream in = connection.getInputStream()) {
                byte[] buffer = new byte[16384];
                int read;
                int done = 0;
                while ((read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                    done += read;
                    if (total > 0 && progress != null) {
                        progress.publish(from + (to - from) * done / total,
                                "Downloading " + done / 1024 + " / " + total / 1024 + " KB");
                    }
                }
            }
            byte[] bytes = out.toByteArray();
            String sha = sha256(bytes);
            if (expectedSha != null && !expectedSha.equalsIgnoreCase(sha)) {
                throw new Exception("SHA-256 mismatch — refusing to use the download");
            }
            return bytes;
        } finally {
            connection.disconnect();
        }
    }

    private static String sha256(byte[] data) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(data);
        StringBuilder out = new StringBuilder(hash.length * 2);
        for (byte b : hash) out.append(String.format("%02x", b & 0xFF));
        return out.toString();
    }
}
