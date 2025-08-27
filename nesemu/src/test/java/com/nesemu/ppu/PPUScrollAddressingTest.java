package com.nesemu.ppu;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for PPU loopy v/t/x behaviour (ponto 2):
 * - Increment coarse X with horizontal nametable switch at 31
 * - Increment Y with fine Y and coarse Y wrapping / vertical nametable switch
 * - Copy horizontal bits at cycle 257
 * - Copy vertical bits at pre-render cycles 280-304
 * - Ensure disabled rendering does NOT perform automatic increments/copies
 */
public class PPUScrollAddressingTest {

    private Ppu2C02 newPPU() {
        Ppu2C02 p = new Ppu2C02();
        p.reset();
        return p;
    }

    private void runCycles(Ppu2C02 p, int cycles) {
        for (int i = 0; i < cycles; i++)
            p.clock();
    }

    private void advanceTo(int targetScanline, int targetCycle, Ppu2C02 p) {
        while (!(p.getScanline() == targetScanline && p.getCycle() == targetCycle)) {
            p.clock();
        }
    }

    private void enableBackground(Ppu2C02 p) {
        // Write to $2001 (PPUMASK) to enable background rendering (bit3)
        p.writeRegister(1, 0x08);
    }

    @Test
    public void coarseXWrapsAndFlipsNametable() {
        Ppu2C02 p = newPPU();
        // Set v with coarse X = 31 and horizontal NT bit = 0 while rendering disabled
        p.setVramAddressForTest((0x20 << 8) | 0x1F); // coarseX=31
        advanceTo(0, 0, p); // reach start of visible scanline
        int before = p.getVramAddress();
        enableBackground(p); // enable rendering now
        advanceTo(0, 8, p); // first coarse X increment point
        int after = p.getVramAddress();
        assertEquals((before & ~0x001F) | 0x0400, after, "Coarse X wrap should reset X and toggle nametable");
    }

    @Test
    public void incrementYFineAndCoarseLogic() {
        Ppu2C02 p = newPPU();
        int v = (7 << 12) | (29 << 5); // fineY=7 coarseY=29
        p.setVramAddressForTest(v);
        advanceTo(0, 0, p);
        enableBackground(p);
        advanceTo(0, 256, p);
        int after = p.getVramAddress();
        assertEquals(0, (after >> 12) & 7, "fineY reset");
        assertEquals(0, (after >> 5) & 0x1F, "coarseY wrap");
        assertEquals(1, (after >> 11) & 1, "Vertical NT toggled");
    }

    @Test
    public void copyHorizontalAt257() {
        Ppu2C02 p = newPPU();
        int t = (1 << 10) | 10; // desired horizontal bits in t
        p.setTempAddressForTest(t); // set t
        p.setVramAddressForTest(3); // disturb v horizontal bits
        advanceTo(0, 0, p);
        enableBackground(p);
        advanceTo(0, 257, p); // includes the copy
        assertEquals(t & 0x041F, p.getVramAddress() & 0x041F,
                "Horizontal bits (coarseX + nametable X) should match t after copy at 257");
    }

    @Test
    public void copyVerticalDuringPreRender() {
        Ppu2C02 p = newPPU();
        int t = (5 << 12) | (1 << 11) | (17 << 5);
        p.setTempAddressForTest(t);
        p.setVramAddressForTest((0 << 12) | (0 << 11) | (2 << 5) | (t & 0x1F));
        // Advance within pre-render to cycle 279 then enable rendering so copy occurs
        // starting 280
        advanceTo(-1, 279, p);
        enableBackground(p);
        p.clock(); // advance to 280
        int after = p.getVramAddress();
        int mask = 0x7BE0;
        assertEquals((t & mask), (after & mask), "Vertical bits copy at 280 when rendering enabled");
    }

    @Test
    public void noAutoIncrementsWhenRenderingDisabled() {
        Ppu2C02 p = newPPU();
        // Ensure mask is zero (already after reset) and set a distinctive v
        int v = (7 << 12) | (31) | (29 << 5) | (1 << 10) | (1 << 11);
        p.setVramAddressForTest(v);
        // Run a full visible scanline worth of cycles
        runCycles(p, 341);
        assertEquals(v & 0x7FFF, p.getVramAddress(), "vramAddress must not change when rendering disabled");
    }
}
