package com.nesemu.mapper;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.nesemu.rom.INesHeader;
import com.nesemu.rom.INesRom;

/**
 * Tests UNROM (Mapper2) PRG bank switching:
 * - Variable bank at $8000-$BFFF responds to writes.
 * - Fixed last bank at $C000-$FFFF always points to last PRG page.
 */
public class Mapper2PrgBankTest {

    private Mapper2 newMapper2(int prgPages) {
        // prgPages >=2
        byte[] header = new byte[16];
        header[0] = 'N';
        header[1] = 'E';
        header[2] = 'S';
        header[3] = 0x1A;
        header[4] = (byte) prgPages; // PRG 16KB units
        header[5] = 0; // CHR=0 -> RAM
        header[6] = 0; // horiz mirroring
        header[7] = 0;
        var h = INesHeader.parse(header);
        byte[] prg = new byte[prgPages * 0x4000];
        for (int p = 0; p < prgPages; p++) {
            int base = p * 0x4000;
            for (int i = 0; i < 0x4000; i++)
                prg[base + i] = (byte) (p & 0xFF);
            // Reset vector (little endian) at end of last bank so we can sample
            if (p == prgPages - 1) {
                int vecBase = base + 0x3FFC;
                prg[vecBase] = (byte) 0x34; // low byte
                prg[vecBase + 1] = (byte) 0x12; // high byte
            }
        }
        byte[] chr = new byte[0];
        INesRom rom = new INesRom(h, prg, chr, null);
        return new Mapper2(rom);
    }

    private int cpuRead(Mapper2 m, int addr) {
        return m.cpuRead(addr) & 0xFF;
    }

    @Test
    public void bankSwitchAndFixedLastVerified() {
        Mapper2 m = newMapper2(4); // 4*16=64KB PRG => pages 0..3 (3 fixed at top)
        // Initial bank should be 0
        assertEquals(0, m.getPrgBank(), "Initial bank 0");
        // Read from variable window and fixed window
        assertEquals(0x00, cpuRead(m, 0x8000)); // bank0 pattern
        assertEquals(0x00, cpuRead(m, 0xBFFF));
        assertEquals(0x03, cpuRead(m, 0xC000)); // last bank pattern
        assertEquals(0x03, cpuRead(m, 0xFFFF));
        // Switch to bank 1
        m.cpuWrite(0x8000, 0x01);
        assertEquals(1, m.getPrgBank());
        assertEquals(0x01, cpuRead(m, 0x8000));
        assertEquals(0x03, cpuRead(m, 0xC000)); // still last
        // Switch to bank 2
        m.cpuWrite(0x9000, 0x02);
        assertEquals(2, m.getPrgBank());
        assertEquals(0x02, cpuRead(m, 0x8000));
        // Write value exceeding selectable range -> modulo (selectable=3 pages, last
        // fixed)
        m.cpuWrite(0xFFFF, 0x07); // 7 % 3 = 1
        assertEquals(1, m.getPrgBank());
        assertEquals(0x01, cpuRead(m, 0x8000));
        // Ensure last vector bytes remain from last bank
        assertEquals(0x34, cpuRead(m, 0xFFFC));
        assertEquals(0x12, cpuRead(m, 0xFFFD));
    }
}
