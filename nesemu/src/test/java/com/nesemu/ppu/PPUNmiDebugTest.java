package com.nesemu.ppu;

import com.nesemu.bus.Bus;
import com.nesemu.cpu.CPU;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifica sincronização NMI vs VBlank:
 * (a) VBlank ($2002 bit7) deve estar set antes / ou no mesmo ciclo em que NMI é
 * sinalizado ao CPU.
 * (b) Um único NMI por entrada em vblank.
 */
public class PPUNmiDebugTest {
    private PPU ppu;
    private CPU cpu;
    private Bus bus;
    private int nmiCount;
    private boolean sawVblankBeforeNmi;

    @BeforeEach
    void setup() {
        bus = new Bus();
        cpu = new CPU(bus);
        bus.attachCPU(cpu);
        ppu = new PPU();
        bus.attachPPU(ppu);
        ppu.attachCPU(cpu);
        ppu.setNmiCallback(() -> {
            nmiCount++;
            // No callback for direct status sample; rely on flag from main loop assertions
            if ((ppu.getStatusRegister() & 0x80) != 0) {
                sawVblankBeforeNmi = true;
            }
        });
        ppu.reset();
        nmiCount = 0;
        sawVblankBeforeNmi = false;
    }

    @Test
    public void vblankFlagPrecedesOrMatchesNmi() {
        // Enable NMI generation
        bus.write(0x2000, 0x80);
        // Run until first NMI arrives (via callback increment)
        long safety = 100000; // generous PPU cycles
        while (nmiCount == 0 && safety-- > 0) {
            ppu.clock();
        }
        assertTrue(nmiCount > 0, "NMI não ocorreu no primeiro vblank");
        assertTrue(sawVblankBeforeNmi, "Bit de VBlank não estava set quando NMI chegou");
    }

    @Test
    public void singleNmiPerVblank() {
        bus.write(0x2000, 0x80); // habilita NMI
        int framesToObserve = 3; // ignore frame 0 partial (power-on) when counting
        long startFrame = ppu.getFrame();
        int[] nmiPerFrame = new int[framesToObserve + 1]; // include frame0 slot
        int lastSeen = 0;
        while (ppu.getFrame() < startFrame + framesToObserve) {
            long fBefore = ppu.getFrame();
            ppu.clock();
            if (nmiCount > lastSeen) {
                nmiPerFrame[(int) (fBefore - startFrame)]++;
                lastSeen = nmiCount;
            }
        }
        // Frames 1..framesToObserve-1 must each have exactly 1 NMI (frame 0 may or may
        // not, depending on when enable happened)
        for (int i = 1; i < framesToObserve; i++) {
            assertEquals(1, nmiPerFrame[i], "Frame " + i + " teve " + nmiPerFrame[i] + " NMIs");
        }
    }
}
