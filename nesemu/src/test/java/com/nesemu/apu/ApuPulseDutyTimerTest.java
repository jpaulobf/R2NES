package com.nesemu.apu;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

public class ApuPulseDutyTimerTest {

    @Test
    public void pulse1_duty_steps_and_mutes_on_small_timer() {
        Apu2A03 apu = new Apu2A03();
        apu.reset();
        // Enable P1
        apu.writeRegister(0x4015, 0x01);
        // Set duty=50% (index 2), constant volume=1, volume=10
        apu.writeRegister(0x4000, 0b10_1_0000 | 10);
        // Timer low/high = 8 -> expect muted because < 8
        apu.writeRegister(0x4002, 8);
        apu.writeRegister(0x4003, 0);
        assertTrue(apu.isPulse1Muted());
        // Raise timer to 16 and reload length, unmute expected
        apu.writeRegister(0x4002, 16);
        apu.writeRegister(0x4003, (5 << 3));
        assertFalse(apu.isPulse1Muted());
        // Step many CPU cycles; duty should advance at CPU/2 when timer ticks
        int initialStep = apu.getPulse1DutyStep();
        for (int i = 0; i < 1000; i++)
            apu.clockCpuCycle();
        assertNotEquals(initialStep, apu.getPulse1DutyStep());
        // Output level should be either 0 or the envelope value (10)
        int out = apu.getPulse1OutputLevel();
        assertTrue(out == 0 || out == 10);
    }
}
