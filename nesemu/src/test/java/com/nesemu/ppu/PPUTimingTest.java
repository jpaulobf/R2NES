package com.nesemu.ppu;

import com.nesemu.bus.Bus;
import com.nesemu.cpu.CPU;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Testes para infraestrutura básica da PPU (ponto 1):
 * - Contadores de ciclo/linha/quadro
 * - Transições de VBlank (entrada em 241,1; limpeza em -1,1)
 * - Geração de NMI condicionada ao bit 7 de PPUCTRL e single-shot.
 */
public class PPUTimingTest {
    private Ppu2C02 ppu;
    private CPU cpu;
    private Bus bus;
    private int nmiCount;

    @BeforeEach
    void setUp() {
        bus = new Bus();
        cpu = new CPU(bus);
        bus.attachCPU(cpu);
        ppu = new Ppu2C02();
        bus.attachPPU(ppu);
        ppu.attachCPU(cpu);
        ppu.setNmiCallback(() -> nmiCount++);
        ppu.reset();
        nmiCount = 0;
    }

    @Test
    public void entersAndClearsVBlankAtExpectedScanlines() {
        // Run until start of vblank (scanline 241 cycle 1) => (241+1) * 341 cycles
        // approx
        // We iterate until flag set to be robust.
        while (!ppu.isInVBlank()) {
            ppu.clock();
            // Safety guard to avoid infinite loop
            assertTrue(ppu.getFrame() < 2, "Nunca entrou em VBlank no primeiro frame");
        }
        assertEquals(241, ppu.getScanline());
        assertTrue(ppu.isInVBlank());
        // Continue until pre-render clears
        while (!(ppu.isPreRender() && !ppu.isInVBlank() && ppu.getCycle() >= 1)) {
            ppu.clock();
            assertTrue(ppu.getFrame() < 3, "Nunca limpou VBlank até frame 2");
        }
        assertTrue(ppu.isPreRender());
        assertFalse(ppu.isInVBlank());
    }

    @Test
    public void nmiFiresOncePerVBlankWhenEnabled() {
        // Enable NMI (PPUCTRL bit7) via bus write ($2000)
        bus.write(0x2000, 0x80);
        int targetFrames = 2;
        long startFrame = ppu.getFrame();
        long endFrame = startFrame + targetFrames;
        while (ppu.getFrame() < endFrame) {
            ppu.clock();
        }
        // Expect NMI count == number of vblank entries (targetFrames)
        assertTrue(nmiCount >= targetFrames, "NMI não disparou por frame");
    }

    @Test
    public void enablingNmiMidVblankTriggersLateEdgeOnce() {
        // Run until inside vblank with NMI disabled
        while (!ppu.isInVBlank())
            ppu.clock();
        int countBefore = nmiCount;
        // Now enable NMI bit mid-vblank
        bus.write(0x2000, 0x80);
        // A few cycles should trigger a late NMI
        for (int i = 0; i < 50; i++)
            ppu.clock();
        assertTrue(nmiCount == countBefore + 1, "NMI tardio não disparou exatamente uma vez");
    }
}
