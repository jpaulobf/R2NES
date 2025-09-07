package com.nesemu.apu;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

public class ApuTriangleLinearAndSweepTest {

    @Test
    public void triangle_linear_counter_reload_and_decrement() {
        APU apu = new APU();
        apu.reset();
        // Enable triangle via $4015 bit2
        apu.writeRegister(0x4015, 0x04);
        // Set linear control=0, value=5
        apu.writeRegister(0x4008, 0b0000_0101);
        // Writing $400B triggers reload flag and loads length
        apu.writeRegister(0x400B, (0 << 3));

        // Before any ticks, counter not yet reloaded
        assertEquals(0, apu.getTriangleLinearCounter());

        // Run until first quarter-frame tick (~3729.5 CPU cycles). We'll run one full
        // frame to be safe.
        for (int i = 0; i < 3729 + 10; i++)
            apu.clockCpuCycle();
        // After quarter tick, counter should load to 5
        assertEquals(5, apu.getTriangleLinearCounter());

        // Run another quarter tick worth (~3728 CPU cycles) to decrement
        for (int i = 0; i < 3728; i++)
            apu.clockCpuCycle();
        assertEquals(4, apu.getTriangleLinearCounter());

        // With control=0, reload flag should have been cleared and counter keeps
        // decrementing
        for (int i = 0; i < 3 * 3728; i++)
            apu.clockCpuCycle();
        assertEquals(1, apu.getTriangleLinearCounter());
    }

    @Test
    public void pulse1_sweep_increase_timer_and_status_bits_reflect_lengths() {
        APU apu = new APU();
        apu.reset();
        // Enable P1, P2, TRI, NOISE
        apu.writeRegister(0x4015, 0x0F);
        // Load lengths via their reload registers
        apu.writeRegister(0x4003, (5 << 3));
        apu.writeRegister(0x4007, (5 << 3));
        apu.writeRegister(0x400B, (5 << 3));
        apu.writeRegister(0x400F, (5 << 3));
        int st = apu.readStatus();
        assertEquals(0x0F, st & 0x0F, "All four length bits should be set");

        // Set P1 timer to 0x010 (16)
        apu.writeRegister(0x4002, 0x10);
        apu.writeRegister(0x4003, 0x00); // high=0
        assertEquals(16, apu.getPulse1Timer());

        // Configure sweep: enable=1, period=1, negate=0, shift=1
        apu.writeRegister(0x4001, 0b1001_0001);

        // Run one full frame so that at least two half-frame ticks occur
        for (int i = 0; i < 14916; i++)
            apu.clockCpuCycle();
        // Expected: After one sweep apply, timer increases by timer>>1 = 8 => 24
        assertEquals(24, apu.getPulse1Timer());
        assertFalse(apu.isPulse1Muted());
    }
}
