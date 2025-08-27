package com.nesemu.ppu;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.nesemu.mapper.Mapper0;
import com.nesemu.rom.INesHeader;
import com.nesemu.rom.INesRom;

/**
 * Validates CHR RAM behavior (mapper 0 with 0 CHR pages):
 * - Writes via $2006/$2007 into $0000-$1FFF are stored (CHR RAM present).
 * - Reads back (considering PPUDATA buffering) return the written values.
 */
public class PPUChrRamTest {

    private Ppu2C02 newChrRamPPU() {
        byte[] header = new byte[16];
        header[0] = 'N'; header[1] = 'E'; header[2] = 'S'; header[3] = 0x1A;
        header[4] = 1; // 1 x 16KB PRG
        header[5] = 0; // 0 x 8KB CHR -> CHR RAM
        header[6] = 0; // horizontal mirroring default (irrelevant here)
        header[7] = 0;
        INesHeader h = INesHeader.parse(header);
        byte[] prg = new byte[0x4000]; // dummy PRG
        byte[] chr = new byte[0]; // empty CHR -> RAM path
        INesRom rom = new INesRom(h, prg, chr, null);
        Mapper0 mapper = new Mapper0(rom);
        Ppu2C02 ppu = new Ppu2C02();
        ppu.reset();
        ppu.attachMapper(mapper);
        return ppu;
    }

    private void ppuWrite(Ppu2C02 p, int addr, int value) {
        p.writeRegister(6, (addr >> 8) & 0x3F);
        p.writeRegister(6, addr & 0xFF);
        p.writeRegister(7, value & 0xFF);
    }

    private int ppuReadBuffered(Ppu2C02 p, int addr) {
        // Perform dummy read to populate buffer then actual read
        p.writeRegister(6, (addr >> 8) & 0x3F);
        p.writeRegister(6, addr & 0xFF);
        p.readRegister(7); // discard buffered (stale)
        p.writeRegister(6, (addr >> 8) & 0x3F);
        p.writeRegister(6, addr & 0xFF);
        return p.readRegister(7) & 0xFF;
    }

    @Test
    public void chrRamReadWriteReflectsValues() {
        Ppu2C02 p = newChrRamPPU();
        int[] addrs = {0x0000, 0x07FF, 0x0ABC, 0x1FFF};
        int[] vals  = {0x12,   0x34,   0x5A,   0xFF};
        for (int i = 0; i < addrs.length; i++) {
            ppuWrite(p, addrs[i], vals[i]);
        }
        for (int i = 0; i < addrs.length; i++) {
            int read = ppuReadBuffered(p, addrs[i]);
            assertEquals(vals[i], read, String.format("CHR RAM read mismatch at %04X", addrs[i]));
        }
        // Overwrite one address to ensure updates propagate
        ppuWrite(p, 0x0ABC, 0x99);
        assertEquals(0x99, ppuReadBuffered(p, 0x0ABC), "Overwrite should update CHR RAM");
    }
}
