package com.nesemu.ppu;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.nesemu.bus.Bus;
import com.nesemu.cpu.CPU;

/** Verifica captura da secondary OAM construída no pipeline (linha seguinte). */
public class PPUSecondaryOamTest {

    private PPU newPpu(Bus bus) {
        PPU p = new PPU();
        bus.attachPPU(p);
        return p;
    }

    @Test
    public void testSecondaryOamSnapshotForScanline() {
        Bus bus = new Bus();
        PPU ppu = newPpu(bus);
        CPU cpu = new CPU(bus);
        bus.attachCPU(cpu);
        ppu.reset();
        ppu.writeRegister(1, 0x18); // habilita sprites + bg
        // Limpa OAM
        ppu.writeRegister(3, 0);
        for (int i = 0; i < 64; i++) {
            ppu.writeRegister(4, 0xFF); // Y offscreen
            ppu.writeRegister(4, 0);    // tile
            ppu.writeRegister(4, 0);    // attr
            ppu.writeRegister(4, 0);    // X
        }
        // Escreve 5 sprites visíveis na linha 50
        ppu.writeRegister(3, 0);
        for (int i = 0; i < 5; i++) {
            ppu.writeRegister(4, 50);      // Y
            ppu.writeRegister(4, i);       // tile diverso
            ppu.writeRegister(4, 0x03);    // attr
            ppu.writeRegister(4, 16 * i);  // X separado
        }
        // Avança até a linha 49 ciclo 257 para que a linha 50 seja avaliada
        while (!(ppu.getScanline() == 49 && ppu.getCycle() == 256)) {
            ppu.clock();
        }
        // Próximo ciclo fará evaluateSpritesForLine(50)
        ppu.clock(); // agora cycle=257 na linha 49
        assertEquals(49, ppu.getScanline());
        assertEquals(257, ppu.getCycle());
        // Secondary OAM deve ter sido preenchida para linha 50
        assertEquals(50, ppu.getSecondaryOamPreparedLine());
        byte[] sec = ppu.getSecondaryOamSnapshot();
        // Verifica primeiros 5 sprites (Y, tile, attr, X)
        for (int i = 0; i < 5; i++) {
            int base = i * 4;
            assertEquals(50, sec[base] & 0xFF);
            assertEquals(i & 0xFF, sec[base + 1] & 0xFF);
            assertEquals(0x03, sec[base + 2] & 0xFF);
            assertEquals(16 * i, sec[base + 3] & 0xFF);
        }
        // Entrada seguinte (sprite 5) deve continuar 0xFF (não copiado pois só 5 sprites)
        int nextBase = 5 * 4;
        assertEquals(0xFF, sec[nextBase] & 0xFF);
    }
}
