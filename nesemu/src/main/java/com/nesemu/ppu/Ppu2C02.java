package com.nesemu.ppu;

import com.nesemu.cpu.CPU;
import com.nesemu.mapper.Mapper;
import com.nesemu.mapper.Mapper.MirrorType;

/**
 * Minimal 2C02 PPU skeleton: implements core registers and a basic
 * cycle/scanline counter.
 *
 * Exposed behaviour right now:
 * - write/read $2000-$2007 via Bus (Bus will call readRegister/writeRegister
 * when integrated)
 * - status ($2002) vblank bit set at scanline 241, cleared at pre-render (-1)
 * - simple VRAM address latch (PPUADDR) and increment logic (PPUDATA increments
 * by 1 or 32)
 * - internal buffer for PPUDATA read delay emulation (return buffered & fill
 * new)
 *
 * Missing (future): pattern fetch pipeline, nametable/palette storage,
 * mirroring, sprite system.
 */
public class Ppu2C02 implements PPU {

    // Optional CPU callback for NMI (set by Bus/emulator)
    private CPU cpu;
    // Optional mapper reference (for CHR access + mirroring metadata)
    private Mapper mapper;

    public void attachCPU(CPU cpu) {
        this.cpu = cpu;
    }

    public void attachMapper(Mapper mapper) {
        this.mapper = mapper;
    }

    // Registers
    private int regCTRL; // $2000
    private int regMASK; // $2001
    private int regSTATUS; // $2002
    private int oamAddr; // $2003
    // $2004 OAMDATA (not implemented yet)
    // $2005 PPUSCROLL (x,y latch)
    // $2006 PPUADDR (VRAM address latch)
    // $2007 PPUDATA

    // VRAM address latch / toggle
    private boolean addrLatchHigh = true; // true -> next write is high byte
    private int vramAddress; // current VRAM address
    private int tempAddress; // temp (t) during address/scroll sequence
    private int fineX; // fine X scroll (0..7) from first scroll write

    // Buffered read (PPUDATA) behavior
    private int readBuffer; // internal buffer

    // Timing counters
    private int cycle; // 0..340
    private int scanline; // -1 pre-render, 0..239 visible, 240 post, 241..260 vblank
    private long frame; // frame counter

    // Single-shot NMI latch (prevent multiple nmi() calls inside same vblank entry)
    private boolean nmiFiredThisVblank = false;

    // Optional test hook: a callback invoked whenever an NMI would be signalled
    private Runnable nmiCallback;

    // Frame buffer storing final 32-bit ARGB color and parallel index buffer
    private final int[] frameBuffer = new int[256 * 240]; // ARGB color
    private final int[] frameIndexBuffer = new int[256 * 240]; // raw palette index (0..15 background)

    // Debug flag (can be toggled via system property -Dnes.ppu.debug=true)
    private static final boolean DEBUG = Boolean.getBoolean("nes.ppu.debug");
    // Extended attribute writes log
    private static final boolean LOG_ATTR = Boolean.getBoolean("nes.ppu.logAttr");
    // If true, don't cap register write logs
    private static final boolean LOG_EXTENDED = Boolean.getBoolean("nes.ppu.logExtended");

    // Palette subsystem
    private final Palette palette = new Palette();

    // --- Background rendering simplified ---
    // Pattern tables + nametables (2x1KB) temporary internal storage
    private final byte[] patternTables = new byte[0x2000]; // CHR ROM/RAM placeholder
    private final byte[] nameTables = new byte[0x0800]; // two nametables (no mirroring control yet)

    // Shift registers (16-bit each like real PPU)
    private int patternLowShift, patternHighShift;
    private int attributeLowShift, attributeHighShift; // replicated attribute bits

    // Latches
    private int ntLatch, atLatch, patternLowLatch, patternHighLatch;
    // Raw background palette index per pixel (0..15) stored for tests (pattern 0 ->
    // 0)
    // Already used internally in produceBackgroundPixel; accessor added below.
    // Removed hack fields (firstTileReady, scanlinePixelCounter) in favour of
    // direct cycle-based x computation

    public void setNmiCallback(Runnable cb) {
        this.nmiCallback = cb;
    }

    @Override
    public void reset() {
        regCTRL = regMASK = 0;
        regSTATUS = 0;
        oamAddr = 0;
        addrLatchHigh = true;
        vramAddress = tempAddress = 0;
        fineX = 0;
        readBuffer = 0;
        cycle = 0;
        scanline = -1; // pre-render
        frame = 0;
        nmiFiredThisVblank = false;
        // Background fetch / rendering state
        patternLowShift = patternHighShift = 0;
        attributeLowShift = attributeHighShift = 0;
        ntLatch = atLatch = patternLowLatch = patternHighLatch = 0;
        // firstTileReady / scanlinePixelCounter removed
        for (int i = 0; i < frameBuffer.length; i++) {
            frameBuffer[i] = 0xFF000000;
            frameIndexBuffer[i] = 0;
        }
    }

    @Override
    public void clock() {
        // Advance one PPU cycle (3x CPU speed in real hardware, handled externally).
        cycle++;
        if (cycle > 340) {
            cycle = 0;
            scanline++;
            if (scanline > 260) {
                scanline = -1; // wrap to pre-render
                frame++;
                if (DEBUG) {
                    // Dump first 32 background indices of scanline 0 for debugging
                    StringBuilder sb = new StringBuilder();
                    sb.append("[PPU] First scanline indices: ");
                    for (int i = 0; i < 32; i++) {
                        sb.append(String.format("%02x ", frameIndexBuffer[i] & 0xFF));
                    }
                    System.out.println(sb.toString());
                }
            }
            // New visible scanline: reset pixel counters
            // no per-scanline pixel counter reset needed now
        }

        // (No priming hack) – rely on 8‑cycle pipeline latency.

        // Enter vblank at scanline 241, cycle 1 (NES spec; some docs cite cycle 0)
        if (scanline == 241 && cycle == 1) {
            regSTATUS |= 0x80; // set VBlank flag
            nmiFiredThisVblank = false; // allow a new NMI if enabled
            if (DEBUG)
                System.out.printf("[PPU] VBLANK SET frame=%d scan=%d cyc=%d\n", frame, scanline, cycle);
            if ((regCTRL & 0x80) != 0) {
                fireNmi();
            }
        }
        // Pre-render line: clear vblank at cycle 1
        else if (scanline == -1 && cycle == 1) {
            regSTATUS &= ~0x80; // clear VBlank
            // Clear sprite 0 hit (bit 6) & overflow (bit 5) placeholder
            regSTATUS &= 0x1F;
            nmiFiredThisVblank = true; // block until next vblank start
            if (DEBUG)
                System.out.printf("[PPU] VBLANK CLEAR frame=%d scan=%d cyc=%d\n", frame, scanline, cycle);
        }
        // Mid-vblank: If NMI enable toggled on after start, spec allows late NMI
        // (edge). Simplify: fire once if enabled.
        else if (isInVBlank() && (regCTRL & 0x80) != 0 && !nmiFiredThisVblank) {
            fireNmi();
        }

        // --- Rendering address logic (loopy v/t/x) ---
        // Only active when background or sprite rendering enabled (MASK bits 3 or 4)
        if (renderingEnabled()) {
            // Visible or pre-render scanlines only
            if (isVisibleScanline() || isPreRender()) {
                // Background pipeline per-cycle operations (simplified subset)
                // Shift at start of each visible cycle before sampling pixel
                // Fetch pipeline for this cycle
                backgroundPipeline();
                // Produce pixel using current shift register state (before shifting)
                if (isVisibleScanline() && cycle >= 1 && cycle <= 256) {
                    produceBackgroundPixel();
                    // Shift after sampling (hardware shifts once per pixel after use)
                    shiftBackgroundRegisters();
                }
                // Increment coarse X at cycles 8,16,...,256 and 328,336 (simplified: every 8
                // cycles in the fetch regions)
                if (((cycle >= 1 && cycle <= 256) || (cycle >= 321 && cycle <= 336)) && (cycle & 0x7) == 0) {
                    incrementCoarseX();
                }
                // At cycle 256 increment Y (vertical position)
                if (cycle == 256) {
                    incrementY();
                }
                // At cycle 257 copy horizontal bits from t to v
                if (cycle == 257) {
                    copyHorizontalBits();
                }
                // During pre-render line cycles 280-304 copy vertical bits from t to v
                if (isPreRender() && cycle >= 280 && cycle <= 304) {
                    copyVerticalBits();
                }
            }
        }
    }

    private void fireNmi() {
        nmiFiredThisVblank = true;
        if (cpu != null)
            cpu.nmi();
        if (nmiCallback != null)
            nmiCallback.run();
    }

    // --- Register access (to be wired by Bus) ---
    public int readRegister(int reg) {
        switch (reg & 0x7) {
            case 2: { // $2002 PPUSTATUS
                int value = regSTATUS;
                // Reading status clears vblank bit and address latch
                regSTATUS &= ~0x80;
                addrLatchHigh = true;
                return value;
            }
            case 4: { // OAMDATA (stub)
                return 0; // future: read OAM[oamAddr]
            }
            case 7: { // PPUDATA
                // Buffered read behaviour: return previous buffer, then fill
                int value = readBuffer;
                readBuffer = ppuMemoryRead(vramAddress) & 0xFF;
                incrementVram();
                return value;
            }
            default:
                return 0; // unimplemented / write-only
        }
    }

    public void writeRegister(int reg, int value) {
        value &= 0xFF;
        switch (reg & 0x7) {
            case 0: // $2000 PPUCTRL
                regCTRL = value;
                tempAddress = (tempAddress & 0x73FF) | ((value & 0x03) << 10); // nametable bits into t
                logEarlyWrite(reg, value);
                break;
            case 1: // $2001 PPUMASK
                regMASK = value;
                logEarlyWrite(reg, value);
                break; // removed mid-scanline priming hack
            case 2: // STATUS is read-only
                break;
            case 3: // OAMADDR
                oamAddr = value & 0xFF;
                logEarlyWrite(reg, value);
                break;
            case 4: // OAMDATA (stub)
                // future: write to OAM[oamAddr++]
                oamAddr = (oamAddr + 1) & 0xFF;
                logEarlyWrite(reg, value);
                break;
            case 5: // PPUSCROLL
                if (addrLatchHigh) {
                    fineX = value & 0x07; // coarse X goes into tempAddress bits 0-4; fine X separate
                    int coarseX = (value >> 3) & 0x1F;
                    tempAddress = (tempAddress & 0x7FE0) | coarseX;
                    addrLatchHigh = false;
                } else {
                    int coarseY = (value >> 3) & 0x1F;
                    int fineY = value & 0x07;
                    tempAddress = (tempAddress & 0x0C1F) | (coarseY << 5) | (fineY << 12);
                    addrLatchHigh = true;
                }
                logEarlyWrite(reg, value);
                break;
            case 6: // PPUADDR
                if (addrLatchHigh) {
                    tempAddress = (tempAddress & 0x00FF) | ((value & 0x3F) << 8); // high 6 bits (mask 0x3F)
                    addrLatchHigh = false;
                } else {
                    tempAddress = (tempAddress & 0x7F00) | value;
                    vramAddress = tempAddress;
                    addrLatchHigh = true;
                }
                logEarlyWrite(reg, value);
                break;
            case 7: // PPUDATA
                ppuMemoryWrite(vramAddress, value);
                incrementVram();
                logEarlyWrite(reg, value);
                break;
        }
    }

    // --- Early register write logging (first few only to avoid spam) ---
    private static final int EARLY_WRITE_LOG_LIMIT = 40; // cap (unless LOG_EXTENDED)
    private int earlyWriteLogCount = 0;

    private void logEarlyWrite(int reg, int val) {
        if (LOG_EXTENDED || earlyWriteLogCount < EARLY_WRITE_LOG_LIMIT) {
            System.out.printf("[PPU WR %02X] val=%02X frame=%d scan=%d cyc=%d v=%04X t=%04X fineX=%d%n",
                    0x2000 + (reg & 0x7), val & 0xFF, frame, scanline, cycle, vramAddress & 0x7FFF,
                    tempAddress & 0x7FFF, fineX);
            earlyWriteLogCount++;
        }
    }

    private void incrementVram() {
        int increment = ((regCTRL & 0x04) != 0) ? 32 : 1;
        vramAddress = (vramAddress + increment) & 0x7FFF; // 15-bit
    }

    private boolean renderingEnabled() {
        return (regMASK & 0x18) != 0; // background or sprites
    }

    // --- Loopy address helpers ---
    private void incrementCoarseX() {
        if ((vramAddress & 0x001F) == 31) { // coarse X == 31
            vramAddress &= ~0x001F; // coarse X = 0
            vramAddress ^= 0x0400; // switch horizontal nametable
        } else {
            vramAddress++;
        }
    }

    private void incrementY() {
        int fineY = (vramAddress >> 12) & 0x7;
        if (fineY < 7) {
            fineY++;
            vramAddress = (vramAddress & 0x8FFF) | (fineY << 12);
        } else {
            fineY = 0;
            vramAddress &= 0x8FFF; // clear fine Y
            int coarseY = (vramAddress >> 5) & 0x1F;
            if (coarseY == 29) {
                coarseY = 0;
                vramAddress ^= 0x0800; // switch vertical nametable
            } else if (coarseY == 31) {
                coarseY = 0; // 30 & 31 wrap without toggling
            } else {
                coarseY++;
            }
            vramAddress = (vramAddress & ~0x03E0) | (coarseY << 5);
        }
    }

    private void copyHorizontalBits() {
        // Copy coarse X (bits 0-4) and horizontal nametable (bit 10) from t to v
        vramAddress = (vramAddress & ~0x041F) | (tempAddress & 0x041F);
    }

    private void copyVerticalBits() {
        // Copy fine Y (12-14), coarse Y (5-9) and vertical nametable (bit 11)
        vramAddress = (vramAddress & ~0x7BE0) | (tempAddress & 0x7BE0);
    }

    private void backgroundPipeline() {
        // Only execute during visible cycles 1-256 or prefetch cycles 321-336 on
        // visible/pre-render lines
        boolean fetchRegion = (isVisibleScanline() && cycle >= 1 && cycle <= 256)
                || (cycle >= 321 && cycle <= 336 && isVisibleScanline())
                || (isPreRender() && ((cycle >= 321 && cycle <= 336) || (cycle >= 1 && cycle <= 256)));
        if (!fetchRegion)
            return;
        int phase = cycle & 0x7; // 8-cycle tile fetch phase (1,3,5,7 fetch; 0 reload)
        switch (phase) {
            case 1: // Fetch nametable byte
                ntLatch = ppuMemoryRead(0x2000 | (vramAddress & 0x0FFF));
                break;
            case 3: { // Fetch attribute byte
                int v = vramAddress;
                int coarseX = v & 0x1F;
                int coarseY = (v >> 5) & 0x1F;
                int attributeAddr = 0x23C0 | (v & 0x0C00) | ((coarseY >> 2) << 3) | (coarseX >> 2);
                atLatch = ppuMemoryRead(attributeAddr);
                break;
            }
            case 5: { // Pattern low
                int fineY = (vramAddress >> 12) & 0x07;
                // PPUCTRL bit 3 (0x08) selects background pattern table (0: $0000, 1: $1000)
                int base = ((regCTRL & 0x08) != 0 ? 0x1000 : 0x0000) + (ntLatch * 16) + fineY;
                patternLowLatch = ppuMemoryRead(base);
                break;
            }
            case 7: { // Pattern high
                int fineY = (vramAddress >> 12) & 0x07;
                // Use same (corrected) background pattern table selection (bit 3)
                int base = ((regCTRL & 0x08) != 0 ? 0x1000 : 0x0000) + (ntLatch * 16) + fineY + 8;
                patternHighLatch = ppuMemoryRead(base);
                break;
            }
            case 0: // Reload: insert new tile bytes into low 8 bits (faithful orientation)
                loadShiftRegisters();
                break;
        }
    }

    private void produceBackgroundPixel() {
        if ((regMASK & 0x08) == 0)
            return; // background disabled
        // Cycle->pixel mapping with 8-cycle fetch latency: first visible pixel (x=0) at
        // cycle 9
        int x = cycle - 9; // cycle 9 -> pixel 0
        if (x < 0 || x >= 256 || !isVisibleScanline())
            return;
        if ((regMASK & 0x02) == 0 && x < 8) { // left column clip
            frameBuffer[scanline * 256 + x] = 0;
            frameIndexBuffer[scanline * 256 + x] = 0;
            return;
        }
        int bitIndex = 15 - (fineX & 0x7); // use fine X to select bit from 16-bit shift registers
        int mask = 1 << bitIndex;
        int bit0 = (patternLowShift & mask) != 0 ? 1 : 0;
        int bit1 = (patternHighShift & mask) != 0 ? 1 : 0;
        int attrLow = (attributeLowShift & mask) != 0 ? 1 : 0;
        int attrHigh = (attributeHighShift & mask) != 0 ? 1 : 0;
        int pattern = (bit1 << 1) | bit0;
        int attr = (attrHigh << 1) | attrLow;
        int paletteIndex = (attr << 2) | pattern; // 0..15
        int store = (pattern == 0) ? 0 : paletteIndex; // universal bg for transparent pattern
        frameIndexBuffer[scanline * 256 + x] = store;
        int colorIndex = palette.read(0x3F00 + ((pattern == 0) ? 0 : paletteIndex));
        frameBuffer[scanline * 256 + x] = palette.getArgb(colorIndex, regMASK);
    }

    private void loadShiftRegisters() {
        int pl = patternLowLatch & 0xFF;
        int ph = patternHighLatch & 0xFF;
        // Insert new tile bytes into LOW 8 bits; existing bits keep shifting toward
        // bit15
        patternLowShift = (patternLowShift & 0xFF00) | pl;
        patternHighShift = (patternHighShift & 0xFF00) | ph;
        int coarseX = vramAddress & 0x1F;
        int coarseY = (vramAddress >> 5) & 0x1F;
        int quadSelector = ((coarseY & 0x02) << 1) | (coarseX & 0x02); // 0,2,4,6
        int shift;
        switch (quadSelector) {
            case 0:
                shift = 0;
                break; // TL
            case 2:
                shift = 2;
                break; // TR
            case 4:
                shift = 4;
                break; // BL
            case 6:
                shift = 6;
                break; // BR
            default:
                shift = 0;
                break;
        }
        int attributeBits = (atLatch >> shift) & 0x03;
        int lowBit = attributeBits & 0x01;
        int highBit = (attributeBits >> 1) & 0x01;
        // Replicate attribute bits across both bytes so bit15 (first pixel after
        // reload)
        // reflects current tile's palette selection (avoids leaking previous tile's
        // high byte).
        attributeLowShift = (lowBit != 0 ? 0xFFFF : 0x0000);
        attributeHighShift = (highBit != 0 ? 0xFFFF : 0x0000);
    }

    private void shiftBackgroundRegisters() {
        patternLowShift = (patternLowShift << 1) & 0xFFFF;
        patternHighShift = (patternHighShift << 1) & 0xFFFF;
        attributeLowShift = (attributeLowShift << 1) & 0xFFFF;
        attributeHighShift = (attributeHighShift << 1) & 0xFFFF;
    }

    // (No priming helper in accurate pipeline mode)

    // priming hack removed – rely on pre-render line (if enabled early) or natural
    // 8-cycle pipeline delay

    // Placeholder memory space for pattern/nametables/palette until Bus integration
    // fleshed out.
    private int ppuMemoryRead(int addr) {
        addr &= 0x3FFF;
        if (addr < 0x2000) { // pattern tables
            if (mapper != null) {
                return mapper.ppuRead(addr) & 0xFF;
            }
            return patternTables[addr] & 0xFF; // fallback (tests / bootstrap)
        } else if (addr < 0x3F00) { // nametables (0x2000-0x2FFF)
            int nt = (addr - 0x2000) & 0x0FFF;
            int index = nt & 0x03FF; // 1KB region within a logical table
            int table = (nt >> 10) & 0x03; // 0..3 logical tables before mirroring
            int physical = table; // map to 0 or 1 based on mirroring
            MirrorType mt = (mapper != null) ? mapper.getMirrorType() : MirrorType.VERTICAL; // default vertical
            if (mt == MirrorType.VERTICAL) {
                physical = table & 0x01; // 0,1,0,1
            } else { // HORIZONTAL
                physical = (table >> 1); // 0,0,1,1
            }
            return nameTables[(physical * 0x0400) + index] & 0xFF;
        } else if (addr < 0x4000) {
            // Palette RAM $3F00-$3F1F mirrored every 32 bytes up to 0x3FFF
            return palette.read(addr);
        }
        return 0;
    }

    private void ppuMemoryWrite(int addr, int value) {
        addr &= 0x3FFF;
        value &= 0xFF;
        if (addr < 0x2000) {
            if (mapper != null) {
                mapper.ppuWrite(addr, value);
            } else {
                patternTables[addr] = (byte) value; // CHR RAM case
            }
        } else if (addr < 0x3F00) {
            int nt = (addr - 0x2000) & 0x0FFF;
            int index = nt & 0x03FF;
            int table = (nt >> 10) & 0x03; // logical
            int physical;
            MirrorType mt = (mapper != null) ? mapper.getMirrorType() : MirrorType.VERTICAL;
            if (mt == MirrorType.VERTICAL) {
                physical = table & 0x01; // 0,1,0,1
            } else {
                physical = (table >> 1); // 0,0,1,1
            }
            nameTables[(physical * 0x0400) + index] = (byte) value;
            // Attribute table logging ($23C0-$23FF etc.) after mirroring mapping
            if (LOG_ATTR) {
                // Reconstruct base logical address for determining attribute section
                int logicalBase = 0x2000 | nt; // before mirroring
                int logicalInTable = logicalBase & 0x03FF; // 0..0x3FF inside logically selected table
                if ((logicalInTable & 0x03C0) == 0x03C0) { // attribute quadrant area (top 64 bytes of table)
                    System.out.printf("[PPU ATTR WR] addr=%04X val=%02X frame=%d scan=%d cyc=%d table=%d phys=%d%n",
                            logicalBase, value & 0xFF, frame, scanline, cycle, table, physical);
                }
            }
        } else if (addr < 0x4000) {
            palette.write(addr, value);
        }
    }

    // Accessors for future tests/debug
    public int getScanline() {
        return scanline;
    }

    public int getCycle() {
        return cycle;
    }

    public boolean isInVBlank() {
        return (regSTATUS & 0x80) != 0;
    }

    public boolean isVisibleScanline() {
        return scanline >= 0 && scanline <= 239;
    }

    public boolean isPreRender() {
        return scanline == -1;
    }

    public boolean isPostRender() {
        return scanline == 240;
    }

    public long getFrame() {
        return frame;
    }

    // --- Testing / debug accessors ---
    public int getBackgroundIndex(int x, int y) {
        if (x < 0 || x >= 256 || y < 0 || y >= 240)
            return 0;
        return frameIndexBuffer[y * 256 + x] & 0xFF;
    }

    public int[] getBackgroundIndexBufferCopy() {
        int[] copy = new int[frameIndexBuffer.length];
        System.arraycopy(frameIndexBuffer, 0, copy, 0, frameIndexBuffer.length);
        return copy;
    }

    // Testing accessors (safe read-only)
    public int getVramAddress() {
        return vramAddress & 0x7FFF;
    }

    public int getTempAddress() {
        return tempAddress & 0x7FFF;
    }

    public int getFineX() {
        return fineX & 0x7;
    }

    // TEST HELPERS (package-private)
    void setVramAddressForTest(int v) {
        this.vramAddress = v & 0x7FFF;
    }

    void setTempAddressForTest(int t) {
        this.tempAddress = t & 0x7FFF;
    }

    // Background test helpers
    void pokeNameTable(int offset, int value) {
        if (offset >= 0 && offset < nameTables.length)
            nameTables[offset] = (byte) value;
    }

    void pokePattern(int addr, int value) {
        if (addr >= 0 && addr < patternTables.length)
            patternTables[addr] = (byte) value;
    }

    int getPatternLowShift() {
        return patternLowShift & 0xFFFF;
    }

    int getPatternHighShift() {
        return patternHighShift & 0xFFFF;
    }

    int getAttributeLowShift() {
        return attributeLowShift & 0xFFFF;
    }

    int getAttributeHighShift() {
        return attributeHighShift & 0xFFFF;
    }

    int getNtLatch() {
        return ntLatch & 0xFF;
    }

    int getAtLatch() {
        return atLatch & 0xFF;
    }

    int getPixel(int x, int y) {
        if (x < 0 || x >= 256 || y < 0 || y >= 240)
            return 0;
        return frameIndexBuffer[y * 256 + x];
    }

    int[] getFrameBufferRef() { // returns ARGB buffer
        return frameBuffer;
    }

    /** Public accessor for rendering layer (returns direct reference). */
    public int[] getFrameBuffer() {
        return frameBuffer;
    }

    // loadChr removed: pattern fetches now always routed via mapper when attached.

    // TEST HELPERS for palette
    void pokePalette(int addr, int value) {
        palette.write(addr, value);
    }

    int readPalette(int addr) {
        return palette.read(addr);
    }

    // Raw status for deeper debug if needed
    public int getStatusRegister() {
        return regSTATUS & 0xFF;
    }
}
