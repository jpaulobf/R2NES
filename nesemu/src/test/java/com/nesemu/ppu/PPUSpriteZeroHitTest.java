package com.nesemu.ppu;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** Tests for sprite 0 hit (STATUS bit6) behavior. */
public class PPUSpriteZeroHitTest {

    private PPU freshPpu() {
        PPU p = new PPU();
        p.reset();
        return p;
    }

    /**
     * (a) Hit occurs when BG pixel non-zero and sprite 0 pixel non-zero at same
     * coordinate (outside left clip or both masks enabled).
     */
    @Test
    public void testSpriteZeroHitOccurs() {
        PPU ppu = freshPpu();
        // Enable background + sprites fully (no left clipping disabled) bits: bg (3),
        // sprites (4), bg left (1), sprite left (2)
        ppu.writeRegister(1, 0x1E); // 0001 1110 -> bits 1,2,3,4 set
        // Place a background tile with pattern producing non-zero pixel at (16,16)
        // We'll craft a simple pattern in background table 0, tile index 1 with a
        // single row/col solid
        int tileIndex = 1;
        // Put tile index 1 into nametable 0 at position covering (16,16): tile (2,2) =>
        // nametable offset = 2 + 2*32
        int nametableOffset = 2 + 2 * 32; // inside first table
        ppu.pokeNameTable(nametableOffset, tileIndex);
        // Pattern for tile 1: all pixels value 1 (lo plane 0xFF, hi 0x00)
        for (int row = 0; row < 8; row++) {
            ppu.pokePattern(0x0000 + tileIndex * 16 + row, 0xFF); // low
            ppu.pokePattern(0x0000 + tileIndex * 16 + row + 8, 0x00); // high
        }
        // Sprite 0 positioned to overlap pixel (16,16)
        ppu.writeRegister(3, 0); // OAMADDR
        ppu.writeRegister(4, 16); // Y
        ppu.writeRegister(4, 0); // tile 0 (sprite pattern table 0)
        ppu.writeRegister(4, 0); // attr palette 0
        ppu.writeRegister(4, 16); // X
        // Sprite tile 0 pattern: opaque value 2 (lo plane 0x00 hi plane 0xFF)
        for (int row = 0; row < 8; row++) {
            ppu.pokePattern(0x0000 + 0 * 16 + row, 0x00); // low plane
            ppu.pokePattern(0x0000 + 0 * 16 + row + 8, 0xFF); // high plane
        }
        // Enable background pattern table 0 (default) & sprite table 0
        ppu.writeRegister(0, 0x00);
        // Run until after scanline 16 rendered
        while (ppu.getScanline() < 17)
            ppu.clock();
        int status = ppu.getStatusRegister();
        assertTrue((status & 0x40) != 0, "Sprite 0 hit bit should be set");
    }

    /** (b) No hit if background pixel is transparent (BG pattern zero). */
    @Test
    public void testNoHitWithTransparentBackground() {
        PPU ppu = freshPpu();
        ppu.writeRegister(1, 0x1E);
        // Background: leave nametable default (tile 0 => pattern 0 -> transparent)
        // Sprite 0 at (20,20) opaque pattern
        ppu.writeRegister(3, 0);
        ppu.writeRegister(4, 20); // Y
        ppu.writeRegister(4, 0); // tile 0
        ppu.writeRegister(4, 0); // attr
        ppu.writeRegister(4, 20); // X
        for (int row = 0; row < 8; row++) {
            // Ensure tile 0 is fully transparent (both planes 0) so background pixel stays
            // 0
            ppu.pokePattern(0x0000 + row, 0x00); // low plane
            ppu.pokePattern(0x0000 + row + 8, 0x00); // high plane transparent
        }
        // Sprite tile 0 needs to be opaque; reuse tile 1 for sprite by writing pattern
        // there
        // but keep sprite referencing tile 0 => modify to use palette with pattern bits
        // from high plane only
        // Instead change sprite tile index to 1 now that tile 0 cleared
        ppu.writeRegister(3, 0);
        ppu.writeRegister(4, 20); // Y
        ppu.writeRegister(4, 1); // tile 1
        ppu.writeRegister(4, 0); // attr
        ppu.writeRegister(4, 20); // X
        for (int row = 0; row < 8; row++) {
            ppu.pokePattern(0x0000 + 1 * 16 + row, 0x00);
            ppu.pokePattern(0x0000 + 1 * 16 + row + 8, 0xFF);
        }
        ppu.writeRegister(0, 0x00);
        while (ppu.getScanline() < 21)
            ppu.clock();
        assertEquals(0, ppu.getStatusRegister() & 0x40, "Sprite 0 hit should not occur with transparent background");
    }

    /**
     * (c) Hit suppressed inside left 8 pixels if clipping masks disabled for either
     * BG or sprites.
     */
    @Test
    public void testLeftClippingPreventsHit() {
        PPU ppu = freshPpu();
        // Enable BG+Sprites but disable left BG (bit1) and left sprite (bit2) => only
        // set bits 3,4
        ppu.writeRegister(1, 0x18); // 0001 1000
        // Put background tile at (0,0) with opaque pattern (tile 1)
        int tileIndex = 1;
        ppu.pokeNameTable(0, tileIndex);
        for (int row = 0; row < 8; row++) {
            ppu.pokePattern(0x0000 + tileIndex * 16 + row, 0xFF);
            ppu.pokePattern(0x0000 + tileIndex * 16 + row + 8, 0x00);
        }
        // Sprite 0 at (0,0) with opaque pattern (tile 0 hi plane 0xFF)
        ppu.writeRegister(3, 0);
        ppu.writeRegister(4, 0); // Y
        ppu.writeRegister(4, 0); // tile 0
        ppu.writeRegister(4, 0); // attr
        ppu.writeRegister(4, 0); // X
        for (int row = 0; row < 8; row++) {
            ppu.pokePattern(0x0000 + 0 * 16 + row, 0x00);
            ppu.pokePattern(0x0000 + 0 * 16 + row + 8, 0xFF);
        }
        ppu.writeRegister(0, 0x00);
        while (ppu.getScanline() < 2)
            ppu.clock();
        assertEquals(0, ppu.getStatusRegister() & 0x40,
                "Sprite 0 hit should be clipped inside left 8 when masks disabled");

        // Now enable left clipping bits and confirm hit occurs
        ppu.reset();
        ppu.writeRegister(1, 0x1E); // enable bits 1,2,3,4
        ppu.pokeNameTable(0, tileIndex);
        for (int row = 0; row < 8; row++) {
            ppu.pokePattern(0x0000 + tileIndex * 16 + row, 0xFF);
            ppu.pokePattern(0x0000 + tileIndex * 16 + row + 8, 0x00);
            ppu.pokePattern(0x0000 + 0 * 16 + row, 0x00);
            ppu.pokePattern(0x0000 + 0 * 16 + row + 8, 0xFF);
        }
        ppu.writeRegister(3, 0);
        ppu.writeRegister(4, 0);
        ppu.writeRegister(4, 0);
        ppu.writeRegister(4, 0);
        ppu.writeRegister(4, 0);
        ppu.writeRegister(0, 0x00);
        while (ppu.getScanline() < 2)
            ppu.clock();
        assertTrue((ppu.getStatusRegister() & 0x40) != 0, "Sprite 0 hit should occur when clipping bits enabled");
    }
}
