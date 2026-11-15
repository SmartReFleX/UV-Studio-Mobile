package ro.reflex.uvstudio;

/**
 * Wire format of the UV-K1 / UV-K5-v3 runtime protocol, as used by UV Studio.
 *
 *   ab cd | len u16 | ... len bytes ... | crc u16 | dc ba
 *
 * The 2-byte length covers type + dataLength + data (+1 pad byte when odd).
 * Everything from the type field to the CRC is XOR-obfuscated with a fixed
 * 16-byte table; the 4-byte header and 2-byte trailer are sent in clear.
 *
 * This is an independent Java implementation of that wire format; the
 * framing rules were derived from the published UV Studio sources and no
 * JavaScript is bundled or executed.  It does no I/O: framing only.
 * RadioLink owns the stream, the screens own the meaning.
 */
final class UvProtocol {

    private static final byte[] OBFUSCATION = {
            0x16, 0x6c, 0x14, (byte) 0xe6, 0x2e, (byte) 0x91, 0x0d, 0x40,
            0x21, 0x35, (byte) 0xd5, 0x40, 0x13, 0x03, (byte) 0xe9, (byte) 0x80
    };

    static final int MAX_MESSAGE = 512;

    private UvProtocol() { }

    /** One decoded message from the radio. */
    static final class Packet {
        final int type;
        /** Declared payload length (the dataLen field). */
        final int dataLength;
        /**
         * Everything after the 4-byte message header, exactly like the web
         * client: payload + optional pad byte + the 2 CRC bytes.  Firmware
         * replies keep a 0xFFFF CRC placeholder there.
         */
        final byte[] data;

        Packet(int type, int dataLength, byte[] data) {
            this.type = type;
            this.dataLength = dataLength;
            this.data = data;
        }

        byte[] payload() {
            byte[] out = new byte[Math.min(dataLength, data.length)];
            System.arraycopy(data, 0, out, 0, out.length);
            return out;
        }

        @Override
        public String toString() {
            return "Packet{type=0x" + Integer.toHexString(type) + ", len=" + data.length + "}";
        }
    }

    static byte[] create(int type, byte[] data) {
        if (data == null) data = new byte[0];
        int payloadLength = 4 + data.length;
        int messageLength = payloadLength + (payloadLength & 1);
        byte[] packet = new byte[8 + messageLength];

        putU16(packet, 0, 0xCDAB);
        putU16(packet, 2, messageLength);
        putU16(packet, 4, type);
        putU16(packet, 6, data.length);
        System.arraycopy(data, 0, packet, 8, data.length);

        putU16(packet, 4 + messageLength, crc16(packet, 4, messageLength));
        putU16(packet, 6 + messageLength, 0xBADC);
        xor(packet, 4, messageLength + 2);
        return packet;
    }

    /** In-place XOR with the fixed table. */
    static void xor(byte[] buffer, int offset, int size) {
        for (int i = 0; i < size; i++) {
            buffer[offset + i] ^= OBFUSCATION[i % OBFUSCATION.length];
        }
    }

    static int crc16(byte[] buffer, int offset, int size) {
        int crc = 0;
        for (int i = 0; i < size; i++) {
            crc ^= (buffer[offset + i] & 0xff) << 8;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc & 0x8000) != 0
                        ? ((crc << 1) ^ 0x1021) & 0xffff
                        : (crc << 1) & 0xffff;
            }
        }
        return crc;
    }

    /**
     * Tries to decode one packet at {@code offset}.
     *
     * @param consumed receives the number of bytes to drop: 0 when the packet is
     *                 incomplete (wait for more data), 1 for a stray/invalid byte.
     * @return the packet, or null when more bytes are needed or the data is bad.
     */
    static Packet parse(byte[] buffer, int offset, int limit, int[] consumed) {
        consumed[0] = 0;
        int end = Math.min(limit, buffer.length);
        if (offset < 0 || offset >= end) return null;
        if (buffer[offset] != (byte) 0xab) { consumed[0] = 1; return null; }
        if (offset + 1 >= end) return null;
        if (buffer[offset + 1] != (byte) 0xcd) { consumed[0] = 1; return null; }
        if (offset + 4 > end) return null;

        int messageLength = u16(buffer, offset + 2);
        if (messageLength < 4 || messageLength > MAX_MESSAGE) {
            consumed[0] = 1;
            return null;
        }

        int packetLength = 8 + messageLength;
        if (offset + packetLength > end) return null;
        if (buffer[offset + packetLength - 2] != (byte) 0xdc
                || buffer[offset + packetLength - 1] != (byte) 0xba) {
            consumed[0] = 1;
            return null;
        }

        byte[] decoded = new byte[messageLength + 2];
        System.arraycopy(buffer, offset + 4, decoded, 0, decoded.length);
        xor(decoded, 0, decoded.length);

        int expectedCrc = u16(decoded, messageLength);
        /* The radio keeps the 0xffff placeholder; host commands carry a real CRC. */
        if (expectedCrc != 0xffff && crc16(decoded, 0, messageLength) != expectedCrc) {
            consumed[0] = packetLength;
            return null;
        }

        int dataLength = u16(decoded, 2);
        if (dataLength > messageLength - 4) {
            consumed[0] = packetLength;
            return null;
        }

        byte[] data = new byte[messageLength - 2];
        System.arraycopy(decoded, 4, data, 0, data.length);
        consumed[0] = packetLength;
        return new Packet(u16(decoded, 0), dataLength, data);
    }

    /* ------------------------------ byte helpers ------------------------- */

    static int u16(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8);
    }

    static int u32(byte[] b, int off) {
        return (b[off] & 0xff)
                | ((b[off + 1] & 0xff) << 8)
                | ((b[off + 2] & 0xff) << 16)
                | ((b[off + 3] & 0xff) << 24);
    }

    static void putU16(byte[] b, int off, int value) {
        b[off] = (byte) (value & 0xff);
        b[off + 1] = (byte) ((value >> 8) & 0xff);
    }

    static void putU32(byte[] b, int off, long value) {
        b[off] = (byte) (value & 0xff);
        b[off + 1] = (byte) ((value >> 8) & 0xff);
        b[off + 2] = (byte) ((value >> 16) & 0xff);
        b[off + 3] = (byte) ((value >> 24) & 0xff);
    }

    /** Big-endian 16-bit read, for payloads that do not use the packet's LE. */
    static int be16(byte[] b, int off) {
        return ((b[off] & 0xff) << 8) | (b[off + 1] & 0xff);
    }

    static String hex(byte[] data, int max) {
        StringBuilder out = new StringBuilder();
        int n = Math.min(data.length, max);
        for (int i = 0; i < n; i++) {
            if (i > 0) out.append(' ');
            out.append(String.format("%02x", data[i] & 0xff));
        }
        if (data.length > n) out.append(" … (").append(data.length).append(" bytes)");
        return out.toString();
    }
}
