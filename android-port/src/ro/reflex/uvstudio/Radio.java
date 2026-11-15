package ro.reflex.uvstudio;

import android.os.SystemClock;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * UV Studio protocol, natively.
 *
 * Every command that the web application sends is implemented here once, with
 * the same field layout, timeouts and retry policy (see _ref/spec/*.md).  The
 * screens only deal with bytes and progress; they never touch the wire format.
 */
final class Radio {

    /* --- message types ------------------------------------------------- */
    static final int DEV_INFO = 0x0514;
    static final int DEV_INFO_RESP = 0x0515;
    static final int NOTIFY_DEV_INFO = 0x0518;
    static final int PROG_FW = 0x0519;
    static final int PROG_FW_RESP = 0x051A;
    static final int READ_EEPROM = 0x051B;
    static final int READ_EEPROM_RESP = 0x051C;
    static final int WRITE_EEPROM = 0x051D;
    static final int WRITE_EEPROM_RESP = 0x051E;
    static final int NOTIFY_BL_VER = 0x0530;
    static final int REBOOT = 0x05DD;

    static final int SLOT_INFO = 0x0720;
    static final int SLOT_INFO_RESP = 0x0721;
    static final int SLOT_ERASE = 0x0722;
    static final int SLOT_ERASE_RESP = 0x0723;
    static final int SLOT_WRITE = 0x0724;
    static final int SLOT_WRITE_RESP = 0x0725;
    static final int SLOT_VALIDATE = 0x0726;
    static final int SLOT_VALIDATE_RESP = 0x0727;
    static final int PROFILE_ERASE = 0x0728;
    static final int PROFILE_ERASE_RESP = 0x0729;

    static final int APP_INFO = 0x0730;
    static final int APP_INFO_RESP = 0x0731;
    static final int APP_ERASE = 0x0732;
    static final int APP_ERASE_RESP = 0x0733;
    static final int APP_WRITE = 0x0734;
    static final int APP_WRITE_RESP = 0x0735;
    static final int APP_VALIDATE = 0x0736;
    static final int APP_VALIDATE_RESP = 0x0737;

    static final int FLASH_READ = 0x0738;
    static final int FLASH_READ_RESP = 0x0739;
    static final int FLASH_ERASE = 0x073A;
    static final int FLASH_ERASE_RESP = 0x073B;
    static final int FLASH_WRITE = 0x073C;
    static final int FLASH_WRITE_RESP = 0x073D;
    static final int FLASH_CRC = 0x073E;
    static final int FLASH_CRC_RESP = 0x073F;

    static final int ASSET_INFO = 0x0750;
    static final int ASSET_INFO_RESP = 0x0751;
    static final int ASSET_ERASE = 0x0752;
    static final int ASSET_ERASE_RESP = 0x0753;
    static final int ASSET_WRITE = 0x0754;
    static final int ASSET_WRITE_RESP = 0x0755;
    static final int ASSET_VALIDATE = 0x0756;
    static final int ASSET_VALIDATE_RESP = 0x0757;

    /* --- geometry ------------------------------------------------------- */
    static final int CALIB_SIZE = 512;
    static final int CALIB_CHUNK = 16;
    static final int CALIB_OFFSET_OLD = 0x1E00;
    static final int CALIB_OFFSET_NEW = 0xB000;

    static final int LOGO_OFFSET = 0xC000;
    static final int LOGO_HEADER = 8;
    static final int LOGO_BITMAP = 1024;
    static final int LOGO_TOTAL = 1032;
    static final int LOGO_PADDED = 1040;

    static final int FLASH_TOTAL = 0x200000;
    static final int FLASH_CHUNK = 128;
    static final int FLASH_SECTOR = 0x1000;
    static final int FLASH_CALIB_SECTOR = 0x010000;

    static final int SLOT_COUNT = 4;
    static final int SLOT_IMG_OFFSET = 0x1000;
    static final int SLOT_IMG_MAX = 0x1D800;
    static final int SLOT_HDR_SIZE = 64;
    static final int SLOT_STRIDE_HINT = 0x20000;
    static final int SLOT_MAGIC = 0x31424D46;

    static final int ASSET_RUNNING_SLOT = 0xFF;
    static final int ASSET_ZONE = 0x4000;

    private static final int CHUNK_TIMEOUT = 3000;

    private final RadioLink link;
    private int ts = (int) (System.currentTimeMillis() & 0xFFFFFFFFL);
    private boolean crcSupported;

    Radio(RadioLink link) {
        this.link = link;
    }

    /* ===================================================================== */
    /*  Session                                                             */
    /* ===================================================================== */

    /** Sends 0x0514 and returns the ASCII firmware string. */
    String deviceInfo(int timeoutMs) throws IOException {
        ts = (int) (System.currentTimeMillis() & 0xFFFFFFFFL);
        byte[] payload = new byte[4];
        UvProtocol.putU32(payload, 0, ts & 0xFFFFFFFFL);
        UvProtocol.Packet reply = link.request(DEV_INFO, payload, timeoutMs, DEV_INFO_RESP);
        if (reply == null) throw new IOException("No answer from the radio");
        return ascii(reply.data, 0, Math.min(32, reply.data.length));
    }

    boolean probe(int timeoutMs) {
        try {
            deviceInfo(timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    int timestamp() {
        return ts;
    }

    boolean isOpen() {
        return link.isOpen();
    }

    void reboot() {
        link.send(REBOOT, new byte[0]);
        sleep(500);
    }

    static String hexId(byte[] data, int offset, int length) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < length && offset + i < data.length; i++) {
            out.append(String.format("%02X", data[offset + i] & 0xFF));
        }
        return out.toString();
    }

    static String ascii(byte[] data, int offset, int max) {        StringBuilder out = new StringBuilder();
        for (int i = offset; i < Math.min(data.length, offset + max); i++) {
            int c = data[i] & 0xFF;
            if (c == 0 || c == 0xFF) break;
            if (c >= 32 && c < 127) out.append((char) c);
        }
        return out.toString().trim();
    }

    /* ===================================================================== */
    /*  Generic command plumbing                                            */
    /* ===================================================================== */

    private UvProtocol.Packet cmd(int type, byte[] payload, int timeoutMs, int expect) {
        return link.request(type, payload, timeoutMs, expect);
    }

    /** Retries a command up to {@code attempts} times, immediately. */
    private UvProtocol.Packet retry(int type, byte[] payload, int timeoutMs, int expect, int attempts) {
        for (int attempt = 0; attempt < attempts; attempt++) {
            UvProtocol.Packet reply = cmd(type, payload, timeoutMs, expect);
            if (reply != null) return reply;
        }
        return null;
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /* ===================================================================== */
    /*  EEPROM-compatible space: calibration and boot logo                  */
    /* ===================================================================== */

    /** major version >= 5 stores calibration at 0xB000 instead of 0x1E00. */
    static int calibrationOffset(String firmware) {
        if (firmware == null) return CALIB_OFFSET_OLD;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("v(\\d+)", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(firmware);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1)) >= 5 ? CALIB_OFFSET_NEW : CALIB_OFFSET_OLD;
            } catch (NumberFormatException ignored) {
                return CALIB_OFFSET_OLD;
            }
        }
        return CALIB_OFFSET_OLD;
    }

    /** Reads {@code length} bytes from the EEPROM-compat space in 16-byte chunks. */
    byte[] readEeprom(int offset, int length, Worker.Progress progress) throws IOException {
        byte[] out = new byte[length];
        for (int done = 0; done < length; done += CALIB_CHUNK) {
            if (progress != null) {
                progress.publish(done * 100 / length, "Reading " + done + "/" + length + " bytes");
                if (progress.isCancelled()) throw new IOException("Cancelled");
            }
            byte[] chunk = readEepromChunk(offset + done, Math.min(CALIB_CHUNK, length - done));
            System.arraycopy(chunk, 0, out, done, chunk.length);
        }
        return out;
    }

    private byte[] readEepromChunk(int offset, int length) throws IOException {
        byte[] payload = new byte[8];
        UvProtocol.putU16(payload, 0, offset & 0xFFFF);
        UvProtocol.putU16(payload, 2, length);
        UvProtocol.putU32(payload, 4, ts & 0xFFFFFFFFL);

        long deadline = SystemClock.elapsedRealtime() + CHUNK_TIMEOUT;
        while (SystemClock.elapsedRealtime() < deadline) {
            UvProtocol.Packet reply = cmd(READ_EEPROM, payload, 1000, READ_EEPROM_RESP);
            if (reply == null) continue;
            if (UvProtocol.u16(reply.data, 0) != (offset & 0xFFFF)) continue;
            if ((reply.data[2] & 0xFF) != length) continue;
            byte[] out = new byte[length];
            System.arraycopy(reply.data, 4, out, 0, length);
            return out;
        }
        throw new IOException(String.format("Read timeout at 0x%04X", offset));
    }

    /** Writes 16-byte chunks; the flag byte 0x01 mirrors the web client. */
    void writeEeprom(int offset, byte[] data, Worker.Progress progress) throws IOException {
        for (int done = 0; done < data.length; done += CALIB_CHUNK) {
            if (progress != null) {
                progress.publish(done * 100 / data.length, "Writing " + done + "/" + data.length + " bytes");
                if (progress.isCancelled()) throw new IOException("Cancelled");
            }
            int length = Math.min(CALIB_CHUNK, data.length - done);
            byte[] payload = new byte[8 + length];
            UvProtocol.putU16(payload, 0, (offset + done) & 0xFFFF);
            payload[2] = (byte) length;
            payload[3] = 0x01;
            UvProtocol.putU32(payload, 4, ts & 0xFFFFFFFFL);
            System.arraycopy(data, done, payload, 8, length);

            long deadline = SystemClock.elapsedRealtime() + CHUNK_TIMEOUT;
            boolean ok = false;
            while (SystemClock.elapsedRealtime() < deadline && !ok) {
                UvProtocol.Packet reply = cmd(WRITE_EEPROM, payload, 1000, WRITE_EEPROM_RESP);
                if (reply == null) continue;
                ok = UvProtocol.u16(reply.data, 0) == ((offset + done) & 0xFFFF);
            }
            if (!ok) throw new IOException(String.format("Write timeout at 0x%04X", offset + done));
        }
    }

    byte[] readCalibration(String firmware, Worker.Progress progress) throws IOException {
        return readEeprom(calibrationOffset(firmware), CALIB_SIZE, progress);
    }

    void writeCalibration(byte[] data, Worker.Progress progress) throws IOException {
        if (data.length != CALIB_SIZE) throw new IOException("Calibration file must be 512 bytes");
        String firmware = deviceInfo(1500);
        writeEeprom(calibrationOffset(firmware), data, progress);
        reboot();
    }

    byte[] readLogo(Worker.Progress progress) throws IOException {
        return readEeprom(LOGO_OFFSET, LOGO_PADDED, progress);
    }

    void writeLogo(byte[] padded, Worker.Progress progress) throws IOException {
        if (padded.length != LOGO_PADDED) throw new IOException("Logo payload must be 1040 bytes");
        writeEeprom(LOGO_OFFSET, padded, progress);
        reboot();
    }

    /* ===================================================================== */
    /*  External SPI flash (physical addresses, 2 MiB)                      */
    /* ===================================================================== */

    byte[] readFlashChunk(int address, int length) throws IOException {
        byte[] payload = new byte[10];
        UvProtocol.putU32(payload, 0, address & 0xFFFFFFFFL);
        UvProtocol.putU16(payload, 4, length);
        UvProtocol.putU32(payload, 6, ts & 0xFFFFFFFFL);

        UvProtocol.Packet reply = retry(FLASH_READ, payload, 2000, FLASH_READ_RESP, 4);
        if (reply == null) throw new IOException(String.format("Flash read timeout at 0x%06X", address));
        if (UvProtocol.u32(reply.data, 0) != (address & 0xFFFFFFFFL)) {
            throw new IOException("Flash read: wrong address echoed");
        }
        if (UvProtocol.u16(reply.data, 4) != length) throw new IOException("Flash read: wrong length");
        if ((reply.data[6] & 0xFF) != 0) throw new IOException("Flash read: status " + (reply.data[6] & 0xFF));
        if (reply.data.length < 7 + length) throw new IOException("Flash read: short payload");

        byte[] out = new byte[length];
        System.arraycopy(reply.data, 7, out, 0, length);
        return out;
    }

    boolean hasExternalFlash() {
        try {
            readFlashChunk(0, 1);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    void eraseFlashSector(int address) throws IOException {
        byte[] payload = new byte[8];
        UvProtocol.putU32(payload, 0, address & 0xFFFFFFFFL);
        UvProtocol.putU32(payload, 4, ts & 0xFFFFFFFFL);
        UvProtocol.Packet reply = retry(FLASH_ERASE, payload, 3000, FLASH_ERASE_RESP, 4);
        if (reply == null) throw new IOException(String.format("Erase timeout at 0x%06X", address));
        if (UvProtocol.u32(reply.data, 0) != (address & 0xFFFFFFFFL)) {
            throw new IOException("Erase: wrong address echoed");
        }
        if ((reply.data[4] & 0xFF) != 0) throw new IOException("Erase failed at 0x" + Integer.toHexString(address));
    }

    void writeFlashChunk(int address, byte[] data) throws IOException {
        byte[] payload = new byte[10 + data.length];
        UvProtocol.putU32(payload, 0, address & 0xFFFFFFFFL);
        UvProtocol.putU16(payload, 4, data.length);
        UvProtocol.putU32(payload, 6, ts & 0xFFFFFFFFL);
        System.arraycopy(data, 0, payload, 10, data.length);

        UvProtocol.Packet reply = retry(FLASH_WRITE, payload, 2000, FLASH_WRITE_RESP, 4);
        if (reply == null) throw new IOException(String.format("Write timeout at 0x%06X", address));
        if (UvProtocol.u32(reply.data, 0) != (address & 0xFFFFFFFFL)) {
            throw new IOException("Write: wrong address echoed");
        }
        if ((reply.data[6] & 0xFF) != 0) throw new IOException("Write failed at 0x" + Integer.toHexString(address));
    }

    /** CRC-32 of a flash range; returns -1 when the firmware has no support. */
    long flashCrc(int address, int length, int timeoutMs, int attempts) {
        byte[] payload = new byte[12];
        UvProtocol.putU32(payload, 0, address & 0xFFFFFFFFL);
        UvProtocol.putU32(payload, 4, length & 0xFFFFFFFFL);
        UvProtocol.putU32(payload, 8, ts & 0xFFFFFFFFL);

        UvProtocol.Packet reply = retry(FLASH_CRC, payload, timeoutMs, FLASH_CRC_RESP, attempts);
        if (reply == null) return -1;
        if ((reply.data[12] & 0xFF) != 0) return -1;
        if (UvProtocol.u32(reply.data, 4) != length) return -1;
        return UvProtocol.u32(reply.data, 8) & 0xFFFFFFFFL;
    }

    boolean detectCrcSupport() {
        crcSupported = flashCrc(0, 1, 1000, 1) >= 0;
        return crcSupported;
    }

    byte[] dumpExternalFlash(Worker.Progress progress) throws IOException {
        byte[] image = new byte[FLASH_TOTAL];
        for (int address = 0; address < FLASH_TOTAL; address += FLASH_CHUNK) {
            if (progress != null) {
                progress.publish(address * 100 / FLASH_TOTAL,
                        String.format("Reading 0x%06X / 0x200000", address));
                if (progress.isCancelled()) throw new IOException("Cancelled");
            }
            int length = Math.min(FLASH_CHUNK, FLASH_TOTAL - address);
            byte[] chunk = readFlashChunk(address, length);
            System.arraycopy(chunk, 0, image, address, length);
        }
        return image;
    }

    /**
     * Writes a full 2 MiB image sector by sector.
     *
     * @param factory when true the two multiboot marker sectors are written last,
     *                so the radio stays bootable as long as possible.
     */
    void restoreExternalFlash(byte[] image, Worker.Progress progress, boolean factory) throws IOException {
        if (image.length != FLASH_TOTAL) throw new IOException("Image must be exactly 2 MiB");

        boolean crc = detectCrcSupport();
        int[] order = sectorOrder(factory);
        for (int index = 0; index < order.length; index++) {
            int sector = order[index];
            if (progress != null) {
                progress.publish(index * 100 / order.length,
                        String.format("Sector 0x%06X (%d/%d)", sector, index + 1, order.length));
                if (progress.isCancelled()) throw new IOException("Cancelled");
            }
            if (sector == FLASH_CALIB_SECTOR) continue;

            int end = Math.min(sector + FLASH_SECTOR, FLASH_TOTAL);
            if (crc && flashCrc(sector, end - sector, 2000, 4) == (Crc32.of(image, sector, end - sector) & 0xFFFFFFFFL)) {
                continue;
            }

            eraseFlashSector(sector);
            for (int offset = sector; offset < end; offset += FLASH_CHUNK) {
                int length = Math.min(FLASH_CHUNK, end - offset);
                if (allOnes(image, offset, length)) continue;
                byte[] chunk = new byte[length];
                System.arraycopy(image, offset, chunk, 0, length);
                writeFlashChunk(offset, chunk);
            }
            verifySector(image, sector, end, crc);
        }
    }

    private static int[] sectorOrder(boolean factory) {
        int total = FLASH_TOTAL / FLASH_SECTOR;
        int[] temp = new int[total];
        int count = 0;
        for (int sector = 0; sector < FLASH_TOTAL; sector += FLASH_SECTOR) {
            if (sector == FLASH_CALIB_SECTOR) continue;
            if (factory && (sector == 0x100000 || sector == 0x101000)) continue;
            temp[count++] = sector;
        }
        if (factory) {
            temp[count++] = 0x100000;
            temp[count++] = 0x101000;
        }
        int[] out = new int[count];
        System.arraycopy(temp, 0, out, 0, count);
        return out;
    }

    private void verifySector(byte[] image, int sector, int end, boolean crc) throws IOException {
        int length = end - sector;
        if (crc) {
            long want = Crc32.of(image, sector, length) & 0xFFFFFFFFL;
            long got = flashCrc(sector, length, 2000, 4);
            if (got < 0) throw new IOException("CRC probe failed at 0x" + Integer.toHexString(sector));
            if (got != want) throw new IOException(String.format("Verify failed at 0x%06X", sector));
            return;
        }
        for (int offset = sector; offset < end; offset += FLASH_CHUNK) {
            int n = Math.min(FLASH_CHUNK, end - offset);
            byte[] read = readFlashChunk(offset, n);
            for (int i = 0; i < n; i++) {
                if (read[i] != image[offset + i]) {
                    throw new IOException(String.format("Verify failed at 0x%06X", offset + i));
                }
            }
        }
    }

    private static boolean allOnes(byte[] data, int offset, int length) {
        for (int i = 0; i < length; i++) {
            if (data[offset + i] != (byte) 0xFF) return false;
        }
        return true;
    }

    /* ===================================================================== */
    /*  Firmware slots                                                      */
    /* ===================================================================== */

    static final class SlotInfo {
        int slot;
        int status;
        byte[] header = new byte[SLOT_HDR_SIZE];

        boolean empty() {
            return status == 1;
        }

        String name() {
            return ascii(header, 16, 16);
        }

        String version() {
            return ascii(header, 32, 16);
        }

        int imageSize() {
            return UvProtocol.u32(header, 8);
        }

        long imageCrc() {
            return UvProtocol.u32(header, 12) & 0xFFFFFFFFL;
        }
    }

    SlotInfo slotInfo(int slot) throws IOException {
        UvProtocol.Packet reply = cmd(SLOT_INFO, new byte[] {(byte) slot}, 3000, SLOT_INFO_RESP);
        if (reply == null) throw new IOException("Slot " + slot + ": no answer");
        SlotInfo info = new SlotInfo();
        info.slot = reply.data[0] & 0xFF;
        info.status = reply.data[1] & 0xFF;
        System.arraycopy(reply.data, 2, info.header, 0, SLOT_HDR_SIZE);
        return info;
    }

    void slotErase(int slot) throws IOException {
        byte[] payload = new byte[6];
        payload[0] = (byte) slot;
        UvProtocol.putU32(payload, 2, ts & 0xFFFFFFFFL);
        UvProtocol.Packet reply = cmd(SLOT_ERASE, payload, 30000, SLOT_ERASE_RESP);
        if (reply == null) throw new IOException("Slot erase timeout");
        if ((reply.data[1] & 0xFF) != 0) throw new IOException("Slot erase error " + (reply.data[1] & 0xFF));
    }

    void profileErase(int slot) throws IOException {
        byte[] payload = new byte[6];
        payload[0] = (byte) slot;
        UvProtocol.putU32(payload, 2, ts & 0xFFFFFFFFL);
        UvProtocol.Packet reply = cmd(PROFILE_ERASE, payload, 30000, PROFILE_ERASE_RESP);
        if (reply == null) throw new IOException("Profile erase timeout");
        if ((reply.data[1] & 0xFF) != 0) throw new IOException("Profile erase error " + (reply.data[1] & 0xFF));
    }

    void slotWrite(int slot, int offset, byte[] chunk) throws IOException {
        byte[] payload = new byte[12 + chunk.length];
        payload[0] = (byte) slot;
        UvProtocol.putU32(payload, 2, offset & 0xFFFFFFFFL);
        UvProtocol.putU16(payload, 6, chunk.length);
        UvProtocol.putU32(payload, 8, ts & 0xFFFFFFFFL);
        System.arraycopy(chunk, 0, payload, 12, chunk.length);

        IOException last = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            UvProtocol.Packet reply = cmd(SLOT_WRITE, payload, 1500, SLOT_WRITE_RESP);
            if (reply != null && (reply.data[1] & 0xFF) == 0) return;
            last = new IOException("Slot write error at 0x" + Integer.toHexString(offset));
            sleep(60);
        }
        throw last != null ? last : new IOException("Slot write failed");
    }

    SlotValidate slotValidate(int slot) throws IOException {
        UvProtocol.Packet reply = cmd(SLOT_VALIDATE, new byte[] {(byte) slot}, 20000, SLOT_VALIDATE_RESP);
        if (reply == null) throw new IOException("Slot validate timeout");
        SlotValidate out = new SlotValidate();
        out.crc = UvProtocol.u32(reply.data, 0) & 0xFFFFFFFFL;
        out.status = reply.data[5] & 0xFF;
        return out;
    }

    static final class SlotValidate {
        long crc;
        int status;
    }

    /** Writes one firmware image into a multiboot slot. */
    void writeFirmwareSlot(int slot, byte[] image, String name, String version,
                           Worker.Progress progress) throws IOException {
        if (image.length > SLOT_IMG_MAX) throw new IOException("Firmware larger than 118 KiB");

        long crc = Crc32.of(image, 0, image.length) & 0xFFFFFFFFL;
        slotErase(slot);

        int written = 0;
        while (written < image.length) {
            if (progress != null) {
                progress.publish(written * 95 / image.length,
                        String.format("Slot %d: %d/%d bytes", slot, written, image.length));
                if (progress.isCancelled()) throw new IOException("Cancelled");
            }
            int length = Math.min(FLASH_CHUNK, image.length - written);
            byte[] chunk = new byte[length];
            System.arraycopy(image, written, chunk, 0, length);
            slotWrite(slot, SLOT_IMG_OFFSET + written, chunk);
            written += length;
        }

        byte[] header = slotHeader(image.length, crc, name, version);
        slotWrite(slot, 0, header);

        if (progress != null) progress.publish(97, "Validating slot " + slot);
        SlotValidate v = slotValidate(slot);
        if (v.status != 0 || v.crc != crc) throw new IOException("Slot validation failed");
    }

    static byte[] slotHeader(int imageSize, long crc, String name, String version) {
        byte[] header = new byte[SLOT_HDR_SIZE];
        UvProtocol.putU32(header, 0, SLOT_MAGIC);
        UvProtocol.putU16(header, 4, 1);
        UvProtocol.putU16(header, 6, 1);
        UvProtocol.putU32(header, 8, imageSize);
        UvProtocol.putU32(header, 12, crc & 0xFFFFFFFFL);
        writeAscii(header, 16, 16, name);
        writeAscii(header, 32, 16, version);
        return header;
    }

    static void writeAscii(byte[] target, int offset, int max, String value) {
        if (value == null) value = "";
        int n = Math.min(max, value.length());
        for (int i = 0; i < n; i++) {
            char c = value.charAt(i);
            target[offset + i] = (byte) (c >= 0x20 && c <= 0x7E ? c & 0x7F : 0);
        }
    }

    /* ===================================================================== */
    /*  Asset packs (FWA1)                                                  */
    /* ===================================================================== */

    static final class AssetInfo {
        int slot;
        int status;
        int runningSlot;
        boolean loaded;
        String runningBuildId = "";
    }

    AssetInfo assetInfo(int slot, int timeoutMs) throws IOException {
        UvProtocol.Packet reply = cmd(ASSET_INFO, new byte[] {(byte) slot}, timeoutMs, ASSET_INFO_RESP);
        if (reply == null) throw new IOException("Asset info timeout");
        AssetInfo info = new AssetInfo();
        info.slot = reply.data[0] & 0xFF;
        info.status = reply.data[1] & 0xFF;
        info.runningSlot = reply.data[2] & 0xFF;
        info.loaded = (reply.data[3] & 0xFF) != 0;
        info.runningBuildId = ascii(reply.data, 36, 16);
        return info;
    }

    void assetErase(int slot) throws IOException {
        byte[] payload = new byte[6];
        payload[0] = (byte) slot;
        UvProtocol.putU32(payload, 2, ts & 0xFFFFFFFFL);
        UvProtocol.Packet reply = cmd(ASSET_ERASE, payload, 30000, ASSET_ERASE_RESP);
        if (reply == null) throw new IOException("Asset erase timeout");
        if ((reply.data[1] & 0xFF) != 0) throw new IOException("Asset erase error " + (reply.data[1] & 0xFF));
    }

    void assetWrite(int slot, int offset, byte[] chunk) throws IOException {
        byte[] payload = new byte[12 + chunk.length];
        payload[0] = (byte) slot;
        UvProtocol.putU32(payload, 2, offset & 0xFFFFFFFFL);
        UvProtocol.putU16(payload, 6, chunk.length);
        UvProtocol.putU32(payload, 8, ts & 0xFFFFFFFFL);
        System.arraycopy(chunk, 0, payload, 12, chunk.length);

        for (int attempt = 0; attempt < 5; attempt++) {
            UvProtocol.Packet reply = cmd(ASSET_WRITE, payload, 1500, ASSET_WRITE_RESP);
            if (reply != null && (reply.data[1] & 0xFF) == 0) return;
            sleep(60);
        }
        throw new IOException("Asset write failed at 0x" + Integer.toHexString(offset));
    }

    void assetValidate(int slot) throws IOException {
        UvProtocol.Packet reply = cmd(ASSET_VALIDATE, new byte[] {(byte) slot}, 5000, ASSET_VALIDATE_RESP);
        if (reply == null) throw new IOException("Asset validate timeout");
        if ((reply.data[1] & 0xFF) != 0) throw new IOException("Asset validate error " + (reply.data[1] & 0xFF));
    }

    /** Writes a FWA1 pack: payload first, header last, so a partial write never validates. */
    void writeAssetPack(int slot, byte[] pack, Worker.Progress progress) throws IOException {
        if (pack.length > ASSET_ZONE) throw new IOException("Asset pack larger than the 16 KiB zone");
        assetErase(slot);
        for (int offset = 32; offset < pack.length; offset += FLASH_CHUNK) {
            if (progress != null) {
                progress.publish(offset * 100 / pack.length, "Assets " + offset + "/" + pack.length);
            }
            int length = Math.min(FLASH_CHUNK, pack.length - offset);
            byte[] chunk = new byte[length];
            System.arraycopy(pack, offset, chunk, 0, length);
            assetWrite(slot, offset, chunk);
        }
        byte[] header = new byte[32];
        System.arraycopy(pack, 0, header, 0, Math.min(32, pack.length));
        assetWrite(slot, 0, header);
        assetValidate(slot);
    }

    /* ===================================================================== */
    /*  Overlay apps                                                        */
    /* ===================================================================== */

    static final int APP_REGION_BASE = 0x102000;
    static final int APP_SLOT_STRIDE = 0x2000;
    static final int APP_IMG_OFFSET = 0x1000;
    static final int APP_ASSET_OFFSET = 0x100;
    static final int APP_HDR_SIZE = 64;
    static final int APP_CODE_MAX = 0x1000;
    static final int APP_ASSET_MAX = 0xF00;
    static final long APP_MAGIC = 0x31504146L; /* "FAP1" */

    static final class AppInfo {
        int slot;
        int status;
        byte[] header = new byte[APP_HDR_SIZE];

        boolean installed() {
            return status == 0;
        }

        boolean incompatible() {
            return status == 3 || status == 9;
        }

        String name() {
            return ascii(header, 20, 16);
        }

        String version() {
            return ascii(header, 36, 16);
        }

        int codeSize() {
            return UvProtocol.u32(header, 8);
        }

        int assetSize() {
            return UvProtocol.u16(header, 60);
        }
    }

    /** Number of overlay slots advertised by this firmware (16 / 48 / 64). */
    static int appSlotCount(String firmware) {
        if (firmware == null) return 16;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("v(\\d+)\\.(\\d+)\\.(\\d+)", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(firmware);
        if (!m.find()) return 16;
        int major = Integer.parseInt(m.group(1));
        int minor = Integer.parseInt(m.group(2));
        if (major >= 7) return 64;
        if (major == 6 && minor > 1) return 48;
        return 16;
    }

    AppInfo appInfo(int slot, int timeoutMs) throws IOException {
        UvProtocol.Packet reply = cmd(APP_INFO, new byte[] {(byte) slot}, timeoutMs, APP_INFO_RESP);
        if (reply == null) throw new IOException("App slot " + slot + ": no answer");
        AppInfo info = new AppInfo();
        info.slot = slot;
        info.status = reply.data[1] & 0xFF;
        System.arraycopy(reply.data, 2, info.header, 0, APP_HDR_SIZE);
        return info;
    }

    void appErase(int slot) throws IOException {
        byte[] payload = new byte[6];
        payload[0] = (byte) slot;
        UvProtocol.putU32(payload, 2, ts & 0xFFFFFFFFL);
        UvProtocol.Packet reply = cmd(APP_ERASE, payload, 30000, APP_ERASE_RESP);
        if (reply == null) throw new IOException("App erase timeout");
        if ((reply.data[1] & 0xFF) != 0) throw new IOException("App erase error " + (reply.data[1] & 0xFF));
    }

    void appWrite(int slot, int offset, byte[] chunk) throws IOException {
        byte[] payload = new byte[12 + chunk.length];
        payload[0] = (byte) slot;
        UvProtocol.putU32(payload, 2, offset & 0xFFFFFFFFL);
        UvProtocol.putU16(payload, 6, chunk.length);
        UvProtocol.putU32(payload, 8, ts & 0xFFFFFFFFL);
        System.arraycopy(chunk, 0, payload, 12, chunk.length);

        for (int attempt = 0; attempt < 5; attempt++) {
            UvProtocol.Packet reply = cmd(APP_WRITE, payload, 1500, APP_WRITE_RESP);
            if (reply != null && (reply.data[1] & 0xFF) == 0) return;
            sleep(60);
        }
        throw new IOException("App write failed at 0x" + Integer.toHexString(offset));
    }

    int appValidate(int slot) throws IOException {
        UvProtocol.Packet reply = cmd(APP_VALIDATE, new byte[] {(byte) slot}, 20000, APP_VALIDATE_RESP);
        if (reply == null) throw new IOException("App validate timeout");
        return reply.data[1] & 0xFF;
    }

    /** Installs a .app image (64-byte FAP1 header + code + assets) into a slot. */
    void installApp(int slot, byte[] app, Worker.Progress progress) throws IOException {
        if (app.length < APP_HDR_SIZE) throw new IOException("App file too short");
        if (UvProtocol.u32(app, 0) != APP_MAGIC) throw new IOException("Not a FAP1 app file");

        int codeSize = UvProtocol.u32(app, 8);
        int assetSize = UvProtocol.u16(app, 60);
        int codeEnd = APP_HDR_SIZE + codeSize;
        if (codeSize == 0 || codeEnd > app.length) throw new IOException("App file is truncated");
        int assetsLength = app.length - codeEnd;
        if (assetsLength != assetSize) throw new IOException("App asset size mismatch");
        if (codeSize > APP_CODE_MAX) throw new IOException("App code larger than 4 KiB");
        if (assetsLength > APP_ASSET_MAX) throw new IOException("App assets larger than 3.75 KiB");

        byte[] code = new byte[codeSize];
        System.arraycopy(app, APP_HDR_SIZE, code, 0, codeSize);
        byte[] assets = new byte[assetsLength];
        System.arraycopy(app, codeEnd, assets, 0, assetsLength);

        appErase(slot);

        int total = codeSize + assetsLength;
        int done = 0;
        for (int offset = 0; offset < code.length; offset += FLASH_CHUNK) {
            int length = Math.min(FLASH_CHUNK, code.length - offset);
            byte[] chunk = new byte[length];
            System.arraycopy(code, offset, chunk, 0, length);
            appWrite(slot, APP_IMG_OFFSET + offset, chunk);
            done += length;
            if (progress != null) progress.publish(done * 95 / Math.max(1, total), "Code " + done + "/" + total);
        }
        for (int offset = 0; offset < assets.length; offset += FLASH_CHUNK) {
            int length = Math.min(FLASH_CHUNK, assets.length - offset);
            byte[] chunk = new byte[length];
            System.arraycopy(assets, offset, chunk, 0, length);
            appWrite(slot, APP_ASSET_OFFSET + offset, chunk);
            done += length;
            if (progress != null) progress.publish(done * 95 / Math.max(1, total), "Assets " + done + "/" + total);
        }

        byte[] header = new byte[APP_HDR_SIZE];
        System.arraycopy(app, 0, header, 0, APP_HDR_SIZE);
        appWrite(slot, 0, header);

        int status = appValidate(slot);
        if (status != 0 && status != 3 && status != 9) {
            throw new IOException("App validation failed (code " + status + ")");
        }
        if (progress != null) progress.publish(100, "App installed");
    }

    /** Reads one whole 8 KiB app slot from the external flash. */
    byte[] readAppSlotImage(int slot) throws IOException {
        int base = APP_REGION_BASE + slot * APP_SLOT_STRIDE;
        byte[] image = new byte[APP_SLOT_STRIDE];
        for (int offset = 0; offset < image.length; offset += FLASH_CHUNK) {
            byte[] chunk = readFlashChunk(base + offset, FLASH_CHUNK);
            System.arraycopy(chunk, 0, image, offset, FLASH_CHUNK);
        }
        return image;
    }

    void writeAppSlotImage(int slot, byte[] image, Worker.Progress progress) throws IOException {
        int base = APP_REGION_BASE + slot * APP_SLOT_STRIDE;
        for (int offset = 0; offset < image.length; offset += FLASH_SECTOR) {
            int address = base + offset;
            int end = Math.min(offset + FLASH_SECTOR, image.length);
            if (flashCrc(address, end - offset, 2000, 4) == (Crc32.of(image, offset, end - offset) & 0xFFFFFFFFL)) {
                continue;
            }
            eraseFlashSector(address);
            for (int chunkOffset = offset; chunkOffset < end; chunkOffset += FLASH_CHUNK) {
                int length = Math.min(FLASH_CHUNK, end - chunkOffset);
                if (allOnes(image, chunkOffset, length)) continue;
                byte[] chunk = new byte[length];
                System.arraycopy(image, chunkOffset, chunk, 0, length);
                writeFlashChunk(base + chunkOffset, chunk);
            }
            if (progress != null) progress.publish(35 + offset * 60 / image.length, "Slot " + slot);
        }
    }

    /* ===================================================================== */
    /*  Bootloader (DFU)                                                    */
    /* ===================================================================== */

    static final class Bootloader {
        String uid = "";
        String version = "";
    }

    /**
     * Passive wait for the bootloader's 0x0518 notices: five intervals between
     * 5 and 1000 ms mean the device is really in DFU mode.
     */
    Bootloader waitForBootloader(Worker.Progress progress) throws IOException {
        Bootloader out = new Bootloader();
        int accepted = 0;
        long last = 0;
        long deadline = SystemClock.elapsedRealtime() + 8000;

        while (SystemClock.elapsedRealtime() < deadline) {
            UvProtocol.Packet packet = link.poll(200, NOTIFY_DEV_INFO);
            if (packet == null) continue;
            long now = SystemClock.elapsedRealtime();
            if (last != 0) {
                long dt = now - last;
                if (dt >= 5 && dt <= 1000) accepted++;
                else accepted = 0;
            }
            last = now;

            if (out.uid.isEmpty()) out.uid = hexId(packet.data, 0, 16);
            if (out.version.isEmpty()) out.version = ascii(packet.data, 16, 16);
            if (progress != null) {
                progress.publish(Math.min(90, accepted * 18),
                        "Bootloader " + out.version + " · " + accepted + "/5");
            }
            if (accepted >= 5) return out;
        }
        throw new IOException("No bootloader. Put the radio in DFU mode (PTT + power on).");
    }

    void bootloaderHandshake(String version) throws IOException {
        String token = version.length() >= 4 ? version.substring(0, 4) : "7.00";
        byte[] payload = token.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        for (int i = 0; i < 3; i++) {
            link.poll(1000, NOTIFY_DEV_INFO);
            if (link.send(NOTIFY_BL_VER, payload)) sleep(50);
        }
        sleep(200);
        link.drain();
    }

    /** Programs the image page by page; the bootloader erases and verifies. */
    void programImage(byte[] image, Worker.Progress progress) throws IOException {
        int pageCount = (image.length + 255) / 256;
        for (int page = 0; page < pageCount; page++) {
            if (progress != null) {
                progress.publish(page * 100 / pageCount,
                        String.format("Page %d/%d", page + 1, pageCount));
                if (progress.isCancelled()) throw new IOException("Cancelled");
            }
            int offset = page * 256;
            int length = Math.min(256, image.length - offset);

            byte[] payload = new byte[268];
            UvProtocol.putU32(payload, 0, ts & 0xFFFFFFFFL);
            UvProtocol.putU16(payload, 4, page);
            UvProtocol.putU16(payload, 6, pageCount);
            System.arraycopy(image, offset, payload, 12, length);

            boolean done = false;
            int errors = 0;
            while (!done) {
                UvProtocol.Packet reply = cmd(PROG_FW, payload, 3000, PROG_FW_RESP);
                if (reply == null) {
                    if (++errors > 3) throw new IOException("Too many timeouts at page " + page);
                    continue;
                }
                int replyPage = UvProtocol.u16(reply.data, 4);
                int err = UvProtocol.u16(reply.data, 6);
                if (err != 0) {
                    if (++errors > 3) throw new IOException("Page " + page + " error " + err);
                    continue;
                }
                done = replyPage == page;
            }
        }
    }
}
