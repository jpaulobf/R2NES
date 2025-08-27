package com.nesemu.ppu;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests point 4: pixel generation from shift registers into framebuffer.
 */
public class PPUPixelGenerationTest {
    private Ppu2C02 newPPU() {
        Ppu2C02 p = new Ppu2C02();
        p.reset();
        return p;
    }

    private void enableBg(Ppu2C02 p) {
        p.writeRegister(1, 0x08 | 0x02);
    } // background + left 8 enabled

    private void advanceTo(Ppu2C02 p, int scan, int cyc) {
        while (!(p.getScanline() == scan && p.getCycle() == cyc))
            p.clock();
    }

    @Test
    public void firstTilePixelsRendered() {
        Ppu2C02 p = newPPU();
        // Setup tile index 1 at (0,0) with attribute bits = 2 (binary 10 -> attrHigh=1
        // attrLow=0)
        p.pokeNameTable(0, 0x01);
        p.pokeNameTable(0x3C0, 0b10);
        // Pattern table tile #1 fineY=0 bytes
        int base = 0x01 * 16;
        p.pokePattern(base, 0b10101010); // low pattern bits alternate
        p.pokePattern(base + 8, 0b01010101); // high pattern bits alternate inverse
        advanceTo(p, 0, 0);
        enableBg(p);
        // Run through cycles until after first reload (cycle 8) then gather first 8
        // pixels (cycles 9..16)
        for (int i = 0; i < 8; i++)
            p.clock(); // up to and including reload at phase 0
        for (int x = 0; x < 8; x++) { // produce pixel x by advancing one cycle each time
            p.clock();
            int pix = p.getPixel(x, 0);
            // Derive expected pattern bit sequence for first 8 pixels at fineY=0
            int bit0 = (0b10101010 >> (7 - x)) & 1;
            int bit1 = (0b01010101 >> (7 - x)) & 1;
            int pattern = (bit1 << 1) | bit0; // 0..3
            int attr = 0b10; // palette upper bits =2
            int expected = (attr << 2) | pattern;
            assertEquals(expected, pix, "Pixel " + x + " matches expected palette index");
        }
    }

    @Test
    public void leftEdgeClippingClearsWhenDisabled() {
        Ppu2C02 p = newPPU();
        p.pokeNameTable(0, 0x02);
        p.pokePattern(0x02 * 16, 0xFF);
        p.pokePattern(0x02 * 16 + 8, 0x00);
        advanceTo(p, 0, 0);
        // Enable background but NOT left 8 (omit bit1)
        p.writeRegister(1, 0x08); // only bit3
        for (int i = 0; i < 16; i++)
            p.clock();
        for (int x = 0; x < 8; x++)
            assertEquals(0, p.getPixel(x, 0), "Left clipped pixel");
    }
}
