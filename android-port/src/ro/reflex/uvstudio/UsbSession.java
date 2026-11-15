package ro.reflex.uvstudio;

import android.content.Context;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.util.Base64;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * One CDC-ACM (virtual COM port) session with the radio.
 *
 * The firmware enumerates as a USB CDC device (App/usb/usbd_cdc_if.c, VID 0x36B7
 * PID 0xFFFF).  Android exposes no serial API, so this class drives the bulk
 * endpoints directly and emulates what a terminal program would do: set the
 * line coding, assert DTR/RTS, then move raw bytes in both directions.
 *
 * Receive bytes are buffered here and handed to RadioLink, which turns them
 * into complete frames.  Nothing in this class interprets the protocol: byte
 * framing and commands belong to RadioLink and the screens.
 */
final class UsbSession {
    private static final String TAG = "UVUsb";

    static final int VID = 0x36B7;
    static final int PID = 0xFFFF;

    private static final int RX_LIMIT = 512 * 1024;

    private final UsbManager manager;
    private final ByteArrayOutputStream rx = new ByteArrayOutputStream();

    private UsbDeviceConnection conn;
    private UsbEndpoint epIn;
    private UsbEndpoint epOut;
    private UsbInterface dataIface;
    private UsbInterface commIface;
    private Thread pumpThread;
    private volatile boolean running;

    UsbSession(Context context) {
        this.manager = (UsbManager) context.getApplicationContext()
                .getSystemService(Context.USB_SERVICE);
    }

    UsbManager manager() {
        return manager;
    }

    /** Human readable device label for the UI and the console. */
    static String name(UsbDevice device) {
        String label = null;
        try {
            label = device.getProductName();
        } catch (Exception ignored) { }
        if (label == null || label.isEmpty()) {
            label = String.format("USB %04x:%04x", device.getVendorId(), device.getProductId());
        }
        return label;
    }

    /** Any CDC-ACM (virtual COM port) device, whatever its VID/PID is. */
    static boolean looksLikeSerial(UsbDevice device) {
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            int cls = device.getInterface(i).getInterfaceClass();
            if (cls == UsbConstants.USB_CLASS_CDC_DATA || cls == UsbConstants.USB_CLASS_COMM) {
                return true;
            }
        }
        return false;
    }

    /** One-line description of the USB interfaces, for the diagnostic screen. */
    static String interfaces(UsbDevice device) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface iface = device.getInterface(i);
            if (i > 0) out.append(", ");
            out.append("if").append(iface.getId())
                    .append(" class=0x").append(Integer.toHexString(iface.getInterfaceClass()))
                    .append(" ep=").append(iface.getEndpointCount());
        }
        return out.length() == 0 ? "no interfaces" : out.toString();
    }

    /** Attached devices, radio first. */
    List<UsbDevice> devices() {
        List<UsbDevice> out = new ArrayList<>();
        if (manager == null) return out;
        for (UsbDevice d : manager.getDeviceList().values()) out.add(d);
        out.sort((a, b) -> rank(b) - rank(a));
        return out;
    }

    private static int rank(UsbDevice d) {
        if (d.getVendorId() == VID) return 3;
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            int c = d.getInterface(i).getInterfaceClass();
            if (c == UsbConstants.USB_CLASS_CDC_DATA) return 2;
            if (c == UsbConstants.USB_CLASS_COMM) return 1;
        }
        return 0;
    }

    List<UsbDevice> matching(int vid, int pid) {
        List<UsbDevice> out = new ArrayList<>();
        for (UsbDevice d : devices()) {
            if (vid != 0 && d.getVendorId() != vid) continue;
            if (pid != 0 && d.getProductId() != pid) continue;
            out.add(d);
        }
        return out;
    }

    UsbDevice find(int vid, int pid) {
        List<UsbDevice> list = matching(vid, pid);
        return list.isEmpty() ? null : list.get(0);
    }

    boolean attached(int vid, int pid) {
        return manager != null && find(vid, pid) != null;
    }

    boolean isOpen() {
        return conn != null;
    }

    synchronized boolean open(int vid, int pid, int baud) {
        close();

        UsbDevice device = find(vid, pid);
        if (device == null) {
            Log.w(TAG, "open: device " + vid + ":" + pid + " not attached");
            return false;
        }
        if (!manager.hasPermission(device)) {
            Log.w(TAG, "open: no USB permission");
            return false;
        }

        UsbInterface data = null, comm = null;
        UsbEndpoint in = null, out = null;
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface iface = device.getInterface(i);
            if (iface.getInterfaceClass() == UsbConstants.USB_CLASS_CDC_DATA) data = iface;
            if (iface.getInterfaceClass() == UsbConstants.USB_CLASS_COMM) comm = iface;
            for (int j = 0; j < iface.getEndpointCount(); j++) {
                UsbEndpoint ep = iface.getEndpoint(j);
                if (ep.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) continue;
                if (ep.getDirection() == UsbConstants.USB_DIR_IN) in = ep;
                else out = ep;
            }
        }
        if (in == null || out == null) {
            Log.w(TAG, "open: no bulk endpoints");
            return false;
        }

        UsbDeviceConnection connection = manager.openDevice(device);
        if (connection == null) {
            Log.w(TAG, "open: openDevice returned null");
            return false;
        }

        boolean claimed = true;
        if (comm != null) claimed &= connection.claimInterface(comm, true);
        if (data != null) claimed &= connection.claimInterface(data, true);
        if (!claimed) {
            Log.w(TAG, "open: claimInterface failed");
            try { connection.close(); } catch (Exception ignored) { }
            return false;
        }

        conn = connection;
        epIn = in;
        epOut = out;
        dataIface = data;
        commIface = comm;

        synchronized (rx) { rx.reset(); }
        setLineCoding(baud);

        running = true;
        pumpThread = new Thread(this::pump, "uv-usb-rx");
        pumpThread.setDaemon(true);
        pumpThread.start();

        Log.i(TAG, "open: " + device.getDeviceName() + " @" + baud);
        return true;
    }

    /** baud is cosmetic for the CDC firmware, but the host still has to ask. */
    private void setLineCoding(int baud) {
        byte[] coding = new byte[] {
                (byte) (baud & 0xFF), (byte) ((baud >> 8) & 0xFF),
                (byte) ((baud >> 16) & 0xFF), (byte) ((baud >> 24) & 0xFF),
                0 /* one stop bit */, 0 /* no parity */, 8 /* data bits */
        };
        int index = commIface != null ? commIface.getId() : 0;
        try {
            conn.controlTransfer(0x21, 0x20, 0, index, coding, coding.length, 500);
            conn.controlTransfer(0x21, 0x22, 0x0003, index, null, 0, 500);
        } catch (Exception e) {
            Log.w(TAG, "setLineCoding: " + e);
        }
    }

    private void pump() {
        byte[] buffer = new byte[8192];
        while (running) {
            UsbDeviceConnection c = conn;
            UsbEndpoint ep = epIn;
            if (c == null || ep == null) break;
            int n;
            try {
                n = c.bulkTransfer(ep, buffer, buffer.length, 200);
            } catch (Exception e) {
                if (!running) break;
                Log.w(TAG, "bulk read: " + e);
                break;
            }
            if (n > 0) {
                synchronized (rx) {
                    if (rx.size() < RX_LIMIT) rx.write(buffer, 0, n);
                    rx.notifyAll();
                }
            } else if (n < 0 && !running) {
                break;
            }
        }
    }

    synchronized String readBase64(int max) {
        byte[] part;
        /* The pump thread appends under the same lock, so the remainder must be
         * put back here or the byte order would change under load. */
        synchronized (rx) {
            int size = rx.size();
            if (size == 0) return "";
            byte[] all = rx.toByteArray();
            rx.reset();
            int take = Math.min(size, max);
            part = new byte[take];
            System.arraycopy(all, 0, part, 0, take);
            if (size > take) rx.write(all, take, size - take);
        }
        return Base64.encodeToString(part, Base64.NO_WRAP);
    }

    /**
     * Copy up to {@code len} buffered bytes, waiting at most {@code timeoutMs}
     * for the first one.  Returns 0 on timeout or when the port is closed.
     */
    int read(byte[] dst, int off, int len, int timeoutMs) throws InterruptedException {
        synchronized (rx) {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (rx.size() == 0 && running && conn != null) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) break;
                rx.wait(Math.min(left, 100));
            }
            byte[] all = rx.toByteArray();
            int n = Math.min(len, all.length);
            System.arraycopy(all, 0, dst, off, n);
            rx.reset();
            if (all.length > n) rx.write(all, n, all.length - n);
            return n;
        }
    }

    synchronized int write(byte[] data) {
        UsbDeviceConnection c = conn;
        UsbEndpoint ep = epOut;
        if (c == null || ep == null) return -1;
        int offset = 0;
        try {
            while (offset < data.length) {
                int chunk = Math.min(4096, data.length - offset);
                int n = c.bulkTransfer(ep, data, offset, chunk, 2000);
                if (n < 0) return -1;
                offset += n;
            }
            int packet = ep.getMaxPacketSize();
            if (packet > 0 && data.length > 0 && data.length % packet == 0) {
                c.bulkTransfer(ep, new byte[0], 0, 0, 100);
            }
        } catch (Exception e) {
            Log.w(TAG, "bulk write: " + e);
            return -1;
        }
        return offset;
    }

    synchronized void close() {
        running = false;

        Thread t = pumpThread;
        pumpThread = null;
        if (t != null) {
            try { t.join(600); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }

        UsbDeviceConnection c = conn;
        conn = null;
        epIn = null;
        epOut = null;
        if (c != null) {
            try {
                if (commIface != null) c.releaseInterface(commIface);
                if (dataIface != null) c.releaseInterface(dataIface);
            } catch (Exception ignored) { }
            try { c.close(); } catch (Exception ignored) { }
        }
        commIface = null;
        dataIface = null;
        synchronized (rx) {
            rx.reset();
            rx.notifyAll();
        }
    }

    void detach(UsbDevice device) {
        UsbDevice current = find(device.getVendorId(), device.getProductId());
        if (current == null) close();
    }
}
