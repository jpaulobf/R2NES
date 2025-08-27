package com.nesemu.ppu;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.nesemu.mapper.Mapper0;
import com.nesemu.rom.INesHeader;
import com.nesemu.rom.INesRom;

/**
 * Tests nametable mirroring behavior (horizontal vs vertical) as interpreted
 * by the PPU when a mapper is attached.
 *
 * Strategy:
 * - Build synthetic iNES headers with only the mirroring flag differing.
 * - Use Mapper0 + attach to a fresh PPU.
 * - Write via PPUDATA ($2007) to logical nametable addresses spanning the 4
 * logical tables:
 * $2000 (table 0), $2400 (1), $2800 (2), $2C00 (3)
 * - Re-read each logical address and verify sharing rules:
 * Vertical: 0 & 2 share; 1 & 3 share.
 * Horizontal: 0 & 1 share; 2 & 3 share.
 * - We avoid attribute table edge cases; simple byte writes suffice.
 */
public class PPUMirroringTest {

    private Ppu2C02 newPPUWithMapper(boolean vertical) {
        // Build minimal header bytes (16) with mirroring bit set/cleared
        byte[] header = new byte[16];
        header[0] = 'N';
        header[1] = 'E';
        header[2] = 'S';
        header[3] = 0x1A;
        header[4] = 1; // 1 x 16KB PRG
        header[5] = 1; // 1 x 8KB CHR
        int flags6 = 0; // base
        if (vertical)
            flags6 |= 0x01; // set mirroring bit
        header[6] = (byte) flags6;
        header[7] = 0; // flags7
        INesHeader h = INesHeader.parse(header);
        // Provide dummy PRG/CHR data matching page counts
        byte[] prg = new byte[0x4000]; // 16KB
        byte[] chr = new byte[0x2000]; // 8KB
        INesRom rom = new INesRom(h, prg, chr, null);
        Mapper0 mapper = new Mapper0(rom);
        Ppu2C02 ppu = new Ppu2C02();
        ppu.reset();
        ppu.attachMapper(mapper);
        return ppu;
    }

    /**
     * Write a byte to PPU logical address using registers: set address high, low
     * then write data.
     */
    private void ppuWrite(Ppu2C02 p, int addr, int value) {
        p.writeRegister(6, (addr >> 8) & 0x3F); // high (mask like real PPU)
        p.writeRegister(6, addr & 0xFF); // low
        p.writeRegister(7, value & 0xFF);
    }

    /**
     * Read a byte via buffered PPUDATA (simulate buffered read by performing a
     * dummy read first).
     */
    private int ppuRead(Ppu2C02 p, int addr) {
        p.writeRegister(6, (addr >> 8) & 0x3F);
        p.writeRegister(6, addr & 0xFF);
        p.readRegister(7); // dummy buffered read
        p.writeRegister(6, (addr >> 8) & 0x3F);
        p.writeRegister(6, addr & 0xFF);
        return p.readRegister(7) & 0xFF;
    }

    @Nested
    @DisplayName("Vertical mirroring (0&2 share, 1&3 share)")
    class VerticalMirroring {
        @Test
        public void verticalSharesExpectedTables() {
            Ppu2C02 p = newPPUWithMapper(true);
            // Distinct values across four logical tables
            ppuWrite(p, 0x2000, 0x11); // table0
            ppuWrite(p, 0x2400, 0x22); // table1
            ppuWrite(p, 0x2800, 0x33); // table2 (should alias table0)
            ppuWrite(p, 0x2C00, 0x44); // table3 (should alias table1)

            int t0 = ppuRead(p, 0x2000);
            int t1 = ppuRead(p, 0x2400);
            int t2 = ppuRead(p, 0x2800);
            int t3 = ppuRead(p, 0x2C00);

            // Because of aliasing, writes to 0 and 2 last values should reflect last write
            // among their pair
            // We wrote 0x11 then 0x33 to aliased (0,2) -> both expected 0x33
            assertEquals(0x33, t0, "Vertical: table0 reflects last write among (0,2)");
            assertEquals(0x33, t2, "Vertical: table2 mirrors table0");
            // Pair (1,3): wrote 0x22 then 0x44 -> both 0x44
            assertEquals(0x44, t1, "Vertical: table1 reflects last write among (1,3)");
            assertEquals(0x44, t3, "Vertical: table3 mirrors table1");
        }

        @Test
        public void verticalAttributeTablesShare() {
            Ppu2C02 p = newPPUWithMapper(true);
            // Attribute addresses for logical tables: 0:$23C0 1:$27C0 2:$2BC0 3:$2FC0
            // Pair (0,2): write then overwrite via alias second
            ppuWrite(p, 0x23C0, 0xA1);
            ppuWrite(p, 0x2BC0, 0xA2); // overrides pair
            int a0 = ppuRead(p, 0x23C0);
            int a2 = ppuRead(p, 0x2BC0);
            assertEquals(0xA2, a0, "Vertical: attribute table0 reflects last write among (0,2)");
            assertEquals(0xA2, a2, "Vertical: attribute table2 mirrors table0");
            // Pair (1,3)
            ppuWrite(p, 0x27C0, 0xB1);
            ppuWrite(p, 0x2FC0, 0xB2);
            int a1 = ppuRead(p, 0x27C0);
            int a3 = ppuRead(p, 0x2FC0);
            assertEquals(0xB2, a1, "Vertical: attribute table1 reflects last write among (1,3)");
            assertEquals(0xB2, a3, "Vertical: attribute table3 mirrors table1");
        }
    }

    @Nested
    @DisplayName("Horizontal mirroring (0&1 share, 2&3 share)")
    class HorizontalMirroring {
        @Test
        public void horizontalSharesExpectedTables() {
            Ppu2C02 p = newPPUWithMapper(false);
            ppuWrite(p, 0x2000, 0x55); // table0
            ppuWrite(p, 0x2400, 0x66); // table1 (aliases table0)
            ppuWrite(p, 0x2800, 0x77); // table2
            ppuWrite(p, 0x2C00, 0x88); // table3 (aliases table2)

            int t0 = ppuRead(p, 0x2000);
            int t1 = ppuRead(p, 0x2400);
            int t2 = ppuRead(p, 0x2800);
            int t3 = ppuRead(p, 0x2C00);

            assertEquals(0x66, t0, "Horizontal: table0 reflects last write among (0,1)");
            assertEquals(0x66, t1, "Horizontal: table1 mirrors table0");
            assertEquals(0x88, t2, "Horizontal: table2 reflects last write among (2,3)");
            assertEquals(0x88, t3, "Horizontal: table3 mirrors table2");
        }

        @Test
        public void horizontalAttributeTablesShare() {
            Ppu2C02 p = newPPUWithMapper(false);
            // Pairs (0,1) and (2,3)
            ppuWrite(p, 0x23C0, 0xC1); // attr table0
            ppuWrite(p, 0x27C0, 0xC2); // overrides pair (0,1)
            int a0 = ppuRead(p, 0x23C0);
            int a1 = ppuRead(p, 0x27C0);
            assertEquals(0xC2, a0, "Horizontal: attribute table0 reflects last write among (0,1)");
            assertEquals(0xC2, a1, "Horizontal: attribute table1 mirrors table0");
            ppuWrite(p, 0x2BC0, 0xD1); // attr table2
            ppuWrite(p, 0x2FC0, 0xD2); // overrides pair (2,3)
            int a2 = ppuRead(p, 0x2BC0);
            int a3 = ppuRead(p, 0x2FC0);
            assertEquals(0xD2, a2, "Horizontal: attribute table2 reflects last write among (2,3)");
            assertEquals(0xD2, a3, "Horizontal: attribute table3 mirrors table2");
        }
    }
}
