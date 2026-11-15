package ro.reflex.uvstudio;

/** CRC-32 (zlib/PNG): poly 0xEDB88320 reflected, init/xor 0xFFFFFFFF. */
final class Crc32 {

    private static final int[] TABLE = new int[256];

    static {
        for (int i = 0; i < 256; i++) {
            int c = i;
            for (int bit = 0; bit < 8; bit++) {
                c = (c & 1) != 0 ? 0xEDB88320 ^ (c >>> 1) : c >>> 1;
            }
            TABLE[i] = c;
        }
    }

    private Crc32() { }

    static int of(byte[] data, int offset, int length) {
        int crc = 0xFFFFFFFF;
        for (int i = 0; i < length; i++) {
            crc = TABLE[(crc ^ data[offset + i]) & 0xFF] ^ (crc >>> 8);
        }
        return crc ^ 0xFFFFFFFF;
    }

    static int of(byte[] data) {
        return of(data, 0, data.length);
    }
}
