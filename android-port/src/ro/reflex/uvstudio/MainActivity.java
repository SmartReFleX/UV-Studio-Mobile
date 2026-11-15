package ro.reflex.uvstudio;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.List;

/**
 * Native UV Studio for Android.
 *
 * No WebView: every tool is an Android screen that speaks the radio's own
 * protocol over USB OTG (see RadioLink / UvProtocol).  This activity is the
 * shell: home screen, navigation stack, USB permission, file pick/save and the
 * shared connection to the radio.
 */
public class MainActivity extends Activity {

    private static final String TAG = "UVStudio";
    private static final String ACTION_USB_PERMISSION = "ro.reflex.uvstudio.USB_PERMISSION";

    private static final int REQ_FILE = 1001;
    private static final int REQ_SAVE = 1002;

    /** The firmware's CDC device runs at 38400 8N1; the value is mostly cosmetic. */
    static final int BAUD = 38400;

    interface Ready {
        void onReady(boolean ok);
    }

    interface FileCallback {
        void onFile(String name, byte[] bytes);
    }

    private static final class Entry {
        final View view;
        final String title;

        Entry(View view, String title) {
            this.view = view;
            this.title = title;
        }
    }

    UsbSession usb;
    RadioLink link;
    Radio radio;

    private FrameLayout root;
    private View home;
    private TextView statusDot;
    private TextView statusText;
    private TextView statusHint;

    private final ArrayDeque<Entry> stack = new ArrayDeque<>();

    private Ready pendingConnect;
    private FileCallback pendingFile;
    private byte[] pendingSave;
    private String pendingSaveName;
    private UsbDevice pendingDevice;
    private BroadcastReceiver receiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        usb = new UsbSession(this);
        link = new RadioLink(usb);
        radio = new Radio(link);

        root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);
        setContentView(root);

        home = buildHome();
        stack.push(new Entry(home, ""));
        root.addView(home, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        registerUsbReceiver();
    }

    /* ===================================================================== */
    /*  Home                                                                */
    /* ===================================================================== */

    private View buildHome() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.BG);
        scroll.setFillViewport(true);

        LinearLayout column = Ui.column(this, Ui.dp(this, 18));
        column.setPadding(Ui.dp(this, 18), Ui.dp(this, 22), Ui.dp(this, 18), Ui.dp(this, 28));
        scroll.addView(column, Ui.matchWrap());

        TextView logo = Ui.text(this, "UV STUDIO MOBILE", 24, Ui.ACCENT, true);
        logo.setLetterSpacing(0.06f);
        column.addView(logo);

        TextView sub = Ui.text(this, "UV-K5 / UV-K1 · native USB OTG", 12, Ui.MUTED, false);
        sub.setPadding(0, Ui.dp(this, 3), 0, Ui.dp(this, 16));
        column.addView(sub);

        column.addView(buildStatusCard());

        column.addView(Ui.label(this, "TOOLS"));
        column.addView(card("ic_live", "Live view", "Radio screen and keypad, in real time", "live"));
        column.addView(card("ic_flash", "Flash firmware", "Write .bin images, then the assets", "flash"));
        column.addView(card("ic_slots", "Firmware slots", "Multiboot: write, rename, delete slots", "firmware-slots"));
        column.addView(card("ic_apps", "Install applications", "Install app from web or local", "apps"));
        column.addView(card("ic_calibration", "Calibration", "Back up and restore calibration data", "calibration"));
        column.addView(card("ic_logo", "Boot logo", "Upload or dump the boot image", "boot-logo"));
        column.addView(card("ic_rflog", "RF log", "Spectrum and signal history", "rf-log"));
        column.addView(card("ic_extflash", "External flash", "Read or write the external flash", "external-flash"));

        column.addView(Ui.label(this, "STUDIO"));
        column.addView(card("ic_apps", "USB devices", "What Android actually sees on the cable", "usb-devices"));
        column.addView(card("ic_apps", "Device info", "Model, firmware, protocol version", "device-info"));
        column.addView(card("ic_info", "About", "How this app talks to the radio", "about"));

        TextView legal = Ui.text(this,
                "Radio work and app installation are only allowed on equipment you own. " +
                "Never point the analyser apps at other people's devices.", 11, Ui.MUTED, false);
        legal.setPadding(Ui.dp(this, 4), Ui.dp(this, 18), Ui.dp(this, 4), 0);
        legal.setLineSpacing(0, 1.25f);
        column.addView(legal);

        return scroll;
    }

    private View buildStatusCard() {
        LinearLayout box = Ui.row(this);
        box.setPadding(Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14));
        box.setBackground(Ui.rounded(Ui.CARD, Ui.dp(this, 16), Ui.LINE, 1));

        statusDot = Ui.text(this, "●", 16, Ui.MUTED, false);
        statusDot.setPadding(0, 0, Ui.dp(this, 10), 0);
        box.addView(statusDot);

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        statusText = Ui.text(this, "Looking for the radio…", 14, Ui.TEXT, true);
        labels.addView(statusText);
        statusHint = Ui.text(this, "Connect the radio with a USB-C OTG cable", 11, Ui.MUTED, false);
        labels.addView(statusHint);
        box.addView(labels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView button = Ui.secondary(this, "Connect");
        button.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
        button.setOnClickListener(v -> connect(ok ->
                toast(ok ? "Radio ready" : "Radio did not answer")));
        box.addView(button);
        return box;
    }

    private View card(String icon, String title, String subtitle, String route) {
        LinearLayout row = Ui.row(this);
        row.setPadding(Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12));
        row.setBackground(Ui.rounded(Ui.CARD, Ui.dp(this, 16), Ui.LINE, 1));

        LinearLayout.LayoutParams outer = Ui.matchWrap();
        outer.bottomMargin = Ui.dp(this, 9);
        row.setLayoutParams(outer);

        ImageView badge = new ImageView(this);
        badge.setScaleType(ImageView.ScaleType.FIT_CENTER);
        badge.setColorFilter(Ui.ACCENT);
        badge.setPadding(Ui.dp(this, 9), Ui.dp(this, 9), Ui.dp(this, 9), Ui.dp(this, 9));
        badge.setImageResource(iconRes(icon));
        badge.setBackground(Ui.rounded(Ui.ACCENT_SOFT, Ui.dp(this, 12), Ui.ACCENT_GLOW, 1));
        LinearLayout.LayoutParams tagParams = new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40));
        tagParams.rightMargin = Ui.dp(this, 12);
        row.addView(badge, tagParams);

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(Ui.text(this, title, 15, Ui.ACCENT, true));
        TextView s = Ui.text(this, subtitle, 11, Ui.MUTED, false);
        s.setPadding(0, Ui.dp(this, 2), 0, 0);
        labels.addView(s);
        row.addView(labels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView chevron = Ui.text(this, "›", 22, Ui.MUTED, false);
        chevron.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 2), 0);
        row.addView(chevron);

        row.setOnClickListener(v -> openTool(route));
        return row;
    }

    private int iconRes(String name) {
        int id = getResources().getIdentifier(name, "drawable", getPackageName());
        return id != 0 ? id : android.R.drawable.ic_menu_help;
    }

    /* ===================================================================== */
    /*  Navigation                                                          */
    /* ===================================================================== */

    private void openTool(String route) {
        View content;
        String title;
        switch (route) {
            case "live":
                title = "Live view";
                content = new LiveScreen(this, link).create();
                break;
            case "flash":
                title = "Flash firmware";
                content = new FlashScreen(this, link).create();
                break;
            case "firmware-slots":
                title = "Firmware slots";
                content = new SlotsScreen(this, link).create();
                break;
            case "apps":
                title = "Install applications";
                content = new AppsScreen(this, link).create();
                break;
            case "calibration":
                title = "Calibration";
                content = new CalibrationScreen(this, link).create();
                break;
            case "boot-logo":
                title = "Boot logo";
                content = new LogoScreen(this, link).create();
                break;
            case "rf-log":
                title = "RF log";
                content = new RfLogScreen(this, link).create();
                break;
            case "external-flash":
                title = "External flash";
                content = new ExternalFlashScreen(this, link).create();
                break;
            case "factory-reset":
                /* Hidden on request: the card is no longer shown on the home screen.
                 * The screen itself is kept in the sources, just not reachable. */
                title = "Factory reset";
                content = new FactoryResetScreen(this, link).create();
                break;
            case "usb-devices":
                title = "USB devices";
                content = new UsbDevicesScreen(this).create();
                break;
            case "device-info":
                title = "Device info";
                content = new DeviceInfoScreen(this, link).create();
                break;
            case "about":
            default:
                title = "About";
                content = new AboutScreen(this).create();
                break;
        }
        push(title, content);
    }

    void push(String title, View content) {
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setBackgroundColor(Ui.BG);
        shell.addView(buildToolbar(title), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 48)));
        shell.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        Entry entry = new Entry(shell, title);
        stack.push(entry);
        root.removeAllViews();
        root.addView(shell, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private View buildToolbar(String title) {
        LinearLayout bar = Ui.row(this);
        bar.setPadding(Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 6));
        bar.setBackgroundColor(Ui.CARD);

        TextView back = Ui.text(this, "‹", 26, Ui.TEXT, false);
        back.setGravity(Gravity.CENTER);
        back.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40)));
        back.setOnClickListener(v -> pop());
        bar.addView(back);

        TextView label = Ui.text(this, title, 14, Ui.TEXT, true);
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        bar.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return bar;
    }

    void pop() {
        if (stack.size() <= 1) return;
        stack.pop();
        Entry entry = stack.peek();
        root.removeAllViews();
        if (entry != null) {
            root.addView(entry.view, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        refreshStatus();
    }

    @Override
    public void onBackPressed() {
        if (stack.size() > 1) {
            pop();
            return;
        }
        super.onBackPressed();
    }

    /* ===================================================================== */
    /*  Connection                                                          */
    /* ===================================================================== */

    /** Opens the radio if needed; the callback always runs on the UI thread. */
    void connect(Ready done) {
        if (link.isOpen()) {
            if (done != null) done.onReady(true);
            return;
        }
        UsbDevice device = findRadio();
        if (device == null) {
            toast(usb.devices().isEmpty()
                    ? "Android sees no USB device. Data cable / OTG problem."
                    : "USB devices are attached, but none is a serial radio. Open “USB devices”.");
            if (done != null) done.onReady(false);
            return;
        }
        if (usb.manager() != null && usb.manager().hasPermission(device)) {
            openLink(device, done);
            return;
        }
        pendingConnect = done;
        askPermission(device);
    }

    /** The radio, by known VID/PID, or the first CDC-ACM device that shows up. */
    UsbDevice findRadio() {
        UsbDevice known = usb.find(UsbSession.VID, UsbSession.PID);
        if (known != null) return known;
        for (UsbDevice device : usb.devices()) {
            if (UsbSession.looksLikeSerial(device)) return device;
        }
        return null;
    }

    private void openLink(UsbDevice device, Ready done) {
        boolean ok = link.open(device.getVendorId(), device.getProductId(), BAUD);
        remember(device);
        refreshStatus();
        if (done != null) done.onReady(ok);
    }

    /** Opens any USB device (the bootloader enumerates as a different one). */
    boolean openDevice(UsbDevice device) {
        link.close();
        boolean ok = link.open(device.getVendorId(), device.getProductId(), BAUD);
        remember(device);
        refreshStatus();
        return ok;
    }

    /** Asks Android for permission on one specific device, without opening it. */
    void requestDeviceAccess(UsbDevice device, Ready done) {
        if (usb.manager() != null && usb.manager().hasPermission(device)) {
            if (done != null) done.onReady(true);
            return;
        }
        pendingConnect = done;
        askPermission(device);
    }

    /** Permission + open for a specific device (used for bootloader mode). */
    void connectToDevice(UsbDevice device, Ready done) {
        requestDeviceAccess(device, ok -> {
            boolean opened = false;
            if (ok) opened = openDevice(device);
            if (done != null) done.onReady(opened);
        });
    }

    private void askPermission(UsbDevice device) {
        if (usb.manager() == null) return;
        pendingDevice = device;
        Intent intent = new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName());
        int flags = Build.VERSION.SDK_INT >= 31
                ? android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_MUTABLE
                : android.app.PendingIntent.FLAG_UPDATE_CURRENT;
        android.app.PendingIntent pending =
                android.app.PendingIntent.getBroadcast(this, 0, intent, flags);
        usb.manager().requestPermission(device, pending);
    }

    private void registerUsbReceiver() {
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (action == null) return;
                switch (action) {
                    case ACTION_USB_PERMISSION: {
                        boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                        UsbDevice device = pendingDevice != null ? pendingDevice : deviceExtra(intent);
                        pendingDevice = null;
                        Ready ready = pendingConnect;
                        pendingConnect = null;
                        if (granted && device != null) {
                            remember(device);
                            if (device.getVendorId() == UsbSession.VID
                                    && device.getProductId() == UsbSession.PID) {
                                openLink(device, ready);
                            } else {
                                if (ready != null) ready.onReady(true);
                                refreshStatus();
                            }
                        } else {
                            if (ready != null) ready.onReady(false);
                            refreshStatus();
                        }
                        break;
                    }
                    case UsbManager.ACTION_USB_DEVICE_ATTACHED: {
                        UsbDevice device = deviceExtra(intent);
                        refreshStatus();
                        if (device != null) {
                            toast("USB attached: " + UsbSession.name(device)
                                    + String.format(" (%04x:%04x)", device.getVendorId(), device.getProductId()));
                            autoGrant(device);
                        }
                        break;
                    }
                    case UsbManager.ACTION_USB_DEVICE_DETACHED:
                        link.close();
                        refreshStatus();
                        break;
                }
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(receiver, filter);
        }
    }

    private static UsbDevice deviceExtra(Intent intent) {
        Parcelable p = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
        return p instanceof UsbDevice ? (UsbDevice) p : null;
    }

    private void autoGrant(UsbDevice device) {
        if (!remembered(device)) return;
        if (usb.manager() == null || usb.manager().hasPermission(device)) return;
        root.postDelayed(() -> {
            if (pendingDevice == null && !link.isOpen()) askPermission(device);
        }, 1200);
    }

    /** Devices we have already opened once; the firmware and the bootloader differ. */
    private boolean remembered(UsbDevice device) {
        String key = String.format("%04x:%04x", device.getVendorId(), device.getProductId());
        String all = getSharedPreferences("usb", MODE_PRIVATE).getString("devices", "");
        return ("," + all + ",").contains("," + key + ",");
    }

    private void remember(UsbDevice device) {
        String key = String.format("%04x:%04x", device.getVendorId(), device.getProductId());
        SharedPreferences prefs = getSharedPreferences("usb", MODE_PRIVATE);
        String all = prefs.getString("devices", "");
        if (("," + all + ",").contains("," + key + ",")) return;
        prefs.edit().putString("devices", all.isEmpty() ? key : all + "," + key).apply();
    }

    void refreshStatus() {
        if (statusText == null) return;
        UsbDevice radio = findRadio();
        if (radio != null && usb.manager() != null && usb.manager().hasPermission(radio)) {
            statusDot.setTextColor(Ui.ACCENT);
            statusText.setText(link.isOpen() ? "Radio connected" : "Radio ready · not open");
            statusHint.setText(String.format("%s · USB %04x:%04x · %d baud",
                    UsbSession.name(radio), radio.getVendorId(), radio.getProductId(), BAUD));
        } else if (radio != null) {
            statusDot.setTextColor(Ui.WARN);
            statusText.setText("Radio detected · approve access");
            statusHint.setText(String.format("%s · USB %04x:%04x · tap Connect",
                    UsbSession.name(radio), radio.getVendorId(), radio.getProductId()));
        } else {
            statusDot.setTextColor(Ui.MUTED);
            int count = usb.devices().size();
            statusText.setText("No radio detected");
            statusHint.setText(count == 0
                    ? "Android sees no USB device · data cable or OTG problem"
                    : count + " USB device(s) attached · open “USB devices”");
        }
        statusText.setTextColor(Ui.TEXT);
    }

    /* ===================================================================== */
    /*  Files                                                               */
    /* ===================================================================== */

    void pickFile(String mime, String[] extraMimes, FileCallback callback) {
        pendingFile = callback;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(mime);
        if (extraMimes != null && extraMimes.length > 0) {
            intent.putExtra(Intent.EXTRA_MIME_TYPES, extraMimes);
        }
        try {
            startActivityForResult(intent, REQ_FILE);
        } catch (Exception e) {
            pendingFile = null;
            toast("Could not open the file picker.");
        }
    }

    void saveFile(String name, byte[] bytes) {
        pendingSave = bytes;
        pendingSaveName = name;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/octet-stream");
        intent.putExtra(Intent.EXTRA_TITLE, name);
        try {
            startActivityForResult(intent, REQ_SAVE);
        } catch (Exception e) {
            pendingSave = null;
            toast("Could not open the save dialog.");
        }
    }

    private byte[] readUri(Uri uri) {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE) {
            Uri uri = (resultCode == RESULT_OK && data != null) ? data.getData() : null;
            FileCallback callback = pendingFile;
            pendingFile = null;
            if (callback == null) return;
            if (uri == null) {
                callback.onFile(null, null);
                return;
            }
            String name = uri.getLastPathSegment();
            if (name == null) name = "file";
            final String fileName = name;
            /* Firmware and 2 MiB flash images: never read them on the UI thread. */
            new Thread(() -> {
                byte[] bytes = readUri(uri);
                runOnUiThread(() -> callback.onFile(fileName, bytes));
            }, "uv-file-read").start();
            return;
        }
        if (requestCode == REQ_SAVE) {
            final Uri target = data != null ? data.getData() : null;
            final byte[] payload = pendingSave;
            final String name = pendingSaveName;
            pendingSave = null;
            pendingSaveName = null;
            if (resultCode != RESULT_OK || target == null || payload == null) return;
            new Thread(() -> {
                String error = null;
                try (OutputStream out = getContentResolver().openOutputStream(target)) {
                    if (out != null) out.write(payload);
                } catch (Exception e) {
                    error = e.getMessage();
                }
                final String failure = error;
                runOnUiThread(() -> {
                    if (failure == null) toast(name + " saved");
                    else toast("Save failed: " + failure);
                });
            }, "uv-file-save").start();
        }
    }

    /* ===================================================================== */
    /*  Misc                                                                */
    /* ===================================================================== */

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    @Override
    protected void onDestroy() {
        if (receiver != null) {
            try { unregisterReceiver(receiver); } catch (Exception ignored) { }
            receiver = null;
        }
        if (link != null) link.close();
        super.onDestroy();
    }

    void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    void runOnUi(Runnable task) {
        runOnUiThread(task);
    }

    String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "1.0";
        }
    }
}
