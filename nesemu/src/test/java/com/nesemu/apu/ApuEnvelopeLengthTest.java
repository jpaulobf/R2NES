package com.nesemu.apu;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

/**
 * Envelope and Length Counter basic behavior tests.
 */
public class ApuEnvelopeLengthTest {

    @Test
    public void pulse1_envelope_decay_and_length_counter_halting() {
        APU apu = new APU();
        apu.reset();
        // Enable Pulse1 via $4015
        apu.writeRegister(0x4015, 0x01);
        // Set Pulse1 envelope: period=3, constantVolume=0, loop=0 (decay)
        apu.writeRegister(0x4000, 0b0000_0011);
        // Trigger length reload with index 10 (LENGTH_TABLE[10] = 60)
        apu.writeRegister(0x4003, (10 << 3));
        int initialLen = apu.getPulse1Length();
        assertTrue(initialLen > 0, "Length loaded");

        // Run one full 4-step frame (~14916 CPU cycles) -> half ticks: 2, quarter: 4
        for (int i = 0; i < 14916; i++)
            apu.clockCpuCycle();
        // Length should have decremented by 2 when not halted
        assertEquals(Math.max(0, initialLen - 2), apu.getPulse1Length());

        // Now set loop/halt (bit5) to halt length counter and restart envelope
        apu.writeRegister(0x4000, 0b0010_0011); // loop=1, period=3
        apu.writeRegister(0x4003, (10 << 3)); // reload length and start envelope
        int len2 = apu.getPulse1Length();
        // Run another frame
        for (int i = 0; i < 14916; i++)
            apu.clockCpuCycle();
        // With halt=1, length should not decrement
        assertEquals(len2, apu.getPulse1Length());

        // Envelope should be active and not constant; volume <=15
        int vol = apu.getPulse1EnvelopeVolume();
        assertTrue(vol >= 0 && vol <= 15);
    }
}
