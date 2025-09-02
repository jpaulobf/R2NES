package com.nesemu.mapper;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.nesemu.rom.INesHeader;
import com.nesemu.rom.INesRom;

/**
 * Tests CHR RAM behavior under Mapper2 (UNROM) when header CHR=0.
 */
public class Mapper2ChrRamTest {

    private Mapper2 newMapper2ChrRam() {
        byte[] header = new byte[16];
        header[0] = 'N';
        header[1] = 'E';
        header[2] = 'S';
        header[3] = 0x1A;
        header[4] = 2; // 32KB PRG (2 pages) minimal for UNROM
        header[5] = 0; // CHR RAM
        header[6] = 1; // vertical mirroring (just to exercise getMirrorType)
        header[7] = 0;
        var h = INesHeader.parse(header);
        byte[] prg = new byte[2 * 0x4000];
        for (int i = 0; i < prg.length; i++)
            prg[i] = (byte) 0xEA; // NOP filler
        byte[] chr = new byte[0];
        INesRom rom = new INesRom(h, prg, chr, null);
        return new Mapper2(rom);
    }

    @Test
    public void chrRamReadWrite() {
        Mapper2 m = newMapper2ChrRam();
        // Write pattern to few addresses
        int[] addrs = { 0x0000, 0x07FF, 0x1234 & 0x1FFF, 0x1FFF };
        int[] vals = { 0x10, 0x55, 0xA7, 0xFF };
        for (int i = 0; i < addrs.length; i++)
            m.ppuWrite(addrs[i], vals[i]);
        for (int i = 0; i < addrs.length; i++)
            assertEquals(vals[i], m.ppuRead(addrs[i]), "CHR RAM mismatch addr=" + Integer.toHexString(addrs[i]));
        // Overwrite
        m.ppuWrite(0x07FF, 0x33);
        assertEquals(0x33, m.ppuRead(0x07FF));
        assertEquals(Mapper.MirrorType.VERTICAL, m.getMirrorType(), "Mirroring flag");
    }
}
