package com.nesemu.ppu;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.nesemu.bus.Bus;
import com.nesemu.cpu.CPU;

/** Tests sprite overflow flag (bit5) and unlimited sprite mode toggle. */
public class PPUSpriteOverflowAndUnlimitedTest {

    private Ppu2C02 newPpu(Bus bus) {
        Ppu2C02 p = new Ppu2C02();
        bus.attachPPU(p);
        return p;
    }

    @Test
    public void testSpriteOverflowFlagSetWith9SpritesSameScanline() {
        Bus bus = new Bus();
        Ppu2C02 ppu = newPpu(bus);
        CPU cpu = new CPU(bus); // attach CPU for completeness
        bus.attachCPU(cpu);
        ppu.reset();
        // Enable background & sprites in MASK so evaluation/render path active
        ppu.writeRegister(1, 0x18); // BG+sprites enable
        // Place 9 sprites all covering scanline 20 (y=20) without unlimited mode
        ppu.writeRegister(3, 0); // OAMADDR
        for (int i = 0; i < 9; i++) {
            // Y, Tile, Attr, X
            ppu.writeRegister(4, 20); // Y
            ppu.writeRegister(4, 0); // tile 0
            ppu.writeRegister(4, 0); // attr
            ppu.writeRegister(4, i * 8); // x staggered
        }
        // Advance PPU to scanline 20 start (evaluateSprites called at cycle 0)
        while (ppu.getScanline() < 20) {
            ppu.clock();
        }
        // Force cycle wrap to ensure evaluation ran
        while (ppu.getCycle() != 0) {
            ppu.clock();
        }
        // Overflow flag bit5 should be set
        assertTrue((ppu.getStatusRegister() & 0x20) != 0, "Sprite overflow flag not set");
        // Only 8 drawn when limited
        assertEquals(8, ppu.getLastSpriteCountThisLine());
    }

    @Test
    public void testNoOverflowWithExactly8Sprites() {
        Bus bus = new Bus();
        Ppu2C02 ppu = newPpu(bus);
        CPU cpu = new CPU(bus);
        bus.attachCPU(cpu);
        ppu.reset();
        ppu.writeRegister(1, 0x18); // enable BG+sprites
        // Clear OAM so no stale sprites overlap target line
        ppu.writeRegister(3, 0);
        for (int i = 0; i < 64; i++) {
            ppu.writeRegister(4, 0xFF); // Y offscreen
            ppu.writeRegister(4, 0); // tile
            ppu.writeRegister(4, 0); // attr
            ppu.writeRegister(4, 0); // X
        }
        // Now write exactly 8 sprites for scanline 40
        ppu.writeRegister(3, 0); // reset OAMADDR
        for (int i = 0; i < 8; i++) {
            ppu.writeRegister(4, 40); // Y => scanline 40
            ppu.writeRegister(4, 0); // tile
            ppu.writeRegister(4, 0); // attr
            ppu.writeRegister(4, i * 8); // X
        }
        while (ppu.getScanline() < 40) {
            ppu.clock();
        }
        while (ppu.getCycle() != 0) {
            ppu.clock();
        }
        // Exactly 8 should not trigger overflow flag
        assertEquals(8, ppu.getLastSpriteCountThisLine());
        assertEquals(0, ppu.getStatusRegister() & 0x20, "Overflow flag incorrectly set with exactly 8 sprites");
    }

    @Test
    public void testUnlimitedSpritesModeDoesNotCapCount() {
        Bus bus = new Bus();
        Ppu2C02 ppu = newPpu(bus);
        CPU cpu = new CPU(bus);
        bus.attachCPU(cpu);
        ppu.reset();
        ppu.setUnlimitedSprites(true);
        ppu.writeRegister(1, 0x18);
        ppu.writeRegister(3, 0);
        for (int i = 0; i < 12; i++) {
            ppu.writeRegister(4, 30); // Y
            ppu.writeRegister(4, 0); // tile
            ppu.writeRegister(4, 0); // attr
            ppu.writeRegister(4, i * 8); // X
        }
        while (ppu.getScanline() < 30) {
            ppu.clock();
        }
        while (ppu.getCycle() != 0) {
            ppu.clock();
        }
        // Logical count should be >8 now (since unlimited)
        assertTrue(ppu.getLastSpriteCountThisLine() > 8, "Unlimited mode did not keep logical count >8");
        // Overflow flag still set (since >8 encountered)
        assertTrue((ppu.getStatusRegister() & 0x20) != 0, "Overflow flag expected in unlimited mode");
    }
}
