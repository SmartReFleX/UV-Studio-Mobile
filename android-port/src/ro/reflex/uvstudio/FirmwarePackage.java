package ro.reflex.uvstudio;

import java.io.IOException;

/**
 * A release .bin is [firmware image][FWA1 asset pack][16-byte trailer].
 * With no trailer the whole file is the image.
 */
final class FirmwarePackage {

    private static final int TRAILER_SIZE = 16;
    private static final long TRAILER_MAGIC = 0x54415746L; /* "FWAT" */
    private static final int PACK_HEADER = 32;
    private static final int IMAGE_MAX = 0x1D800;
    private static final int ZONE_SIZE = 0x4000;

    byte[] image;
    byte[] assets;
    String error;

    static FirmwarePackage parse(byte[] file) {
        FirmwarePackage out = new FirmwarePackage();
        if (file == null || file.length < TRAILER_SIZE) {
            out.image = file;
            return out;
        }

        int base = file.length - TRAILER_SIZE;
        if (UvProtocol.u32(file, base) == TRAILER_MAGIC) {
            long imageSize = UvProtocol.u32(file, base + 4) & 0xFFFFFFFFL;
            long packSize = UvProtocol.u32(file, base + 8) & 0xFFFFFFFFL;
            long crc = UvProtocol.u32(file, base + 12) & 0xFFFFFFFFL;

            if (imageSize <= 0 || imageSize > IMAGE_MAX
                    || imageSize + packSize != base
                    || (Crc32.of(file, 0, base) & 0xFFFFFFFFL) != crc) {
                out.error = "Firmware package is corrupt (trailer check failed)";
                return out;
            }

            out.image = new byte[(int) imageSize];
            System.arraycopy(file, 0, out.image, 0, out.image.length);

            if (packSize > 0) {
                byte[] pack = new byte[(int) packSize];
                System.arraycopy(file, (int) imageSize, pack, 0, pack.length);
                if (!validPack(pack)) {
                    out.error = "Asset pack is corrupt (FWA1 header)";
                    return out;
                }
                out.assets = pack;
            }
            return out;
        }

        out.image = file;
        return out;
    }

    private static boolean validPack(byte[] pack) {
        if (pack.length < PACK_HEADER) return false;
        if (UvProtocol.u32(pack, 0) != 0x31415746L) return false; /* "FWA1" */
        if (UvProtocol.u16(pack, 4) != 1) return false;
        if (UvProtocol.u16(pack, 6) != PACK_HEADER) return false;
        long payloadSize = UvProtocol.u32(pack, 8) & 0xFFFFFFFFL;
        long crc = UvProtocol.u32(pack, 12) & 0xFFFFFFFFL;
        if (payloadSize <= 0 || payloadSize + PACK_HEADER != pack.length) return false;
        if (pack.length > ZONE_SIZE) return false;
        return (Crc32.of(pack, PACK_HEADER, pack.length - PACK_HEADER) & 0xFFFFFFFFL) == crc;
    }

    String buildId() {
        if (assets == null || assets.length < PACK_HEADER) return "";
        return Radio.ascii(assets, 16, 16);
    }

    void requireImage() throws IOException {
        if (error != null) throw new IOException(error);
        if (image == null || image.length == 0) throw new IOException("Empty firmware file");
        if (image.length > IMAGE_MAX) throw new IOException("Firmware larger than 118 KiB");
    }
}
