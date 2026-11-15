package ro.reflex.uvstudio;

import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Reads 0x0514 and probes the optional feature families. */
final class DeviceInfoScreen extends ToolScreen {

    private TextView output;

    DeviceInfoScreen(MainActivity app, RadioLink link) {
        super(app, link, "Device info",
                "Firmware string, calibration layout and which optional services this radio answers.");
    }

    @Override
    protected void build() {
        LinearLayout card = plain();
        output = Ui.mono(app, "Not read yet.");
        output.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
        card.addView(output);

        button("Read from radio", 1, v -> read());
    }

    private void read() {
        withRadio("device-info", progress -> {
            progress.publish(5, "Opening session…");
            Radio.sleep(500);
            progress.publish(20, "Asking for device info…");
            String firmware = app.radio.deviceInfo(5000);

            progress.publish(45, "Probing external flash…");
            boolean externalFlash = app.radio.hasExternalFlash();

            progress.publish(70, "Probing overlay apps…");
            boolean apps;
            try {
                app.radio.appInfo(0, 1000);
                apps = true;
            } catch (Exception e) {
                apps = false;
            }

            progress.publish(100, "Done");
            StringBuilder out = new StringBuilder();
            out.append("Firmware   : ").append(firmware.isEmpty() ? "—" : firmware).append('\n');
            out.append("Calibration: 0x")
                    .append(Integer.toHexString(Radio.calibrationOffset(firmware)).toUpperCase())
                    .append(" (").append(Radio.calibrationOffset(firmware) >= 0xB000 ? "external flash" : "MCU EEPROM")
                    .append(")\n");
            out.append("Ext. flash : ").append(externalFlash ? "supported" : "not answered").append('\n');
            out.append("Apps       : ").append(apps ? "supported" : "not answered").append('\n');
            out.append("Timestamp  : 0x")
                    .append(Integer.toHexString(app.radio.timestamp()).toUpperCase()).append('\n');
            return out.toString();
        }, (value, error) -> {
            if (error != null) {
                output.setText("Error: " + error.getMessage());
                return;
            }
            output.setText(value);
            finishProgress("Device info read", Ui.OK);
        });
    }
}
