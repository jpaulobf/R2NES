package com.nesemu.ppu;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.nesemu.bus.Bus;
import com.nesemu.cpu.CPU;

/**
 * Tests for OAM DMA ($4014) copy and CPU stall timing.
 */
public class PPUOamDmaTest {

    @Test
    public void testOamDmaCopies256BytesAndStallsCpu() {
        Bus bus = new Bus();
        Ppu2C02 ppu = new Ppu2C02();
        bus.attachPPU(ppu);
        CPU cpu = new CPU(bus);
        bus.attachCPU(cpu);

        // Fill source page $0200-$02FF with pattern i^0x55
        int page = 0x02;
        for (int i = 0; i < 256; i++) {
            bus.write((page << 8) | i, (i ^ 0x55) & 0xFF);
        }
        long baseCycles = cpu.getTotalCycles();
        // Trigger DMA by writing page value to $4014
        bus.write(0x4014, page);
        // CPU should now be stalled; advance a chunk of cycles manually calling clock()
        int stallObserved = 0;
        while (cpu.isDmaStalling()) {
            cpu.clock();
            stallObserved++;
            assertTrue(stallObserved < 600, "DMA stall exceeded expected max");
        }
        // Expect stall either 513 or 514 cycles
        assertTrue(stallObserved == 513 || stallObserved == 514, "Unexpected stall cycles: " + stallObserved);
        // Verify OAM content copied
        for (int i = 0; i < 256; i++) {
            int expected = (i ^ 0x55) & 0xFF;
            assertEquals(expected, ppu.dmaOamRead(i), "OAM mismatch at index " + i);
        }
        assertEquals(baseCycles + stallObserved, cpu.getTotalCycles(),
                "Total cycles should reflect stall advance only");
    }
}
