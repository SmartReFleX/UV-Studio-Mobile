package ro.reflex.uvstudio;

import android.hardware.usb.UsbDevice;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Diagnostic screen: exactly what Android reports on the USB bus.
 *
 * When the app says "no radio", this is the place to look: an empty list means
 * the phone does not see any device at all (cable / OTG / port), a list without
 * a serial device means the radio is not enumerating as CDC-ACM.
 */
final class UsbDevicesScreen {

    private final MainActivity app;
    private LinearLayout listBox;
    private TextView summary;

    UsbDevicesScreen(MainActivity app) {
        this.app = app;
    }

    View create() {
        LinearLayout column = Ui.column(app, 0);
        column.setPadding(Ui.dp(app, 16), Ui.dp(app, 14), Ui.dp(app, 16), Ui.dp(app, 26));

        column.addView(Ui.title(app, "USB devices"));
        summary = Ui.body(app, "Reading the USB bus…");
        summary.setPadding(0, Ui.dp(app, 4), 0, Ui.dp(app, 6));
        column.addView(summary);

        TextView refresh = Ui.primary(app, "Refresh");
        refresh.setOnClickListener(v -> refresh());
        column.addView(refresh, Ui.matchWrap());

        listBox = new LinearLayout(app);
        listBox.setOrientation(LinearLayout.VERTICAL);
        column.addView(listBox, Ui.matchWrap());

        ScrollView scroll = new ScrollView(app);
        scroll.setBackgroundColor(Ui.BG);
        scroll.setFillViewport(true);
        scroll.addView(column, Ui.matchWrap());

        refresh();
        return scroll;
    }

    private void refresh() {
        app.refreshStatus();
        listBox.removeAllViews();

        java.util.List<UsbDevice> devices = app.usb.devices();
        if (devices.isEmpty()) {
            summary.setTextColor(Ui.WARN);
            summary.setText("Android sees no USB device at all. This is not an app problem: "
                    + "the phone is not getting a data connection.\n\n"
                    + "• use a data cable (test it with a PC);\n"
                    + "• on Samsung no OTG switch exists, but the port must not be in a "
                    + "charge-only adapter;\n"
                    + "• try a USB-C→USB-A OTG adapter with the radio's own cable;\n"
                    + "• plug the radio in while it is switched on.");
            return;
        }

        summary.setTextColor(Ui.MUTED);
        summary.setText(devices.size() + " USB device(s) enumerated by Android. "
                + "The radio must appear as a serial (CDC-ACM) device.");

        for (UsbDevice device : devices) {
            listBox.addView(row(device));
        }
    }

    private View row(UsbDevice device) {
        LinearLayout card = Ui.card(app);
        LinearLayout.LayoutParams params = Ui.matchWrap();
        params.topMargin = Ui.dp(app, 10);
        card.setLayoutParams(params);

        boolean known = device.getVendorId() == UsbSession.VID
                && device.getProductId() == UsbSession.PID;
        boolean serial = UsbSession.looksLikeSerial(device);
        boolean permitted = app.usb.manager() != null && app.usb.manager().hasPermission(device);

        TextView title = Ui.text(app, UsbSession.name(device), 15, Ui.TEXT, true);
        card.addView(title);

        String tag = known ? "  · UV radio" : serial ? "  · serial" : "  · not serial";
        TextView ids = Ui.text(app, String.format("USB %04x:%04x%s", device.getVendorId(),
                device.getProductId(), tag), 12, serial ? Ui.ACCENT : Ui.MUTED, false);
        ids.setPadding(0, Ui.dp(app, 3), 0, Ui.dp(app, 3));
        card.addView(ids);

        TextView detail = Ui.mono(app, UsbSession.interfaces(device));
        detail.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10);
        detail.setTextColor(Ui.MUTED);
        card.addView(detail);

        LinearLayout actions = Ui.row(app);
        actions.setPadding(0, Ui.dp(app, 8), 0, 0);
        card.addView(actions);

        TextView state = Ui.text(app, permitted ? "permission granted" : "no permission",
                11, permitted ? Ui.OK : Ui.WARN, true);
        actions.addView(state, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (!permitted) {
            TextView ask = Ui.secondary(app, "Request access");
            ask.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11);
            ask.setOnClickListener(v -> app.requestDeviceAccess(device, ok -> refresh()));
            actions.addView(ask);
        } else if (serial) {
            TextView open = Ui.secondary(app, "Open");
            open.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11);
            open.setOnClickListener(v -> app.connectToDevice(device, ok -> {
                app.toast(ok ? "Port opened" : "Could not open the port");
                refresh();
            }));
            actions.addView(open);
        }
        return card;
    }
}
