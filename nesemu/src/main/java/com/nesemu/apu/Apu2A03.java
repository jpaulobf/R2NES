package com.nesemu.apu;

/**
 * NES APU (2A03) skeleton: register latches + frame/status basics.
 * This is a minimal starting point; it does not generate audio yet.
 */
public class Apu2A03 implements APU {
    // Register latches ($4000-$4017)
    private final int[] regs = new int[0x18];

    // Status/IRQ flags (for $4015)
    private boolean frameIrq; // set by 4-step mode when IRQ enabled
    private boolean dmcIrq; // set by DMC when enabled and sample ends

    // Frame counter state ($4017)
    @SuppressWarnings("unused")
    private boolean fiveStepMode; // bit 7
    private boolean irqInhibit; // bit 6
    @SuppressWarnings("unused")
    private int frameCycle; // CPU cycles since last frame step (conceptual)

    // Timing: maintain a double-speed CPU cycle counter so we can hit .5 cycle
    // events
    private int cpuCycles2x; // increments by +2 per CPU cycle
    private int eventIndex; // current event within sequence
    private int nextEventAt2x; // absolute time (2x cycles) for next event

    // Debug/testing counters
    private int quarterTickCount;
    private int halfTickCount;

    @Override
    public void reset() {
        for (int i = 0; i < regs.length; i++)
            regs[i] = 0;
        frameIrq = false;
        dmcIrq = false;
        fiveStepMode = false;
        irqInhibit = false;
        frameCycle = 0;
        cpuCycles2x = 0;
        eventIndex = 0;
        quarterTickCount = 0;
        halfTickCount = 0;
        scheduleNextEvent();
    }

    @Override
    public void clockCpuCycle() {
        // Advance 2x cycle counter (to handle .5 cycle event times)
        cpuCycles2x += 2;
        // Fire events in order; loop guards against missed events (shouldn't happen
        // with +2 increments)
        while (cpuCycles2x >= nextEventAt2x) {
            onSequencerEvent(eventIndex);
            advanceSequencer();
        }
    }

    @Override
    public void writeRegister(int address, int value) {
        int idx = (address - 0x4000) & 0xFF;
        if (idx < 0 || idx >= regs.length)
            return;
        regs[idx] = value & 0xFF;
        if (address == 0x4015) {
            // Channel enables + DMC enable. For now only latch; clearing length counters
            // later.
            // Writing 0 clears DMC IRQ; writing 1 enables DMC (to be implemented).
            dmcIrq = false; // $4015 write clears DMC IRQ
        } else if (address == 0x4017) {
            // Frame counter mode: bit7=1 five-step, bit6=IRQ inhibit
            fiveStepMode = ((value & 0x80) != 0);
            irqInhibit = ((value & 0x40) != 0);
            if (irqInhibit)
                frameIrq = false; // clear frame IRQ when inhibited
            // Reset sequencer timing
            frameCycle = 0;
            cpuCycles2x = 0;
            eventIndex = 0;
            scheduleNextEvent();
        }
    }

    @Override
    public int readStatus() {
        int status = 0;
        // Bits 0..3: length counter >0 for Pulse1, Pulse2, Triangle, Noise (TODO)
        // Bit 4: DMC active (TODO)
        if (dmcIrq)
            status |= 0x80; // bit7: DMC IRQ
        if (frameIrq)
            status |= 0x40; // bit6: Frame IRQ
        // Clear frame IRQ on read
        frameIrq = false;
        return status & 0xFF;
    }

    // ----- Frame sequencer helpers -----

    // Event times in 2x CPU cycles (handles .5 cycle resolution)
    // 4-step: 3729.5, 7456.5, 11186.5, 14915.5
    private static final int[] FOUR_STEP_EVENTS_2X = { 7459, 14913, 22373, 29831 };
    // 5-step: 3729.5, 7456.5, 11186.5, 14916.5, 18641.5 (no IRQ)
    private static final int[] FIVE_STEP_EVENTS_2X = { 7459, 14913, 22373, 29833, 37283 };

    private void scheduleNextEvent() {
        int[] table = fiveStepMode ? FIVE_STEP_EVENTS_2X : FOUR_STEP_EVENTS_2X;
        nextEventAt2x = table[eventIndex];
    }

    private void advanceSequencer() {
        int[] table = fiveStepMode ? FIVE_STEP_EVENTS_2X : FOUR_STEP_EVENTS_2X;
        eventIndex++;
        if (eventIndex >= table.length) {
            // Sequence end: in 4-step, set frame IRQ unless inhibited; in 5-step no IRQ
            if (!fiveStepMode && !irqInhibit) {
                frameIrq = true;
            }
            // wrap to start of next sequence
            eventIndex = 0;
            cpuCycles2x -= table[table.length - 1];
        }
        scheduleNextEvent();
    }

    private void onSequencerEvent(int idx) {
        if (fiveStepMode) {
            switch (idx) {
                case 0, 2, 4 -> quarterFrameTick();
                case 1, 3 -> {
                    quarterFrameTick();
                    halfFrameTick();
                }
            }
        } else { // 4-step
            switch (idx) {
                case 0, 2 -> quarterFrameTick();
                case 1, 3 -> {
                    quarterFrameTick();
                    halfFrameTick();
                }
            }
        }
    }

    private void quarterFrameTick() {
        quarterTickCount++;
        // TODO: tick envelopes + linear counter here
    }

    private void halfFrameTick() {
        halfTickCount++;
        // TODO: tick length counters + sweep here
    }

    // ---- Test helpers (expose counters) ----
    public int getQuarterTickCount() {
        return quarterTickCount;
    }

    public int getHalfTickCount() {
        return halfTickCount;
    }

    public boolean isFrameIrq() {
        return frameIrq;
    }
}
