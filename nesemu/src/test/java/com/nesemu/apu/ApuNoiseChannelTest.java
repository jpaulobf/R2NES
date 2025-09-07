package com.nesemu.apu;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

public class ApuNoiseChannelTest {

    @Test
    public void noise_lfsr_shifts_and_output_gated_by_length() {
        APU apu = new APU();
        apu.reset();
        // Enable noise
        apu.writeRegister(0x4015, 0x08);
        // Envelope: constant volume 1, volume=7 (bit5=0 loop irrelevant here)
        apu.writeRegister(0x400C, 0b0001_0111);
        // Set period idx small so it shifts quickly, mode=long
        apu.writeRegister(0x400E, 0b0000_0000); // mode=0, period=0
        // Load length (non-zero)
        apu.writeRegister(0x400F, (5 << 3));
        int b0Start = apu.getNoiseLfsrBit0();
        // run enough CPU cycles to cause some shifts (APU ticks at CPU/2)
        for (int i = 0; i < 2000; i++)
            apu.clockCpuCycle();
        int b0End = apu.getNoiseLfsrBit0();
        assertNotEquals(b0Start, b0End, "LFSR should have advanced");
        int out = apu.getNoiseOutputLevel();
        assertTrue(out == 0 || out == 7);
    }
}
