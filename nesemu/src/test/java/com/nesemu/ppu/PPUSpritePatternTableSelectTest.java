package com.nesemu.ppu;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Verifica seleção da pattern table de SPRITES via PPUCTRL bit3 (0x08) em modo
 * 8x8
 * e que em modo 8x16 o bit3 não interfere (seleção depende do bit0 do tile
 * index).
 * (Antes os testes assumiam bit4 incorretamente.)
 */
public class PPUSpritePatternTableSelectTest {

    /**
     * Contract:
     * - When sprites are 8x8 (PPUCTRL bit5=0) and bit4=0, pattern table base is
     * $0000.
     * - When bit4=1, base changes to $1000.
     * We place distinct data in tile 0 in both tables and ensure pixel output flips
     * accordingly.
     */
    @Test
    public void testSpritePatternTableBit3AffectsPixelsIn8x8Mode() {
        Ppu2C02 ppu = new Ppu2C02();
        ppu.reset();
        // Enable rendering: background off, sprites on (MASK bit4)
        ppu.writeRegister(1, 0x10); // PPUMASK enable sprites (bit4)
        // Prepare palette entries for sprite palettes: use unique low nybbles so we can
        // detect
        // We'll set palette 0 (indices 0x3F10-0x3F13) so pattern value 1 becomes
        // palette low nibble 1, etc.
        for (int i = 0; i < 4; i++) {
            ppu.pokePalette(0x3F10 + i, i); // grayscale distinct indices
        }
        // OAM sprite 0 at (x=8,y=10), tile 0, palette 0, no flips, in front
        // OAM format: Y, tile, attr, X
        ppu.writeRegister(3, 0); // OAMADDR=0
        ppu.writeRegister(4, 10); // Y
        ppu.writeRegister(4, 0); // tile index 0
        ppu.writeRegister(4, 0); // attr
        ppu.writeRegister(4, 8); // X
        // Populate pattern tile 0 in table $0000 with solid pattern value 1 (lo plane
        // all 1, hi plane 0)
        for (int row = 0; row < 8; row++) {
            ppu.pokePattern(0x0000 + row, 0xFF); // low plane
            ppu.pokePattern(0x0000 + row + 8, 0x00); // high plane
        }
        // Populate pattern tile 0 in table $1000 with solid pattern value 2 (lo plane
        // 0, hi plane 0xFF)
        for (int row = 0; row < 8; row++) {
            ppu.pokePattern(0x1000 + row, 0x00); // low plane
            ppu.pokePattern(0x1000 + row + 8, 0xFF); // high plane
        }
        // 8x8 mode (bit5=0) e bit3=0 -> sprites de $0000
        ppu.writeRegister(0, 0x00); // bit3=0
        // Advance enough cycles to cover the sprite scanline (scanline 10) and a few
        // pixels
        // Each scanline is 341 PPU cycles; we'll run until after first visible pixels
        // of scanline 10.
        int targetScanline = 10;
        while (ppu.getScanline() < targetScanline || (ppu.getScanline() == targetScanline && ppu.getCycle() < 260)) {
            ppu.clock();
        }
        // Sample pixel where sprite should appear: (8,10)
        int pixelBit3Zero = ppu.getPixel(8, 10) & 0x0F;
        assertEquals(1, pixelBit3Zero, "Esperado valor 1 da tabela $0000 quando bit3=0");

        // Reset and repeat with bit4=1 (pattern table $1000)
        ppu.reset();
        ppu.writeRegister(1, 0x10); // enable sprites
        for (int i = 0; i < 4; i++)
            ppu.pokePalette(0x3F10 + i, i);
        ppu.writeRegister(3, 0);
        ppu.writeRegister(4, 10); // Y
        ppu.writeRegister(4, 0); // tile 0
        ppu.writeRegister(4, 0); // attr
        ppu.writeRegister(4, 8); // X
        for (int row = 0; row < 8; row++) {
            ppu.pokePattern(0x0000 + row, 0xFF);
            ppu.pokePattern(0x0000 + row + 8, 0x00);
            ppu.pokePattern(0x1000 + row, 0x00);
            ppu.pokePattern(0x1000 + row + 8, 0xFF);
        }
        ppu.writeRegister(0, 0x08); // set bit3 -> sprites $1000
        while (ppu.getScanline() < targetScanline || (ppu.getScanline() == targetScanline && ppu.getCycle() < 260)) {
            ppu.clock();
        }
        int pixelBit3One = ppu.getPixel(8, 10) & 0x0F;
        assertEquals(2, pixelBit3One, "Esperado valor 2 da tabela $1000 quando bit3=1");

        // Sanity: the two readings must differ
        assertNotEquals(pixelBit3Zero, pixelBit3One, "Pixel de sprite deve mudar ao alternar bit3");
    }

    /**
     * Quick 8x16 addressing smoke test: ensure bit4 does not change base when
     * height=16.
     */
    @Test
    public void testSprite8x16IgnoresBit3() {
        Ppu2C02 ppu = new Ppu2C02();
        ppu.reset();
        ppu.writeRegister(1, 0x10); // enable sprites
        for (int i = 0; i < 4; i++)
            ppu.pokePalette(0x3F10 + i, i);
        // Sprite using tile index 3 (odd) -> table select bit0=1 => $1000
        ppu.writeRegister(3, 0);
        ppu.writeRegister(4, 20); // Y
        ppu.writeRegister(4, 3); // tile 3 (odd -> table=$1000)
        ppu.writeRegister(4, 0); // attr
        ppu.writeRegister(4, 40); // X
        // Fill tile 2 (even) and 3 (odd) top/bottom halves in $1000 region with pattern
        // 1 vs 2 distinction
        // For 8x16, tile 3 uses tiles 2 (even) and 3 (odd) within same table selected
        // by bit0=1 => base $1000
        for (int row = 0; row < 8; row++) {
            // top half tile 2 -> pattern 1
            ppu.pokePattern(0x1000 + (2 * 16) + row, 0xFF);
            ppu.pokePattern(0x1000 + (2 * 16) + row + 8, 0x00);
            // bottom half tile 3 -> pattern 2
            ppu.pokePattern(0x1000 + (3 * 16) + row, 0x00);
            ppu.pokePattern(0x1000 + (3 * 16) + row + 8, 0xFF);
        }
        // Set 8x16 mode & bit4=0
        ppu.writeRegister(0, 0x20); // bit5=1 height=16, bit3=0
        int targetScanline = 20;
        while (ppu.getScanline() < targetScanline || (ppu.getScanline() == targetScanline && ppu.getCycle() < 260))
            ppu.clock();
        int topPixel = ppu.getPixel(40, 20) & 0x0F; // expect 1
        assertEquals(1, topPixel);
        // Now toggle bit4 (should be ignored in 8x16) and re-run from reset identical
        // data
        ppu.reset();
        ppu.writeRegister(1, 0x10);
        for (int i = 0; i < 4; i++)
            ppu.pokePalette(0x3F10 + i, i);
        ppu.writeRegister(3, 0);
        ppu.writeRegister(4, 20);
        ppu.writeRegister(4, 3);
        ppu.writeRegister(4, 0);
        ppu.writeRegister(4, 40);
        for (int row = 0; row < 8; row++) {
            ppu.pokePattern(0x1000 + (2 * 16) + row, 0xFF);
            ppu.pokePattern(0x1000 + (2 * 16) + row + 8, 0x00);
            ppu.pokePattern(0x1000 + (3 * 16) + row, 0x00);
            ppu.pokePattern(0x1000 + (3 * 16) + row + 8, 0xFF);
        }
        ppu.writeRegister(0, 0x28); // bit5=1 height=16, bit3=1 (não deve afetar seleção 8x16)
        while (ppu.getScanline() < targetScanline || (ppu.getScanline() == targetScanline && ppu.getCycle() < 260))
            ppu.clock();
        int topPixel2 = ppu.getPixel(40, 20) & 0x0F;
        assertEquals(1, topPixel2, "Bit3 não deve afetar seleção de tabela em sprites 8x16");
    }
}
