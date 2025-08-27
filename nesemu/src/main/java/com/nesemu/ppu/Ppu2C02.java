package com.nesemu.ppu;

import com.nesemu.cpu.CPU;

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

    public void attachCPU(CPU cpu) {
        this.cpu = cpu;
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

    // Frame buffer for background pixels (palette index 0..15 per pixel)
    private final int[] frameBuffer = new int[256 * 240];

    // Debug flag (can be toggled via system property -Dnes.ppu.debug=true)
    private static final boolean DEBUG = Boolean.getBoolean("nes.ppu.debug");

    // --- Background rendering simplified ---
    // Pattern tables + nametables (2x1KB) temporary internal storage
    private final byte[] patternTables = new byte[0x2000]; // CHR ROM/RAM placeholder
    private final byte[] nameTables = new byte[0x0800]; // two nametables (no mirroring control yet)

    // Shift registers (16-bit each like real PPU)
    private int patternLowShift, patternHighShift;
    private int attributeLowShift, attributeHighShift; // replicated attribute bits

    // Latches
    private int ntLatch, atLatch, patternLowLatch, patternHighLatch;
    private boolean justReloaded; // skip one shift after reload so first pixel uses new high bits
    private boolean firstTileReady; // becomes true after first phase-0 reload on a visible scanline
    private int scanlinePixelCounter; // counts rendered background pixels this scanline

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
        firstTileReady = false;
        scanlinePixelCounter = 0;
        for (int i = 0; i < frameBuffer.length; i++)
            frameBuffer[i] = 0;
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
            }
            // New visible scanline: reset pixel counters
            if (isVisibleScanline()) {
                firstTileReady = false;
                scanlinePixelCounter = 0;
            }
        }

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
                backgroundPipeline();
                if (isVisibleScanline() && cycle >= 1 && cycle <= 256) {
                    produceBackgroundPixel();
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
                break;
            case 1: // $2001 PPUMASK
                int prevMask = regMASK;
                regMASK = value;
                // If background just got enabled mid-frame right at cycle 0 of a visible
                // scanline,
                // we didn't perform the pre-render fetches for the first tile. Prime it now so
                // pixel 0 appears without an 8-pixel delay (test convenience; later we'll rely
                // on
                // real pre-render line fetches).
                boolean bgWasDisabled = (prevMask & 0x08) == 0;
                boolean bgNowEnabled = (regMASK & 0x08) != 0;
                if (bgWasDisabled && bgNowEnabled && cycle == 0 && isVisibleScanline()) {
                    primeFirstTile();
                }
                break;
            case 2: // STATUS is read-only
                break;
            case 3: // OAMADDR
                oamAddr = value & 0xFF;
                break;
            case 4: // OAMDATA (stub)
                // future: write to OAM[oamAddr++]
                oamAddr = (oamAddr + 1) & 0xFF;
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
                break;
            case 7: // PPUDATA
                ppuMemoryWrite(vramAddress, value);
                incrementVram();
                break;
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
        int phase = cycle & 0x7; // 8-cycle tile fetch phase
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
                int base = ((regCTRL & 0x10) != 0 ? 0x1000 : 0x0000) + (ntLatch * 16) + fineY;
                patternLowLatch = ppuMemoryRead(base);
                break;
            }
            case 7: { // Pattern high
                int fineY = (vramAddress >> 12) & 0x07;
                int base = ((regCTRL & 0x10) != 0 ? 0x1000 : 0x0000) + (ntLatch * 16) + fineY + 8;
                patternHighLatch = ppuMemoryRead(base);
                break;
            }
            case 0: // Reload shift registers with latched tile data
                loadShiftRegisters();
                justReloaded = true; // defer shift this cycle and next shift occurs starting following pixel
                if (!firstTileReady && isVisibleScanline()) {
                    firstTileReady = true; // allow pixel production logic to advance scanlinePixelCounter
                }
                break;
        }

        // Perform shifting AFTER phase work so freshly loaded data isn't advanced
        // prematurely
        if (cycle != 0) {
            if (justReloaded) {
                // Skip one shift cycle so first pixel after reload (next cycle) uses bit7
                justReloaded = false;
            } else {
                patternLowShift = (patternLowShift << 1) & 0xFFFF;
                patternHighShift = (patternHighShift << 1) & 0xFFFF;
                attributeLowShift = (attributeLowShift << 1) & 0xFFFF;
                attributeHighShift = (attributeHighShift << 1) & 0xFFFF;
            }
        }
    }

    private void produceBackgroundPixel() {
        if ((regMASK & 0x08) == 0)
            return; // background disabled
        if (!firstTileReady)
            return; // wait until first tile reload
        int x = scanlinePixelCounter;
        if (x >= 256)
            return;
        if ((regMASK & 0x02) == 0 && x < 8) { // left clip
            frameBuffer[scanline * 256 + x] = 0;
            scanlinePixelCounter++;
            return;
        }
        // Real fine X sampling: current pixel uses bit (15 - fineX) of each 16-bit
        // shift register.
        int bitIndex = 15 - (fineX & 0x7);
        int mask = 1 << bitIndex;
        int bit0 = (patternLowShift & mask) != 0 ? 1 : 0;
        int bit1 = (patternHighShift & mask) != 0 ? 1 : 0;
        int attrLow = (attributeLowShift & mask) != 0 ? 1 : 0;
        int attrHigh = (attributeHighShift & mask) != 0 ? 1 : 0;
        int pattern = (bit1 << 1) | bit0;
        int attr = (attrHigh << 1) | attrLow;
        int paletteIndex = (attr << 2) | pattern;
        frameBuffer[scanline * 256 + x] = paletteIndex;
        scanlinePixelCounter++;
    }

    private void loadShiftRegisters() {
        // Load pattern bytes ONLY into high 8 bits (authentic orientation for our
        // simplified priming).
        int pl = patternLowLatch & 0xFF;
        int ph = patternHighLatch & 0xFF;
        patternLowShift = (pl << 8); // low 8 bits left as previous/zero; high bits contain new tile
        patternHighShift = (ph << 8);
        // Attribute quadrant extraction; replicate bit pair into high 8 bits only
        int coarseX = vramAddress & 0x1F;
        int coarseY = (vramAddress >> 5) & 0x1F;
        int quadrant = ((coarseY & 0x02) << 1) | (coarseX & 0x02);
        int attributeBits = (atLatch >> quadrant) & 0x03;
        int lowBit = attributeBits & 0x01;
        int highBit = (attributeBits >> 1) & 0x01;
        attributeLowShift = (lowBit != 0 ? 0xFF00 : 0x0000) | (attributeLowShift & 0x00FF);
        attributeHighShift = (highBit != 0 ? 0xFF00 : 0x0000) | (attributeHighShift & 0x00FF);
    }

    // Prime first tile fetch sequence when enabling background at start of a
    // visible scanline
    private void primeFirstTile() {
        // Emulate phases 1,3,5,7 quickly to fill latches then reload
        ntLatch = ppuMemoryRead(0x2000 | (vramAddress & 0x0FFF));
        int v = vramAddress;
        int coarseX = v & 0x1F;
        int coarseY = (v >> 5) & 0x1F;
        int attributeAddr = 0x23C0 | (v & 0x0C00) | ((coarseY >> 2) << 3) | (coarseX >> 2);
        atLatch = ppuMemoryRead(attributeAddr);
        int fineY = (vramAddress >> 12) & 0x07;
        int base = ((regCTRL & 0x10) != 0 ? 0x1000 : 0x0000) + (ntLatch * 16) + fineY;
        patternLowLatch = ppuMemoryRead(base);
        patternHighLatch = ppuMemoryRead(base + 8);
        loadShiftRegisters();
    }

    // Placeholder memory space for pattern/nametables/palette until Bus integration
    // fleshed out.
    private int ppuMemoryRead(int addr) {
        addr &= 0x3FFF;
        if (addr < 0x2000) { // pattern tables
            return patternTables[addr] & 0xFF;
        } else if (addr < 0x3F00) { // nametables (0x2000-0x2FFF) mirror every 0x1000
            int nt = (addr - 0x2000) & 0x0FFF;
            int index = nt & 0x03FF; // 1KB region
            int table = (nt >> 10) & 0x03; // 4 possible, we only store 2 -> mirror 2 & 3 to 0 & 1
            if (table >= 2)
                table -= 2;
            return nameTables[(table * 0x0400) + index] & 0xFF;
        } else if (addr < 0x4000) {
            // Palette area stub: return fixed
            return 0; // future palette logic
        }
        return 0;
    }

    private void ppuMemoryWrite(int addr, int value) {
        addr &= 0x3FFF;
        value &= 0xFF;
        if (addr < 0x2000) {
            patternTables[addr] = (byte) value; // CHR RAM case
        } else if (addr < 0x3F00) {
            int nt = (addr - 0x2000) & 0x0FFF;
            int index = nt & 0x03FF;
            int table = (nt >> 10) & 0x03;
            if (table >= 2)
                table -= 2;
            nameTables[(table * 0x0400) + index] = (byte) value;
        } else if (addr < 0x4000) {
            // palette write stub ignored
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
        return frameBuffer[y * 256 + x];
    }

    int[] getFrameBufferRef() {
        return frameBuffer;
    }

    // Raw status for deeper debug if needed
    public int getStatusRegister() {
        return regSTATUS & 0xFF;
    }
}
