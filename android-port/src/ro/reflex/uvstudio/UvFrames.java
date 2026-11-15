package ro.reflex.uvstudio;

/**
 * The radio's display/RF stream, a second framing that travels on the same
 * UART as the ab-cd command packets:
 *
 *   [marker] AA 55 type size_hi size_lo payload(size) 0A
 *
 * The optional marker byte is either 0xFF (legacy frame, no flags) or a byte
 * whose high nibble is 0xF; its low nibble carries state flags
 * (bit0 deep sleep, bit1 red LED, bit2 green LED).
 *
 * Types: 0x01 full 1024-byte screen, 0x02 screen diff (9-byte chunks),
 * 0x05 RF log, 0x06 RF log history.
 */
final class UvFrames {

    static final int HEADER_0 = 0xAA;
    static final int HEADER_1 = 0x55;

    static final int TYPE_SCREENSHOT = 0x01;
    static final int TYPE_DIFF = 0x02;
    static final int TYPE_KEY = 0x03;
    static final int TYPE_KEY_LONG = 0x04;
    static final int TYPE_RF_LOG = 0x05;
    static final int TYPE_RF_LOG_HISTORY = 0x06;

    static final int FLAG_DEEP_SLEEP = 1 << 0;
    static final int FLAG_LED_RED = 1 << 1;
    static final int FLAG_LED_GREEN = 1 << 2;

    static final int WIDTH = 128;
    static final int HEIGHT = 64;
    static final int FRAME_SIZE = 1024;

    /** Parse outcome codes. */
    static final int NEED_MORE = 0;
    static final int INVALID = -1;

    static final class Frame {
        final int type;
        final int flags;
        final byte[] payload;

        Frame(int type, int flags, byte[] payload) {
            this.type = type;
            this.flags = flags;
            this.payload = payload;
        }
    }

    private UvFrames() { }

    /**
     * Attempts to decode one frame at {@code offset}.
     *
     * @param consumed number of bytes to drop (0 when more data is needed)
     * @return the frame, or null when incomplete/invalid
     */
    static Frame parse(byte[] buffer, int offset, int limit, int[] consumed) {
        consumed[0] = 0;
        int end = Math.min(limit, buffer.length);
        if (offset >= end) return null;

        int headerStart = offset;
        int flags = 0;
        boolean marked = false;

        int first = buffer[offset] & 0xff;
        if (first == 0xFF) {
            marked = true;
            headerStart = offset + 1;
        } else if ((first & 0xF0) == 0xF0) {
            marked = true;
            flags = first & 0x0F;
            headerStart = offset + 1;
        }

        if (headerStart + 4 >= end) {
            /* Need at least five bytes after the header start. */
            return null;
        }
        if ((buffer[headerStart] & 0xff) != HEADER_0
                || (buffer[headerStart + 1] & 0xff) != HEADER_1) {
            /* A stray marker byte: drop only it, so the next byte still gets a
             * chance to start an ab-cd command packet. */
            consumed[0] = 1;
            return null;
        }

        int type = buffer[headerStart + 2] & 0xff;
        int size = ((buffer[headerStart + 3] & 0xff) << 8) | (buffer[headerStart + 4] & 0xff);
        int markerSize = marked ? 1 : 0;
        int total = markerSize + 5 + size + 1;

        if (size > 8192) {
            consumed[0] = 1;
            return null;
        }
        if (offset + total > end) {
            return null;
        }
        if (buffer[offset + total - 1] != 0x0A) {
            consumed[0] = 1;
            return null;
        }

        byte[] payload = new byte[size];
        System.arraycopy(buffer, headerStart + 5, payload, 0, size);
        consumed[0] = total;
        return new Frame(type, flags, payload);
    }

    /** Host -> radio key event: AA 55 03/04 key. */
    static byte[] key(int keyCode, boolean longPress) {
        return new byte[] {
                (byte) 0xAA, (byte) 0x55,
                (byte) (longPress ? TYPE_KEY_LONG : TYPE_KEY),
                (byte) keyCode
        };
    }

    /** Keepalive: 55 AA 00 00 grants the firmware a frame budget. */
    static final byte[] KEEPALIVE = {0x55, (byte) 0xAA, 0x00, 0x00};

    /** RF-log subscription piggybacked on the keepalive (55 AA 05 features). */
    static byte[] featureKeepalive(boolean restart) {
        int features = 0x01 | 0x02;
        if (restart) features |= 0x80;
        return new byte[] {0x55, (byte) 0xAA, TYPE_RF_LOG, (byte) features};
    }

    /** Encrypted reboot command (0x05DD, empty payload). */
    static final byte[] REBOOT = {
            (byte) 0xAB, (byte) 0xCD, 0x04, 0x00, (byte) 0xCB, 0x69,
            0x14, (byte) 0xE6, 0x5B, (byte) 0xEB, (byte) 0xDC, (byte) 0xBA
    };

    /** Applies a diff payload: repeated (chunk index, 8 bytes) records. */
    static void applyDiff(byte[] frameBuffer, byte[] diff) {
        int i = 0;
        while (i + 9 <= diff.length) {
            int chunk = diff[i] & 0xff;
            i++;
            if (chunk >= 128) break;
            int start = chunk * 8;
            for (int j = 0; j < 8; j++) {
                if (start + j < frameBuffer.length) {
                    frameBuffer[start + j] = diff[i + j];
                }
            }
            i += 8;
        }
    }

    /** LSB-first, row-major, 128x64: 1024 bytes. */
    static int bit(byte[] frameBuffer, int x, int y) {
        int index = y * WIDTH + x;
        int byteIndex = index >> 3;
        if (byteIndex >= frameBuffer.length) return 0;
        return (frameBuffer[byteIndex] >> (index & 7)) & 1;
    }
}
