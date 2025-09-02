package com.nesemu.mapper;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.nesemu.rom.INesHeader;
import com.nesemu.rom.INesRom;

/**
 * Basic MMC1 tests: serial writes, PRG banking mode 3 (fix last), CHR 8KB mode,
 * mirroring decode.
 */
public class Mapper1BasicTest {

    private Mapper1 newMapper1(int prgPages, int chrPages) {
        byte[] hdr = new byte[16];
        hdr[0] = 'N';
        hdr[1] = 'E';
        hdr[2] = 'S';
        hdr[3] = 0x1A;
        hdr[4] = (byte) prgPages; // 16KB units
        hdr[5] = (byte) chrPages; // 8KB units (0 => CHR RAM)
        hdr[6] = 0; // horizontal by header (fallback)
        hdr[7] = 0;
        var h = INesHeader.parse(hdr);
        byte[] prg = new byte[prgPages * 0x4000];
        for (int b = 0; b < prgPages; b++) {
            int base = b * 0x4000;
            for (int i = 0; i < 0x4000; i++)
                prg[base + i] = (byte) b; // fill pattern
        }
        byte[] chr = (chrPages == 0) ? new byte[0] : new byte[chrPages * 0x2000];
        for (int b = 0; b < chrPages; b++) {
            int base = b * 0x2000;
            for (int i = 0; i < 0x2000; i++)
                chr[base + i] = (byte) (b * 13 + i);
        }
        return new Mapper1(new INesRom(h, prg, chr, null));
    }

    private void writeSerial(Mapper1 m, int address, int value) {
        // Write 5 bits LSB-first: each write loads one bit until latch completes
        for (int i = 0; i < 5; i++) {
            int bit = (value >> i) & 1;
            m.cpuWrite(address, bit); // bit in LSB
        }
    }

    @Test
    public void controlWriteChangesMirroringAndPrgMode() {
        Mapper1 m = newMapper1(4, 2); // 64KB PRG, 16KB CHR
        // Default control = 0x0C => mirroring=0 (SINGLE0) initially
        assertEquals(0x0C, m.getControl());
        // Write control value 0x1B (mirroring=3 Horizontal, prgMode=3, chrMode=1)
        writeSerial(m, 0x8000, 0x1B);
        assertEquals(0x1B & 0x1F, m.getControl());
        assertEquals(Mapper.MirrorType.HORIZONTAL, m.getMirrorType());
    }

    @Test
    public void prgBankSwitchMode3() {
        Mapper1 m = newMapper1(4, 2);
        // Set control to mode 3 (fix last at $C000), mirroring horizontal, chr 8KB
        writeSerial(m, 0x8000, 0x1C); // 11100b => mirroring=0 (SINGLE0) prgMode=3 chrMode=1
        // Select PRG bank 2
        writeSerial(m, 0xE000, 0x02);
        assertEquals(2, m.getPrgBank() & 0x1F);
        // $8000 should read from bank2 pattern, $C000 from last bank (3)
        assertEquals(2, m.cpuRead(0x8000));
        assertEquals(3, m.cpuRead(0xC000));
    }

    @Test
    public void chr8kBanking() {
        Mapper1 m = newMapper1(2, 2); // 32KB PRG, 16KB CHR (2x8KB)
        // chrMode=0 (8KB) ensure control low bits not set -> use default 0x0C
        // (chrMode=0)
        // Select CHR bank via CHR0 register (value 1 => 8KB bank1)
        writeSerial(m, 0xA000, 0x01);
        assertEquals(1, m.getChrBank0() & 0x1F);
        int v0 = m.ppuRead(0x0000);
        int v1 = m.ppuRead(0x1FFF);
        assertNotEquals(v0, v1); // pattern should vary across region
    }
}
