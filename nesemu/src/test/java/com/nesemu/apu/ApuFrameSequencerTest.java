package com.nesemu.apu;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

/**
 * Basic validation for APU frame sequencer 4-step and 5-step schedules.
 */
public class ApuFrameSequencerTest {

    @Test
    public void fourStep_generatesQuarterAndHalf_andSetsFrameIrq() {
        APU apu = new APU();
        apu.reset();
        // 4-step by default; IRQ not inhibited; run ~ 29832 2x cycles => 14916 cpu
        // cycles
        int targetCpu = 14916; // one full 4-step sequence
        for (int i = 0; i < targetCpu; i++)
            apu.clockCpuCycle();
        // In 4-step sequence, we expect frame IRQ set at end
        assertTrue(apu.isFrameIrq(), "Frame IRQ should be set at end of 4-step sequence");
        // Quarter ticks should be 4; half ticks should be 2
        assertEquals(4, apu.getQuarterTickCount(), "Quarter-frame ticks count");
        assertEquals(2, apu.getHalfTickCount(), "Half-frame ticks count");
        // Reading status should clear frame IRQ
        int st = apu.readStatus();
        assertTrue((st & 0x40) != 0, "Status bit6 should be 1 before clear");
        assertFalse(apu.isFrameIrq(), "Frame IRQ cleared after read");
    }

    @Test
    public void fiveStep_hasNoFrameIrq_andTicksPattern() {
        APU apu = new APU();
        apu.reset();
        // write $4017 with bit7=1 (five-step), bit6=0 (allow IRQ but none should be
        // produced in this mode)
        apu.writeRegister(0x4017, 0x80);
        int targetCpu = 18642; // approx one 5-step cycle length in CPU cycles
        for (int i = 0; i < targetCpu; i++)
            apu.clockCpuCycle();
        assertFalse(apu.isFrameIrq(), "No frame IRQ in five-step mode");
        assertTrue(apu.getQuarterTickCount() >= 5, "Quarter ticks occur in five-step");
        assertTrue(apu.getHalfTickCount() >= 2, "Half ticks occur in five-step");
    }
}
