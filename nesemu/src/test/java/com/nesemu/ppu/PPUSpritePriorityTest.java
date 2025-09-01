package com.nesemu.ppu;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.nesemu.bus.Bus;
import com.nesemu.cpu.CPU;

/**
 * Tests refined sprite priority: bit5=0 front, bit5=1 behind only shows when BG
 * transparent
 */
public class PPUSpritePriorityTest {

    private Ppu2C02 newPpu(Bus bus) {
        Ppu2C02 p = new Ppu2C02();
        bus.attachPPU(p);
        return p;
    }

    @Test
    public void testBehindSpriteHiddenByOpaqueBackground() {
        Bus bus = new Bus();
        Ppu2C02 p = newPpu(bus);
        CPU cpu = new CPU(bus);
        bus.attachCPU(cpu);
        p.reset();
        // Enable BG & sprites
        p.writeRegister(1, 0x18);
        // Build simple background: write a non-zero pattern tile into first nametable
        // We will sample pixel x=8 (not x=0) because pipeline needs 8 shifts for
        // freshly
        // loaded pattern bits (in low byte) to reach the tap position.
        // Pattern table base for BG depends on CTRL bit3 (0); tile 0 => base 0x0000.
        // Set one bit in low and high plane so pattern !=0.
        // Make tile 0 and tile 1 row0 fully opaque (pattern=1 for all 8 pixels) so
        // pixel x=8 is guaranteed non-zero
        p.pokePattern(0x0000, 0xFF); // tile 0 row0 low plane
        p.pokePattern(0x0008, 0x00); // tile 0 row0 high plane
        p.pokePattern(0x0010, 0xFF); // tile 1 row0 low plane
        p.pokePattern(0x0018, 0x00); // tile 1 row0 high plane
        // Put tile index 0 at top-left of nametable (already 0, but ensure attr selects
        // palette 0)
        // Place one sprite (sprite 0) fully overlapping that first pixel, behind
        // priority.
        p.writeRegister(3, 0); // OAMADDR
        // Y, tile, attr (bit5 priority=1 => behind), X
        p.writeRegister(4, 0); // Y
        p.writeRegister(4, 0); // tile 0
        p.writeRegister(4, 0x20); // attr: bit5=1 behind, palette 0
        p.writeRegister(4, 8); // X at pixel 8
        // Advance to first visible scanline & pixel (scanline 0, cycle wrap ensure eval
        // ran)
        while (p.getScanline() < 0)
            p.clock(); // advance pre-render -> visible
        // Pixel x maps to cycle x+9; we need pixel 8 so wait until cycle >= 17
        while (p.getCycle() < 18)
            p.clock();
        int x = 8;
        int rawBg = p.getRawBackgroundIndex(x, 0) & 0x0F;
        int composite = p.getBackgroundIndex(x, 0) & 0x0F;
        assertTrue(rawBg != 0, "Background should be opaque");
        assertEquals(rawBg, composite, "Behind sprite must not overwrite opaque background");
    }

    @Test
    public void testBehindSpriteShowsWhenBackgroundTransparent() {
        Bus bus = new Bus();
        Ppu2C02 p = newPpu(bus);
        CPU cpu = new CPU(bus);
        bus.attachCPU(cpu);
        p.reset();
        p.writeRegister(1, 0x18);
        // Use separate sprite pattern table so we can modify sprite pixels without
        // affecting background.
        // Set PPUCTRL bit4=1 (sprite pattern table = $1000). Leave bit3=0 (background =
        // $0000).
        p.writeRegister(0, 0x10);
        // Background tile 0 pattern bytes remain all zero -> background transparent at
        // (0,0).
        // Configure sprite 0 at (8,0) with behind priority; it should appear because BG
        // transparent at x=8.
        p.writeRegister(3, 0);
        p.writeRegister(4, 0); // Y
        p.writeRegister(4, 0); // tile 0 (still all zeros now, make it non-zero)
        // Make sprite pattern non-zero by setting pattern for tile 0 in sprite pattern
        // table ($1000 base).
        p.pokePattern(0x1000, 0x80); // set low bit for row0 of tile 0 in sprite table
        p.writeRegister(4, 0x20); // attr priority behind
        p.writeRegister(4, 8); // X
        // Ensure sprite palette entry yields non-zero color index for pattern=1
        p.pokePalette(0x3F11, 0x01); // sprite palette 0, entry for pattern index 1
        while (p.getScanline() < 0)
            p.clock();
        while (p.getCycle() < 18)
            p.clock(); // pixel 8
        int x = 8;
        int rawBg = p.getRawBackgroundIndex(x, 0) & 0x0F;
        int composite = p.getBackgroundIndex(x, 0) & 0x0F;
        assertEquals(0, rawBg, "Background should be transparent");
        assertTrue(composite != 0, "Behind sprite should draw over transparent background");
    }
}
