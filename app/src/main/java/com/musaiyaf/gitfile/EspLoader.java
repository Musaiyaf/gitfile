package com.musaiyaf.gitfile;

import com.hoho.android.usbserial.driver.UsbSerialPort;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.List;
import java.util.zip.Deflater;

/**
 * A compact port of esptool's ROM/stub serial protocol to Java, enough to flash
 * an ESP board over a USB-serial link.
 *
 * The whole thing is a faithful reimplementation of Espressif's esptool-js
 * (the browser flasher), which is the reference that actually runs on hardware:
 * same SLIP framing, same command opcodes, same stub loader, same compressed
 * flash path. The chip-detection magic values come straight from esptool.
 *
 * It is deliberately IO-agnostic about files: the caller loads each .bin into a
 * byte[] and hands it over as a {@link Segment}. Everything this class touches is
 * the serial port and the (bundled) stub JSON. Progress and human-readable log
 * lines are pushed back through {@link Ui} as the flash runs, off the UI thread.
 */
public class EspLoader {

    /** Progress + log sink. Implemented by MainActivity, which forwards to JS. */
    public interface Ui {
        void stage(String s);
        void log(String line);
        /** overall is 0..100 across the whole job; sent/total are this file's bytes. */
        void progress(int fileIndex, String name, long sent, long total, int overall);
    }

    /** Returns the parsed stub JSON for a chip key (e.g. "esp32s3"), or null. */
    public interface StubSource {
        JSONObject stub(String chipKey);
    }

    /** One .bin to write, and where. */
    public static class Segment {
        public final String name;
        public final int    offset;
        public final byte[] data;
        public Segment(String name, int offset, byte[] data) {
            this.name = name; this.offset = offset; this.data = data;
        }
    }

    // ── Protocol constants (esptool) ──────────────────────────────────────────
    private static final int ESP_SYNC            = 0x08;
    private static final int ESP_READ_REG        = 0x0A;
    private static final int ESP_WRITE_REG       = 0x09;
    private static final int ESP_MEM_BEGIN       = 0x05;
    private static final int ESP_MEM_END         = 0x06;
    private static final int ESP_MEM_DATA        = 0x07;
    private static final int ESP_SPI_ATTACH      = 0x0D;
    private static final int ESP_CHANGE_BAUDRATE = 0x0F;
    private static final int ESP_FLASH_BEGIN     = 0x02;
    private static final int ESP_FLASH_DATA      = 0x03;
    private static final int ESP_FLASH_END       = 0x04;
    private static final int ESP_FLASH_DEFL_BEGIN= 0x10;
    private static final int ESP_FLASH_DEFL_DATA = 0x11;
    private static final int ESP_FLASH_DEFL_END  = 0x12;
    private static final int ESP_SPI_FLASH_MD5   = 0x13;
    private static final int ESP_GET_SECURITY_INFO = 0x14;

    private static final int ESP_CHECKSUM_MAGIC  = 0xEF;
    private static final int ESP_RAM_BLOCK       = 0x1800;
    private static final int CHIP_DETECT_MAGIC_REG_ADDR = 0x40001000;

    private static final int SLIP_END     = 0xC0;
    private static final int SLIP_ESC     = 0xDB;
    private static final int SLIP_ESC_END = 0xDC;
    private static final int SLIP_ESC_ESC = 0xDD;

    /** Espressif's native USB-Serial/JTAG bridge (needs the special reset dance). */
    private static final int USB_JTAG_SERIAL_PID = 0x1001;
    private static final int ESPRESSIF_VID       = 0x303A;

    private static final int ROM_BAUD = 115200;

    // ── State ─────────────────────────────────────────────────────────────────
    private final UsbSerialPort port;
    private final Ui  ui;
    private final StubSource stubs;
    private final boolean usbJtag;      // native-USB device → different reset/run

    private boolean isStub;
    private int     flashWriteSize = 0x4000;
    private String  chipName = "unknown";

    /** Set from another thread to abort a flash in progress. */
    public volatile boolean cancel;

    private final ArrayDeque<Integer> in = new ArrayDeque<>();
    private final byte[] readChunk = new byte[8192];

    public EspLoader(UsbSerialPort port, int vid, int pid, Ui ui, StubSource stubs) {
        this.port    = port;
        this.ui      = ui;
        this.stubs   = stubs;
        this.usbJtag = (vid == ESPRESSIF_VID && pid == USB_JTAG_SERIAL_PID);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Public entry point
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Connect, (optionally) upload the stub, switch baud, then write every
     * segment and verify it. Throws on any failure; the caller reports it.
     *
     * @param chipSel  "auto", or a forced CHIP_NAME like "ESP32-S3".
     * @param baud     target flashing baud (falls back to 115200 to connect).
     * @param useStub  upload the high-speed stub loader first.
     * @param compress send zlib-compressed (much faster; needs a working flash).
     */
    public void flash(String chipSel, int baud, boolean useStub, boolean compress,
                      List<Segment> segments) throws Exception {

        ui.stage("Connecting to the board…");
        port.setParameters(ROM_BAUD, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
        connect();

        // Detect for information; honour an explicit user choice over the guess.
        String detected = detectChip();
        if (chipSel != null && !chipSel.equalsIgnoreCase("auto") && !chipSel.isEmpty()) {
            chipName = chipSel;
            if (detected != null && !detected.equals(chipSel)) {
                ui.log("Note: board looks like " + detected + " but you selected "
                        + chipSel + "; using your selection.");
            }
        } else if (detected != null) {
            chipName = detected;
        } else {
            throw new IOException("Couldn't auto-detect the chip. Pick the chip type manually and try again.");
        }
        ui.stage("Chip: " + chipName);
        ui.log("Chip is " + chipName);

        if (useStub) {
            runStub();
        } else {
            ui.log("Skipping stub loader (High-Speed mode off).");
            if (isEsp8266()) flashWriteSize = 0x400;
        }

        if (baud != ROM_BAUD) {
            changeBaud(baud);
        }

        // Configure default SPI flash pins. Harmless on the standard boards, and
        // it heads off "no serial data" timeouts on some ESP32 variants.
        if (!isEsp8266()) {
            try { checkCommand("configure SPI flash pins", ESP_SPI_ATTACH, intLE(0), 0, 0, 3000); }
            catch (Exception e) { ui.log("SPI attach skipped: " + e.getMessage()); }
        }

        // Grand total (uncompressed) drives the single overall progress bar.
        long grandTotal = 0;
        for (Segment s : segments) grandTotal += pad4(s.data).length;
        long doneBytes = 0;

        for (int i = 0; i < segments.size(); i++) {
            if (cancel) throw new IOException("Cancelled.");
            Segment seg = segments.get(i);
            byte[] image = pad4(seg.data);
            int uncSize  = image.length;

            String md5Local = md5Hex(image);
            ui.stage("Writing " + seg.name + " → 0x" + Integer.toHexString(seg.offset));
            ui.log("Writing " + seg.name + " (" + uncSize + " bytes) at 0x"
                    + Integer.toHexString(seg.offset));

            byte[] toSend;
            int blocks;
            if (compress) {
                toSend = deflate(image);
                blocks = ceilDiv(toSend.length, flashWriteSize);
                flashDeflBegin(uncSize, toSend.length, seg.offset, blocks);
                ui.log("Compressed " + uncSize + " → " + toSend.length + " bytes");
            } else {
                toSend = image;
                blocks = ceilDiv(toSend.length, flashWriteSize);
                flashBegin(uncSize, seg.offset, blocks);
            }

            int seq = 0, off = 0;
            long sent = 0, total = toSend.length;
            while (off < toSend.length) {
                if (cancel) throw new IOException("Cancelled.");
                int n = Math.min(flashWriteSize, toSend.length - off);
                byte[] block = new byte[n];
                System.arraycopy(toSend, off, block, 0, n);
                if (compress) flashDeflBlock(block, seq);
                else          flashBlock(block, seq);
                off += n; seq++; sent += n;

                // This block covers (n/total) of the segment's UNcompressed bytes.
                double segFrac = (double) sent / total;
                int overall = grandTotal == 0 ? 100
                        : (int) ((doneBytes + segFrac * uncSize) * 100 / grandTotal);
                ui.progress(i, seg.name, (long) (segFrac * uncSize), uncSize, overall);
            }

            if (isStub) {
                if (compress) checkCommand("leave compressed flash mode", ESP_FLASH_DEFL_END, intLE(1), 0, 0, 3000);
                else          checkCommand("leave flash mode",            ESP_FLASH_END,       intLE(1), 0, 0, 3000);
            }

            // ESP8266 ROM has no MD5 command; the stub adds it. Verify when we can.
            if (isStub || !isEsp8266()) {
                String md5Flash = flashMd5(seg.offset, uncSize);
                if (!md5Local.equalsIgnoreCase(md5Flash)) {
                    throw new IOException("Verify failed for " + seg.name
                            + " (flash MD5 " + md5Flash + " ≠ file MD5 " + md5Local + ").");
                }
                ui.log("Verified " + seg.name + " ✓");
            }

            doneBytes += uncSize;
            ui.progress(i, seg.name, uncSize, uncSize,
                    grandTotal == 0 ? 100 : (int) (doneBytes * 100 / grandTotal));
        }

        ui.stage("Resetting the board…");
        ui.log("Leaving flasher, resetting to run firmware.");
        hardReset();
        ui.stage("Done");
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Connect / detect
    // ══════════════════════════════════════════════════════════════════════════

    private void connect() throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 7; attempt++) {
            if (cancel) throw new IOException("Cancelled.");
            try {
                if (usbJtag) resetUsbJtag();
                else         resetClassic(attempt % 2 == 0 ? 50 : 550);
            } catch (Exception ignore) { /* signal lines can be unsupported */ }

            flushInput();
            for (int i = 0; i < 5; i++) {
                try { sync(); ui.log("Sync OK."); return; }
                catch (IOException e) { last = e; }
            }
        }
        throw new IOException("Couldn't talk to the board. Check the cable, and that the board is "
                + "in download mode. (" + (last == null ? "no sync" : last.getMessage()) + ")");
    }

    private void sync() throws IOException {
        byte[] cmd = new byte[36];
        cmd[0] = 0x07; cmd[1] = 0x07; cmd[2] = 0x12; cmd[3] = 0x20;
        for (int i = 0; i < 32; i++) cmd[4 + i] = 0x55;
        Response r = command(ESP_SYNC, cmd, 0, true, 100);
        // Drain the extra sync replies the ROM sends so they don't pollute the next read.
        for (int i = 0; i < 7; i++) {
            try { readPacket(ESP_SYNC, 100); } catch (IOException ignore) { break; }
        }
        if (r == null) throw new IOException("no sync response");
    }

    /** Returns a CHIP_NAME, or null when we can't tell. */
    private String detectChip() {
        try {
            long magic = readReg(CHIP_DETECT_MAGIC_REG_ADDR) & 0xFFFFFFFFL;
            ui.log("Chip magic 0x" + Long.toHexString(magic));
            if (magic == 0xFFF0C101L) return "ESP8266";
            if (magic == 0x00F01D83L) return "ESP32";
            if (magic == 0x000007C6L) return "ESP32-S2";
        } catch (Exception e) {
            ui.log("Magic read failed: " + e.getMessage());
        }
        // Newer chips share/omit magic; ask the ROM for its chip id instead.
        try {
            Response r = command(ESP_GET_SECURITY_INFO, new byte[0], 0, true, 3000);
            if (r != null && r.data != null && r.data.length >= 16) {
                // flags(4) + flash_crypt_cnt(1) + key_purposes(7) → chip_id at offset 12.
                int id = (int) (le32(r.data, 12) & 0xFFFFFFFFL);
                String name = chipById(id);
                if (name != null) return name;
                ui.log("Unknown chip id " + id);
            }
        } catch (Exception e) {
            ui.log("Security-info probe failed: " + e.getMessage());
        }
        return null;
    }

    private static String chipById(int id) {
        switch (id) {
            case 0:  return "ESP32";
            case 2:  return "ESP32-S2";
            case 5:  return "ESP32-C3";
            case 9:  return "ESP32-S3";
            case 12: return "ESP32-C2";
            case 13: return "ESP32-C6";
            case 16: return "ESP32-H2";
            case 18: return "ESP32-P4";
            case 20: return "ESP32-C61";
            case 23: return "ESP32-C5";
            default: return null;
        }
    }

    private boolean isEsp8266() { return "ESP8266".equals(chipName); }

    private static String stubKey(String chip) {
        switch (chip) {
            case "ESP8266":   return "esp8266";
            case "ESP32":     return "esp32";
            case "ESP32-S2":  return "esp32s2";
            case "ESP32-S3":  return "esp32s3";
            case "ESP32-C2":  return "esp32c2";
            case "ESP32-C3":  return "esp32c3";
            case "ESP32-C5":  return "esp32c5";
            case "ESP32-C6":  return "esp32c6";
            case "ESP32-C61": return "esp32c61";
            case "ESP32-H2":  return "esp32h2";
            case "ESP32-P4":  return "esp32p4";
            default:          return null;
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Stub loader
    // ══════════════════════════════════════════════════════════════════════════

    private void runStub() throws Exception {
        String key = stubKey(chipName);
        JSONObject j = key == null ? null : stubs.stub(key);
        if (j == null) {
            ui.log("No stub bundled for " + chipName + "; continuing without it.");
            if (isEsp8266()) flashWriteSize = 0x400;
            return;
        }
        ui.stage("Uploading stub loader…");
        byte[] text = android.util.Base64.decode(j.getString("text"), android.util.Base64.DEFAULT);
        byte[] data = j.optString("data", "").isEmpty() ? null
                : android.util.Base64.decode(j.getString("data"), android.util.Base64.DEFAULT);
        long textStart = j.getLong("text_start");
        long dataStart = j.optLong("data_start", 0);
        long entry     = j.getLong("entry");

        loadStubSegment(text, textStart);
        if (data != null) loadStubSegment(data, dataStart);

        // MEM_END with a non-zero entry jumps into the stub. The ROM acks the
        // command first, THEN the stub greets us with "OHAI" as its own frame —
        // so consume the ack (leniently; some chips jump before acking), then
        // read the greeting.
        byte[] pkt = concat(intLE(0), intLE((int) entry));
        try { checkCommand("leave RAM download mode", ESP_MEM_END, pkt, 0, 0, 500); }
        catch (IOException e) { ui.log("Stub jump ack not seen, continuing…"); }
        byte[] hello = readRawFrame(3000);
        String greeting = hello == null ? "" : new String(hello);
        if (!"OHAI".equals(greeting)) {
            throw new IOException("Stub didn't start (got \"" + greeting + "\"). "
                    + "Try turning High-Speed mode off.");
        }
        isStub = true;
        flashWriteSize = 0x4000;
        ui.log("Stub running.");
    }

    private void loadStubSegment(byte[] seg, long addr) throws IOException {
        int blocks = ceilDiv(seg.length, ESP_RAM_BLOCK);
        byte[] pkt = concat(intLE(seg.length), intLE(blocks), intLE(ESP_RAM_BLOCK), intLE((int) addr));
        checkCommand("enter RAM download mode", ESP_MEM_BEGIN, pkt, 0, 0, 3000);
        for (int seq = 0; seq < blocks; seq++) {
            int from = seq * ESP_RAM_BLOCK;
            int len  = Math.min(ESP_RAM_BLOCK, seg.length - from);
            byte[] block = new byte[len];
            System.arraycopy(seg, from, block, 0, len);
            byte[] hdr = concat(intLE(len), intLE(seq), intLE(0), intLE(0), block);
            checkCommand("write to target RAM", ESP_MEM_DATA, hdr, checksum(block), 0, 3000);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Baud + flash commands
    // ══════════════════════════════════════════════════════════════════════════

    private void changeBaud(int baud) throws IOException {
        ui.stage("Switching to " + baud + " baud…");
        int second = isStub ? ROM_BAUD : 0;
        // The chip acks at the OLD baud, then switches; read the ack (best effort)
        // before we reconfigure the port.
        try { command(ESP_CHANGE_BAUDRATE, concat(intLE(baud), intLE(second)), 0, true, 3000); }
        catch (IOException ignore) { }
        sleep(60);
        try {
            port.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
        } catch (Exception e) { throw new IOException("Couldn't set " + baud + " baud: " + e.getMessage()); }
        flushInput();
        sleep(60);
        ui.log("Baud changed to " + baud + ".");
    }

    private void flashBegin(int size, int offset, int blocks) throws IOException {
        int eraseSize = eraseSize(offset, size);
        byte[] pkt = concat(intLE(eraseSize), intLE(blocks), intLE(flashWriteSize), intLE(offset));
        if (!isStub) pkt = concat(pkt, intLE(0)); // ROM wants an "encrypted?" word
        int timeout = isStub ? 3000 : timeoutPerMb(30000, size);
        checkCommand("enter flash mode", ESP_FLASH_BEGIN, pkt, 0, 0, timeout);
    }

    private void flashDeflBegin(int uncSize, int compSize, int offset, int blocks) throws IOException {
        int eraseBlocks = ceilDiv(uncSize, flashWriteSize);
        int writeSize   = isStub ? uncSize : eraseBlocks * flashWriteSize;
        int timeout     = isStub ? 3000 : timeoutPerMb(30000, writeSize);
        byte[] pkt = concat(intLE(writeSize), intLE(blocks), intLE(flashWriteSize), intLE(offset));
        // ROM (no stub) on the post-ESP32 chips expects an extra "encrypted?" word.
        if (!isStub && !isEsp8266() && !"ESP32".equals(chipName)) pkt = concat(pkt, intLE(0));
        checkCommand("enter compressed flash mode", ESP_FLASH_DEFL_BEGIN, pkt, 0, 0, timeout);
    }

    private void flashBlock(byte[] data, int seq) throws IOException {
        byte[] pkt = concat(intLE(data.length), intLE(seq), intLE(0), intLE(0), data);
        checkCommand("write flash seq " + seq, ESP_FLASH_DATA, pkt, checksum(data), 0, 5000);
    }

    private void flashDeflBlock(byte[] data, int seq) throws IOException {
        byte[] pkt = concat(intLE(data.length), intLE(seq), intLE(0), intLE(0), data);
        checkCommand("write compressed seq " + seq, ESP_FLASH_DEFL_DATA, pkt, checksum(data), 0, 5000);
    }

    private String flashMd5(int addr, int size) throws IOException {
        int respLen = isStub ? 16 : 32;
        byte[] pkt = concat(intLE(addr), intLE(size), intLE(0), intLE(0));
        byte[] res = checkCommand("verify (md5)", ESP_SPI_FLASH_MD5, pkt, 0, respLen,
                timeoutPerMb(8000, size));
        if (isStub) {           // stub returns 16 raw bytes; ROM returns 32 hex chars
            StringBuilder sb = new StringBuilder();
            for (byte b : res) sb.append(String.format("%02x", b & 0xFF));
            return sb.toString();
        }
        return new String(res).toLowerCase();
    }

    /** ESP8266 ROM needs a block-aligned erase size; every other target uses size. */
    private int eraseSize(int offset, int size) {
        if (!isEsp8266() || isStub) return size;
        int sectorsPerBlock = 16, sectorSize = 4096;
        int numSectors   = ceilDiv(size, sectorSize);
        int startSector  = offset / sectorSize;
        int headSectors  = sectorsPerBlock - (startSector % sectorsPerBlock);
        if (numSectors < headSectors) headSectors = numSectors;
        if (numSectors < 2 * headSectors) return (numSectors + 1) / 2 * sectorSize;
        return (numSectors - headSectors) * sectorSize;
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Reset strategies (mirrors esptool-js reset.ts). DTR/RTS are inverted by the
    //  auto-reset transistors on the board, hence the "backwards" logic.
    // ══════════════════════════════════════════════════════════════════════════

    private void resetClassic(int delay) throws IOException {
        port.setDTR(false); port.setRTS(true);
        sleep(100);
        port.setDTR(true);  port.setRTS(false);
        sleep(delay);
        port.setDTR(false);
    }

    private void resetUsbJtag() throws IOException {
        port.setRTS(false); port.setDTR(false); sleep(100);
        port.setDTR(true);  port.setRTS(false); sleep(100);
        port.setRTS(true);  port.setDTR(false);
        port.setRTS(true);  sleep(100);
        port.setRTS(false); port.setDTR(false);
    }

    private void hardReset() {
        try {
            if (usbJtag) { sleep(200); port.setRTS(false); sleep(200); }
            else         { port.setRTS(true); sleep(100); port.setRTS(false); }
        } catch (Exception ignore) { /* best effort — the board usually boots anyway */ }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Command / response plumbing
    // ══════════════════════════════════════════════════════════════════════════

    private static class Response { int val; byte[] data; }

    private long readReg(int addr) throws IOException {
        Response r = command(ESP_READ_REG, intLE(addr), 0, true, 3000);
        if (r == null) throw new IOException("read_reg had no response");
        return r.val & 0xFFFFFFFFL;
    }

    /**
     * Send a command, optionally wait for its matching response. Mirrors esptool's
     * check_command: the last two bytes of the response payload are a status word;
     * a non-zero first status byte means the chip rejected the command.
     */
    private byte[] checkCommand(String what, int op, byte[] data, int chk,
                                int respDataLen, int timeout) throws IOException {
        Response r = command(op, data, chk, true, timeout);
        if (r == null || r.data == null) throw new IOException("Failed to " + what + " (no response).");
        int statusLen = 2;
        if (r.data.length < respDataLen + statusLen)
            throw new IOException("Failed to " + what + " (short response).");
        int status = r.data[respDataLen] & 0xFF;
        if (status != 0) {
            int err = r.data.length > respDataLen + 1 ? r.data[respDataLen + 1] & 0xFF : 0;
            throw new IOException("Failed to " + what + " (status " + status + "/" + err + ").");
        }
        if (respDataLen > 0) {
            byte[] out = new byte[respDataLen];
            System.arraycopy(r.data, 0, out, 0, respDataLen);
            return out;
        }
        return r.data;
    }

    private Response command(int op, byte[] data, int chk, boolean wait, int timeout) throws IOException {
        byte[] pkt = new byte[8 + data.length];
        pkt[0] = 0x00;
        pkt[1] = (byte) op;
        pkt[2] = (byte) (data.length & 0xFF);
        pkt[3] = (byte) ((data.length >> 8) & 0xFF);
        pkt[4] = (byte) (chk & 0xFF);
        pkt[5] = (byte) ((chk >> 8) & 0xFF);
        pkt[6] = (byte) ((chk >> 16) & 0xFF);
        pkt[7] = (byte) ((chk >> 24) & 0xFF);
        System.arraycopy(data, 0, pkt, 8, data.length);
        writeFrame(pkt);
        if (!wait) return null;
        return readPacket(op, timeout);
    }

    /** Read SLIP frames until one is a valid response to {@code op}. */
    private Response readPacket(int op, int timeout) throws IOException {
        for (int i = 0; i < 100; i++) {
            byte[] p = readRawFrame(timeout);
            if (p == null || p.length < 8) continue;
            if ((p[0] & 0xFF) != 1) continue;           // 1 = response direction
            int opRet = p[1] & 0xFF;
            if (op != -1 && opRet != op) continue;
            Response r = new Response();
            r.val  = (int) le32(p, 4);
            r.data = new byte[p.length - 8];
            System.arraycopy(p, 8, r.data, 0, r.data.length);
            return r;
        }
        throw new IOException("no valid response");
    }

    // ── SLIP framing over the raw port ────────────────────────────────────────

    private void writeFrame(byte[] data) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream(data.length + 8);
        o.write(SLIP_END);
        for (byte b : data) {
            int v = b & 0xFF;
            if (v == SLIP_END)      { o.write(SLIP_ESC); o.write(SLIP_ESC_END); }
            else if (v == SLIP_ESC) { o.write(SLIP_ESC); o.write(SLIP_ESC_ESC); }
            else                    { o.write(v); }
        }
        o.write(SLIP_END);
        byte[] out = o.toByteArray();
        port.write(out, 3000);
    }

    /** Decode one SLIP frame (the bytes between two 0xC0 markers). */
    private byte[] readRawFrame(int timeout) throws IOException {
        long deadline = System.currentTimeMillis() + timeout;
        ByteArrayOutputStream pkt = null;
        boolean escaping = false;
        while (true) {
            if (cancel) throw new IOException("Cancelled.");
            if (in.isEmpty()) {
                int n = port.read(readChunk, 200);
                for (int i = 0; i < n; i++) in.addLast(readChunk[i] & 0xFF);
                if (n == 0) {
                    if (System.currentTimeMillis() > deadline)
                        throw new IOException(pkt == null ? "no serial data" : "incomplete packet");
                    continue;
                }
            }
            int b = in.pollFirst();
            if (pkt == null) {
                if (b == SLIP_END) pkt = new ByteArrayOutputStream();
                // else: noise before a frame — ignore it.
            } else if (escaping) {
                escaping = false;
                if (b == SLIP_ESC_END) pkt.write(SLIP_END);
                else if (b == SLIP_ESC_ESC) pkt.write(SLIP_ESC);
                else throw new IOException("bad SLIP escape 0x" + Integer.toHexString(b));
            } else if (b == SLIP_ESC) {
                escaping = true;
            } else if (b == SLIP_END) {
                if (pkt.size() == 0) { pkt = new ByteArrayOutputStream(); continue; } // C0 C0 → skip
                return pkt.toByteArray();
            } else {
                pkt.write(b);
            }
        }
    }

    private void flushInput() {
        in.clear();
        // Signature is purgeHwBuffers(purgeWriteBuffers, purgeReadBuffers) — we want
        // the read side cleared of any stale bytes before the next command.
        try { port.purgeHwBuffers(false, true); } catch (Exception ignore) { }
        // Drain anything already sitting in the driver.
        try {
            for (int i = 0; i < 4; i++) {
                int n = port.read(readChunk, 20);
                if (n <= 0) break;
            }
        } catch (Exception ignore) { }
    }

    // ── Small helpers ─────────────────────────────────────────────────────────

    private static int checksum(byte[] data) {
        int state = ESP_CHECKSUM_MAGIC;
        for (byte b : data) state ^= (b & 0xFF);
        return state & 0xFF;
    }

    private byte[] deflate(byte[] input) {
        Deflater d = new Deflater(Deflater.BEST_COMPRESSION);   // zlib (with header), like esptool
        d.setInput(input);
        d.finish();
        ByteArrayOutputStream o = new ByteArrayOutputStream(input.length / 2 + 64);
        byte[] buf = new byte[16384];
        while (!d.finished()) { int n = d.deflate(buf); o.write(buf, 0, n); }
        d.end();
        return o.toByteArray();
    }

    private static byte[] pad4(byte[] in) {
        int rem = in.length % 4;
        if (rem == 0) return in;
        byte[] out = new byte[in.length + (4 - rem)];
        System.arraycopy(in, 0, out, 0, in.length);
        for (int i = in.length; i < out.length; i++) out[i] = (byte) 0xFF;   // erased-flash value
        return out;
    }

    private static String md5Hex(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] h = md.digest(data);
        StringBuilder sb = new StringBuilder();
        for (byte b : h) sb.append(String.format("%02x", b & 0xFF));
        return sb.toString();
    }

    private static int timeoutPerMb(int perMb, int sizeBytes) {
        int t = (int) ((long) perMb * sizeBytes / 1_000_000L);
        return Math.max(t, 3000);
    }

    private static int ceilDiv(int a, int b) { return (a + b - 1) / b; }

    private static byte[] intLE(int v) {
        return new byte[]{ (byte) v, (byte) (v >> 8), (byte) (v >> 16), (byte) (v >> 24) };
    }

    private static long le32(byte[] b, int off) {
        return (b[off] & 0xFFL) | ((b[off + 1] & 0xFFL) << 8)
                | ((b[off + 2] & 0xFFL) << 16) | ((b[off + 3] & 0xFFL) << 24);
    }

    private static byte[] concat(byte[]... parts) {
        int len = 0; for (byte[] p : parts) len += p.length;
        byte[] out = new byte[len];
        int i = 0; for (byte[] p : parts) { System.arraycopy(p, 0, out, i, p.length); i += p.length; }
        return out;
    }

    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignore) { } }
}
