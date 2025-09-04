package com.nesemu.ppu;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.nesemu.emulator.NesEmulator;
import com.nesemu.rom.INesHeader;
import com.nesemu.rom.INesRom;

public class PPUForceLeftBlankTest {
    private INesRom buildSimpleRom() {
        byte[] header = new byte[16];
        header[0] = 'N';
        header[1] = 'E';
        header[2] = 'S';
        header[3] = 0x1A;
        header[4] = 1;
        header[5] = 1;
        INesHeader h = INesHeader.parse(header);
        byte[] prg = new byte[0x4000];
        byte[] chr = new byte[0x2000];
        for (int i = 0; i < 16; i++)
            chr[i] = (byte) 0xFF;
        return new INesRom(h, prg, chr, null);
    }

    @Test
    public void testForceLeftBlankOverridesMask() {
        NesEmulator emu = new NesEmulator(buildSimpleRom());
        emu.getBus().write(0x2001, 0x0A); // BG + left column
        ((PPU) emu.getPpu()).setLeftColumnMode(PPU.LeftColumnMode.ALWAYS);
        emu.stepFrame();
        int[] idx = emu.getPpu().getBackgroundIndexBufferCopy();
        for (int y = 0; y < 240; y++) {
            for (int x = 0; x < 8; x++) {
                assertEquals(0, idx[y * 256 + x] & 0x0F, "Coluna esquerda deveria estar blank (ALWAYS mode)");
            }
        }
    }
}
