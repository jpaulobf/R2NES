package com.nesemu.ppu;

/**
 * Minimal 2C02 PPU skeleton: implements core registers and a basic cycle/scanline counter.
 *
 * Exposed behaviour right now:
 * - write/read $2000-$2007 via Bus (Bus will call readRegister/writeRegister when integrated)
 * - status ($2002) vblank bit set at scanline 241, cleared at pre-render (-1)
 * - simple VRAM address latch (PPUADDR) and increment logic (PPUDATA increments by 1 or 32)
 * - internal buffer for PPUDATA read delay emulation (return buffered & fill new)
 *
 * Missing (future): pattern fetch pipeline, nametable/palette storage, mirroring, sprite system.
 */
public class Ppu2C02 implements PPU {

    // Registers
    private int regCTRL;   // $2000
    private int regMASK;   // $2001
    private int regSTATUS; // $2002
    private int oamAddr;   // $2003
    // $2004 OAMDATA (not implemented yet)
    // $2005 PPUSCROLL (x,y latch)
    // $2006 PPUADDR (VRAM address latch)
    // $2007 PPUDATA

    // VRAM address latch / toggle
    private boolean addrLatchHigh = true; // true -> next write is high byte
    private int vramAddress; // current VRAM address
    private int tempAddress; // temp (t) during address/scroll sequence
    private int fineX;       // fine X scroll (0..7) from first scroll write

    // Buffered read (PPUDATA) behavior
    private int readBuffer; // internal buffer

    // Timing counters
    private int cycle;      // 0..340
    private int scanline;   // -1 pre-render, 0..239 visible, 240 post, 241..260 vblank

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
    }

    @Override
    public void clock() {
        // Very minimal timing: just advance counters and toggle vblank.
        cycle++;
        if (cycle > 340) {
            cycle = 0;
            scanline++;
            if (scanline > 260) scanline = -1; // wrap
            if (scanline == 241 && cycle == 0) {
                // Enter vblank
                regSTATUS |= 0x80; // set VBlank flag
            } else if (scanline == -1 && cycle == 0) {
                // Clear vblank & sprite flags at pre-render
                regSTATUS &= ~0x80; // clear VBlank
                // sprite 0 hit & overflow would also be cleared here (bits 6 & 5)
                regSTATUS &= 0x1F; // keep lower 5 bits only for now
            }
        }
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

    // Placeholder memory space for pattern/nametables/palette until Bus integration fleshed out.
    private int ppuMemoryRead(int addr) { return 0; }
    private void ppuMemoryWrite(int addr, int value) { /* ignore for now */ }

    // Accessors for future tests/debug
    public int getScanline() { return scanline; }
    public int getCycle() { return cycle; }
    public boolean isInVBlank() { return (regSTATUS & 0x80) != 0; }
}

