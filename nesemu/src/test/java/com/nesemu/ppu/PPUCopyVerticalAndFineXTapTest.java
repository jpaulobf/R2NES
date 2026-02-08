package com.nesemu.ppu;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import com.nesemu.emulator.NesEmulator;
import com.nesemu.rom.INesHeader;
import com.nesemu.rom.INesRom;

/**
 * Tests:
 * 1. copyVerticalBits(): during pre-render (scanline -1) cycles 280-304 the
 * vertical
 * components (fineY, coarseY, vertical nametable) of t must be copied to v.
 * 2. fineX tap precomputation: ensure that pixel boundary with known pattern
 * matches previous behaviour (sanity check that caching 15-fineX didn't break
 * logic).
 */
public class PPUCopyVerticalAndFineXTapTest {

    private INesRom buildSimpleRom() {
        byte[] header = new byte[16];
        header[0] = 'N';
        header[1] = 'E';
        header[2] = 'S';
        header[3] = 0x1A;
        header[4] = 1; // 16K PRG
        header[5] = 1; // 8K CHR
        INesHeader h = INesHeader.parse(header);
        byte[] prg = new byte[0x4000];
        byte[] chr = new byte[0x2000];
        // tile0 opaque (planos = 0xFF)
        for (int r = 0; r < 8; r++)
            chr[r] = (byte) 0xFF;
        // tile1 transparente (zeros)
        return new INesRom(h, prg, chr, null);
    }

    private void write(NesEmulator emu, int addr, int value) {
        emu.getBus().write(0x2006, (addr >> 8) & 0x3F);
        emu.getBus().write(0x2006, addr & 0xFF);
        emu.getBus().write(0x2007, value & 0xFF);
    }

    @Test
    public void testCopyVerticalBitsTransfersFineYCoarseYAndNtY() {
        INesRom rom = buildSimpleRom();
        NesEmulator emu = new NesEmulator(rom);
        // Enable BG so renderingEnabled() true
        emu.getBus().write(0x2001, 0x08); // BG enable only
        var ppu = (PPU) emu.getPpu();
        // Force tempAddress t with specific fineY=5, coarseY=17, vertical NT bit=1
        int fineY = 5; // bits 12-14
        int coarseY = 17; // bits 5-9
        int ntY = 1; // bit 11
        int t = (fineY << 12) | (ntY << 11) | (coarseY << 5);
        // Put distinct values in v first (set v different so change is observable)
        int initialV = 0x0123;
        ppu.forceCoreState(ppu.getMaskRegister(), ppu.getStatusRegister(), ppu.getCtrl(), -1, 279, initialV, t, 0, 0);
        // Advance to cycle 280 of pre-render so copyVerticalBits() region executes
        // (280..304)
        while (!(ppu.getScanline() == -1 && ppu.getCycle() >= 280)) {
            ppu.clock();
        }
        // After entering region, run a few cycles to ensure copy executed
        for (int i = 0; i < 10; i++)
            ppu.clock();
        int vAfter = ppu.getVramAddress() & 0x7BE0;
        int expected = t & 0x7BE0;
        assertEquals(expected, vAfter,
                String.format("Vertical copy mismatch expected=%04X got=%04X", expected, vAfter));
    }

    // Sanity: fineX tap caching did not shift boundary detection for fineX extremes
    // 0 and 7.
    @Test
    public void testFineXTapCacheBoundaryExtremes() {
        INesRom rom = buildSimpleRom();
        for (int fineX = 0; fineX <= 7; fineX += 7) { // test 0 and 7
            NesEmulator emu = new NesEmulator(rom);
            emu.getBus().write(0x2001, 0x0A); // BG enable + left 8 px
            emu.getBus().write(0x2005, fineX & 7);
            emu.getBus().write(0x2005, 0x00);
            // nametable: tile0 then tile1
            write(emu, 0x2000, 0x00);
            write(emu, 0x2001, 0x01);
            // Restore scroll (PPUADDR writes changed t)
            emu.getBus().write(0x2005, fineX & 7);
            emu.getBus().write(0x2005, 0x00);
            emu.stepFrame();
            int[] idx = emu.getPpu().getBackgroundIndexBufferCopy();
            int boundary = boundary(idx);
            int expected = 8 - fineX;
            assertTrue(boundary >= expected - 1 && boundary <= expected + 1,
                    "fineX=" + fineX + " boundary=" + boundary + " expected~=" + expected);
        }
    }

    private int boundary(int[] buf) {
        boolean seenOpaque = false;
        int y = 0;
        for (int x = 0; x < 64; x++) {
            int v = buf[y * 256 + x] & 0x0F;
            if (!seenOpaque) {
                if (v != 0)
                    seenOpaque = true;
            } else {
                if (v == 0)
                    return x;
            }
        }
        return -1;
    }
}
