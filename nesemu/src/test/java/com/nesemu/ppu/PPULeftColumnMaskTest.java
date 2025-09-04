package com.nesemu.ppu;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.nesemu.emulator.NesEmulator;
import com.nesemu.rom.INesHeader;
import com.nesemu.rom.INesRom;

/**
 * Verifica o comportamento do bit MASK_BG_LEFT (PPUMASK bit1):
 * - Quando desligado (0x08 apenas BG), os 8 primeiros pixels de cada linha
 * devem ser forçados a 0.
 * - Quando ligado (0x0A), os 8 primeiros pixels exibem normalmente o background
 * (tile visível se padrão).
 */
public class PPULeftColumnMaskTest {

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
        // tile0 preenchido (bits=1) para gerar pixels não-transparentes
        for (int r = 0; r < 16; r++)
            chr[r] = (byte) 0xFF; // planos baixo+alto para linha 0..7
        return new INesRom(h, prg, chr, null);
    }

    @Test
    public void testLeftColumnBlankingOffWhenBitCleared() {
        INesRom rom = buildSimpleRom();
        NesEmulator emu = new NesEmulator(rom);
        // nametable 0 primeira entrada = tile0 (já default 0)
        // Habilita somente BG (bit3), sem bit1 (coluna esquerda) -> 0x08
        emu.getBus().write(0x2001, 0x08);
        emu.stepFrame();
        int[] idx = emu.getPpu().getBackgroundIndexBufferCopy();
        for (int y = 0; y < 240; y++) {
            for (int x = 0; x < 8; x++) {
                assertEquals(0, idx[y * 256 + x] & 0x0F,
                        "Pixel coluna esquerda deveria estar blank (y=" + y + ", x=" + x + ")");
            }
        }
    }

    @Test
    public void testLeftColumnVisibleWhenBitSet() {
        INesRom rom = buildSimpleRom();
        NesEmulator emu = new NesEmulator(rom);
        // Ativa BG + coluna esquerda (0x08 | 0x02 = 0x0A)
        emu.getBus().write(0x2001, 0x0A);
        emu.stepFrame();
        int[] idx = emu.getPpu().getBackgroundIndexBufferCopy();
        boolean anyNonZero = false;
        for (int y = 0; y < 240 && !anyNonZero; y++) {
            for (int x = 0; x < 8 && !anyNonZero; x++) {
                if ((idx[y * 256 + x] & 0x0F) != 0)
                    anyNonZero = true;
            }
        }
        assertTrue(anyNonZero,
                "Esperava ver algum pixel de background nos primeiros 8 pixels quando MASK_BG_LEFT ativo");
    }
}
