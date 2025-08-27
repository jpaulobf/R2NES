package com.nesemu.ppu;

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
    private com.nesemu.cpu.CPU cpu; // keep loose coupling (avoid interface for now)

    public void attachCPU(com.nesemu.cpu.CPU cpu) {
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

    public void setNmiCallback(Runnable cb) {
        this.nmiCallback = cb;
    }

    // Debug flag (can be toggled via system property -Dnes.ppu.debug=true)
    private static final boolean DEBUG = Boolean.getBoolean("nes.ppu.debug");

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
                regMASK = value;
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

    // Placeholder memory space for pattern/nametables/palette until Bus integration
    // fleshed out.
    private int ppuMemoryRead(int addr) {
        return 0;
    }

    private void ppuMemoryWrite(int addr, int value) {
        /* ignore for now */ }

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

    // Raw status for deeper debug if needed
    public int getStatusRegister() {
        return regSTATUS & 0xFF;
    }
}
