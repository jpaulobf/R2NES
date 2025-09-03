package com.nesemu.mapper;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.nesemu.ppu.PPU;
import com.nesemu.rom.INesHeader;
import com.nesemu.rom.INesRom;

/**
 * Validates basic CHR bank switching for Mapper3 (CNROM):
 * - 2 banks of 8KB CHR ROM with distinct marker bytes at several offsets.
 * - Writing value selects bank (value % bankCount).
 * - PPU reads reflect currently selected bank.
 */
public class Mapper3ChrBankTest {

    private static class Fixture {
        final Mapper3 mapper;
        final PPU ppu;

        Fixture(Mapper3 m, PPU p) {
            this.mapper = m;
            this.ppu = p;
        }
    }

    private Fixture newMapper3(int chrBanks) {
        byte[] header = new byte[16];
        header[0] = 'N';
        header[1] = 'E';
        header[2] = 'S';
        header[3] = 0x1A;
        header[4] = 1; // 1 x 16KB PRG
        header[5] = (byte) chrBanks; // number of 8KB CHR banks
        header[6] = 0; // horizontal mirroring
        header[7] = 0;
        INesHeader h = INesHeader.parse(header);
        byte[] prg = new byte[0x4000]; // dummy 16KB (mirrored)
        // Build CHR with distinct banks: fill bank i with pattern i, (i+1), ...
        byte[] chr = new byte[chrBanks * 0x2000];
        for (int b = 0; b < chrBanks; b++) {
            int base = b * 0x2000;
            for (int i = 0; i < 0x2000; i++) {
                chr[base + i] = (byte) ((b * 17 + i) & 0xFF); // simple varying pattern per bank
            }
        }
        INesRom rom = new INesRom(h, prg, chr, null);
        Mapper3 m3 = new Mapper3(rom);
        PPU ppu = new PPU();
        ppu.reset();
        ppu.attachMapper(m3);
        return new Fixture(m3, ppu);
    }

    private int ppuReadPattern(PPU p, int addr) {
        // read via PPUADDR/PPUDATA sequence with buffering consideration
        p.writeRegister(6, (addr >> 8) & 0x3F);
        p.writeRegister(6, addr & 0xFF);
        p.readRegister(7); // prime buffer
        p.writeRegister(6, (addr >> 8) & 0x3F);
        p.writeRegister(6, addr & 0xFF);
        return p.readRegister(7) & 0xFF;
    }

    @Test
    public void bankSwitchReflectsDifferentData() {
        Fixture fx = newMapper3(2);
        // Sample a few addresses inside pattern region (<0x2000)
        int[] sampleAddrs = { 0x0000, 0x0100, 0x1FFE };
        // Bank 0 baseline reads
        int[] bank0 = new int[sampleAddrs.length];
        for (int i = 0; i < sampleAddrs.length; i++) {
            bank0[i] = ppuReadPattern(fx.ppu, sampleAddrs[i]);
        }
        // Select bank 1
        fx.mapper.cpuWrite(0x8000, 0x01);
        assertEquals(1, fx.mapper.getChrBank(), "CHR bank should become 1");
        int[] bank1 = new int[sampleAddrs.length];
        for (int i = 0; i < sampleAddrs.length; i++) {
            bank1[i] = ppuReadPattern(fx.ppu, sampleAddrs[i]);
            assertNotEquals(bank0[i], bank1[i],
                    String.format("Address %04X should differ between banks", sampleAddrs[i]));
        }
        // Wrap with larger write value (e.g., 0x81 -> 0x01 mod 2)
        fx.mapper.cpuWrite(0xFFFF, 0x81);
        assertEquals(1, fx.mapper.getChrBank(), "CHR bank mod wrapping expected");
        // Switch back to 0
        fx.mapper.cpuWrite(0xC123, 0x02); // 2 % 2 = 0
        assertEquals(0, fx.mapper.getChrBank(), "CHR bank should wrap to 0");
        for (int i = 0; i < sampleAddrs.length; i++) {
            int v = ppuReadPattern(fx.ppu, sampleAddrs[i]);
            assertEquals(bank0[i], v, "Returning to bank 0 should restore original data");
        }
    }
}
