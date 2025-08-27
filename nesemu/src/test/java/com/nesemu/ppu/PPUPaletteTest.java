package com.nesemu.ppu;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class PPUPaletteTest {
    @Test
    public void testMirrorsUniversalBackground() {
        Palette pal = new Palette();
        pal.write(0x3F00, 0x22);
        // Writes to mirror addresses should read same value
        assertEquals(0x22 & 0x3F, pal.read(0x3F00));
        assertEquals(0x22 & 0x3F, pal.read(0x3F10));
        pal.write(0x3F10, 0x15);
        assertEquals(0x15 & 0x3F, pal.read(0x3F00));
        assertEquals(0x15 & 0x3F, pal.read(0x3F10));
    }

    @Test
    public void testDecodeAddressRangeWrap() {
        Palette pal = new Palette();
        pal.write(0x3F20, 0x3C); // wraps into 0x3F00 window (0x3F20 -> index 0)
        assertEquals(pal.read(0x3F00), pal.read(0x3F20));
    }

    @Test
    public void testGrayscaleMask() {
        Palette pal = new Palette();
        pal.write(0x3F00, 0x01); // some color
        int colorIdx = pal.read(0x3F00);
        int argbNormal = pal.getArgb(colorIdx, 0x00);
        int argbGray = pal.getArgb(colorIdx, 0x01); // grayscale bit set
        // Expect gray variant has R==G==B and differs from original unless original
        // already gray
        int r = (argbGray >> 16) & 0xFF;
        int g = (argbGray >> 8) & 0xFF;
        int b = argbGray & 0xFF;
        assertEquals(r, g);
        assertEquals(g, b);
        assertNotEquals(argbNormal, argbGray, "Grayscale changes color");
    }

    @Test
    public void testUniversalBackgroundAppliedWhenPatternZero() {
        Ppu2C02 p = new Ppu2C02();
        p.reset();
        p.pokeNameTable(0, 0x00); // tile 0
        p.pokeNameTable(0x3C0, 0b11); // attribute entry sets palette high bits
        p.pokePalette(0x3F00, 0x21); // universal color
        p.writeRegister(1, 0x08 | 0x02); // enable bg + left 8
        while (!(p.getScanline() == 0 && p.getCycle() == 0))
            p.clock();
        // Esperar até que o primeiro pixel (posição 0) seja realmente escrito
        // (frameBuffer inicial começa em 0xFF000000)
        int safety = 200; // limite para evitar loop infinito em caso de regressão
        while (p.getFrameBufferRef()[0] == 0xFF000000 && safety-- > 0) {
            p.clock();
        }
        assertTrue(safety > 0, "Primeiro pixel não foi produzido dentro do limite esperado");
        int rawIndex = p.getPixel(0, 0);
        assertEquals(0, rawIndex, "Raw background palette index should be 0 for pattern 0");
        int argb = p.getFrameBufferRef()[0];
        int expectedColorIndex = p.readPalette(0x3F00);
        int expectedArgb = Palette.NES_PALETTE[expectedColorIndex];
        assertEquals(expectedArgb, argb, "Universal background color applied");
    }

    @Test
    public void testEmphasisBitsAffectColor() {
        Palette pal = new Palette();
        pal.write(0x3F00, 0x16); // pick a base color with non-symmetric RGB
        int baseIdx = pal.read(0x3F00);
        int normal = pal.getArgb(baseIdx, 0x00); // no emphasis
        int emphR = pal.getArgb(baseIdx, 0x20); // R emphasis (bit5)
        int emphG = pal.getArgb(baseIdx, 0x40); // G emphasis (bit6)
        int emphB = pal.getArgb(baseIdx, 0x80); // B emphasis (bit7)
        assertNotEquals(normal, emphR, "R emphasis changes color");
        assertNotEquals(normal, emphG, "G emphasis changes color");
        assertNotEquals(normal, emphB, "B emphasis changes color");
    }

    @Test
    public void testEmphasisAppliedBeforeGrayscale() {
        Palette pal = new Palette();
        pal.write(0x3F00, 0x16); // reddish color so luma shifts with emphasis
        int idx = pal.read(0x3F00);
        int grayOnly = pal.getArgb(idx, 0x01); // grayscale bit
        int grayWithREmph = pal.getArgb(idx, 0x01 | 0x20); // grayscale + R emphasis
        // Because emphasis now precedes grayscale, luma should change and thus final
        // gray differs
        assertNotEquals(grayOnly, grayWithREmph, "Grayscale should reflect prior emphasis application");
    }
}
