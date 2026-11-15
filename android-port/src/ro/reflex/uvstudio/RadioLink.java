package ro.reflex.uvstudio;

import android.os.SystemClock;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;

/**
 * Request/response engine on top of the raw USB session.
 *
 * The USB pump thread only moves bytes; this class owns the packet parser and
 * the "one request at a time" rule of the radio firmware:
 *
 *   request() -> create packet -> write -> wait for a matching reply type
 *
 * Unsolicited frames (live-view screen updates, RF events) are handed to the
 * listener as soon as they are parsed, so a request never starves the stream.
 */
final class RadioLink {
    private static final String TAG = "UVLink";

    interface Listener {
        /** Called on the reader thread for every packet, replies included. */
        void onPacket(RadioLink link, int type, byte[] data);

        /** Display/RF frame (AA 55 framing); not part of the command queue. */
        default void onFrame(RadioLink link, int type, int flags, byte[] payload) { }

        /** The reader thread stopped (cable unplugged, radio rebooted). */
        void onClosed(String reason);
    }

    private final UsbSession usb;
    private final ArrayDeque<UvProtocol.Packet> inbox = new ArrayDeque<>();
    private final ByteArrayOutputStream accumulator = new ByteArrayOutputStream();
    private final Object ioLock = new Object();

    private volatile Listener listener;
    private volatile boolean reading;
    private Thread reader;
    private volatile String lastError = "";
    private long lastRxAt;

    RadioLink(UsbSession usb) {
        this.usb = usb;
    }

    UsbSession usb() {
        return usb;
    }

    boolean isOpen() {
        return usb.isOpen();
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    String lastError() {
        return lastError;
    }

    /* ------------------------------------------------------------------ */
    /*  Session                                                            */
    /* ------------------------------------------------------------------ */

    boolean open(int vid, int pid, int baud) {
        close();
        if (!usb.open(vid, pid, baud)) {
            lastError = "USB open failed";
            return false;
        }

        synchronized (inbox) {
            inbox.clear();
        }
        accumulator.reset();
        reading = true;
        reader = new Thread(this::readLoop, "uv-packet-reader");
        reader.setDaemon(true);
        reader.start();
        return true;
    }

    void close() {
        reading = false;
        Thread t = reader;
        reader = null;
        if (t != null && t != Thread.currentThread()) {
            try { t.join(400); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
        usb.close();
        synchronized (inbox) {
            inbox.notifyAll();
        }
        accumulator.reset();
    }

    private void readLoop() {
        byte[] chunk = new byte[8192];
        while (reading) {
            int n;
            try {
                n = usb.read(chunk, 0, chunk.length, 300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                lastError = String.valueOf(e.getMessage());
                break;
            }
            if (!reading) break;
            if (n <= 0) {
                if (!usb.isOpen()) {
                    closed("Radio disconnected");
                    return;
                }
                continue;
            }

            lastRxAt = SystemClock.elapsedRealtime();
            accumulator.write(chunk, 0, n);
            parseAccumulated();
        }
        closed("Reader stopped");
    }

    /** Decodes as many packets/frames as the buffer currently holds. */
    private void parseAccumulated() {
        int[] consumed = new int[1];
        while (true) {
            byte[] buffer = accumulator.toByteArray();
            if (buffer.length == 0) return;

            int first = buffer[0] & 0xff;
            if (first == 0xAB) {
                UvProtocol.Packet packet = UvProtocol.parse(buffer, 0, buffer.length, consumed);
                if (packet == null) {
                    if (consumed[0] > 0) {
                        drop(consumed[0]);
                        continue;
                    }
                    return; /* incomplete runtime packet */
                }
                drop(consumed[0]);
                deliver(packet);
                continue;
            }

            UvFrames.Frame frame = UvFrames.parse(buffer, 0, buffer.length, consumed);
            if (frame == null) {
                if (consumed[0] > 0) {
                    drop(consumed[0]);
                    continue;
                }
                return; /* incomplete frame */
            }
            drop(consumed[0]);
            deliverFrame(frame);
        }
    }

    private void drop(int count) {
        byte[] buffer = accumulator.toByteArray();
        accumulator.reset();
        if (count < buffer.length) {
            accumulator.write(buffer, count, buffer.length - count);
        }
    }

    private void deliverFrame(UvFrames.Frame frame) {
        Listener l = listener;
        if (l == null) return;
        try {
            l.onFrame(this, frame.type, frame.flags, frame.payload);
        } catch (Throwable t) {
            Log.w(TAG, "frame listener: " + t);
        }
    }

    private void deliver(UvProtocol.Packet packet) {
        synchronized (inbox) {
            if (inbox.size() > 64) inbox.pollFirst(); /* frames are droppable */
            inbox.addLast(packet);
            inbox.notifyAll();
        }
        Listener l = listener;
        if (l != null) {
            try {
                l.onPacket(this, packet.type, packet.data);
            } catch (Throwable t) {
                Log.w(TAG, "listener: " + t);
            }
        }
    }

    private void closed(String reason) {
        if (!reading) return;
        reading = false;
        Listener l = listener;
        if (l != null) l.onClosed(reason);
    }

    /* ------------------------------------------------------------------ */
    /*  Messaging                                                          */
    /* ------------------------------------------------------------------ */

    /** Fire and forget. Returns false when the USB write failed. */
    boolean send(int type, byte[] payload) {
        byte[] bytes = UvProtocol.create(type, payload);
        synchronized (ioLock) {
            return usb.write(bytes) == bytes.length;
        }
    }

    /** Raw bytes (AA 55 frames, keepalives); no framing is applied. */
    boolean sendRaw(byte[] bytes) {
        synchronized (ioLock) {
            return usb.write(bytes) == bytes.length;
        }
    }

    /**
     * Sends one command and waits for a reply.
     *
     * @param accept reply types accepted; empty means "the next packet of any
     *               type" (useful while a spec is still being confirmed).
     * @return the reply packet, or null on timeout / disconnection.
     */
    UvProtocol.Packet request(int type, byte[] payload, int timeoutMs, int... accept) {
        synchronized (ioLock) {
            while (true) {
                synchronized (inbox) { inbox.clear(); }
                if (!send(type, payload)) {
                    lastError = "Write failed";
                    return null;
                }
                UvProtocol.Packet reply = await(accept, timeoutMs);
                if (reply != null) return reply;
                lastError = "Timeout on 0x" + Integer.toHexString(type);
                return null;
            }
        }
    }

    private UvProtocol.Packet await(int[] accept, int timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (true) {
            synchronized (inbox) {
                while (!inbox.isEmpty()) {
                    UvProtocol.Packet p = inbox.pollFirst();
                    if (accept.length == 0 || contains(accept, p.type)) return p;
                }
                long left = deadline - SystemClock.elapsedRealtime();
                if (left <= 0) return null;
                try {
                    inbox.wait(Math.min(left, 200));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            if (!reading) return null;
        }
    }

    private static boolean contains(int[] values, int value) {
        for (int v : values) if (v == value) return true;
        return false;
    }

    /** Reads one packet of any type (streamed frames, notifications). */
    UvProtocol.Packet poll(int timeoutMs, int... accept) {
        return await(accept, timeoutMs);
    }

    void drain() {
        synchronized (inbox) { inbox.clear(); }
    }

    long idleMs() {
        return lastRxAt == 0 ? Long.MAX_VALUE : SystemClock.elapsedRealtime() - lastRxAt;
    }
}
