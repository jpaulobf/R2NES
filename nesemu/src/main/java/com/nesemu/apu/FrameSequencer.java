package com.nesemu.apu;

/**
 * NES APU frame sequencer (NTSC) handling 4-step and 5-step modes with
 * half/quarter frame ticks and frame IRQ generation.
 * Operates in 2x CPU cycle resolution (each CPU cycle = +2 units) to support
 * the .5 cycle event timings.
 */
final class FrameSequencer {

    /** Listener for quarter/half frame events. */
    interface Listener {
        void quarterFrameTick();
        void halfFrameTick();
    }

    // Event times in 2x CPU cycles (handles .5 cycle resolution)
    // 4-step: 3729.5, 7456.5, 11186.5, 14915.5 CPU cycles
    // (doubled and rounded to nearest int):
    private static final int[] FOUR_STEP_EVENTS_2X = { 7459, 14913, 22373, 29831 }; // sequence length end wrap
    // 5-step: 3729.5, 7456.5, 11186.5, 14916.5, 18641.5 (no IRQ)
    private static final int[] FIVE_STEP_EVENTS_2X = { 7459, 14913, 22373, 29833, 37283 };

    private final Listener listener;

    private boolean fiveStepMode; // bit7 of $4017
    private boolean irqInhibit;   // bit6 of $4017
    private boolean frameIrq;     // latched when 4-step completes and not inhibited

    private int cycles2x;   // accumulated 2x cycles since start of sequence
    private int eventIndex; // current event index
    private int nextEventAt2x; // absolute 2x cycle time of next event

    FrameSequencer(Listener listener) {
        this.listener = listener;
        reset();
    }

    void reset() {
        fiveStepMode = false;
        irqInhibit = false;
        frameIrq = false;
        cycles2x = 0;
        eventIndex = 0;
        scheduleNext();
    }

    /**
     * Advance by given 2x-cycle delta (typically +2 per CPU cycle) and emit events.
     */
    void step2x(int delta) {
        cycles2x += delta;
        while (cycles2x >= nextEventAt2x) {
            fireEvent(eventIndex);
            advance();
        }
    }

    /** Write control ($4017) and apply immediate effects. */
    void writeControl(int value) {
        boolean newFive = (value & 0x80) != 0;
        boolean newInhibit = (value & 0x40) != 0;

        fiveStepMode = newFive;
        irqInhibit = newInhibit;
        if (irqInhibit) {
            frameIrq = false; // clear on inhibit per spec
        }
        // Reset sequence timing
        cycles2x = 0;
        eventIndex = 0;
        scheduleNext();

        // Immediate tick behavior: if switching to / using 5-step mode (bit7=1)
        // the hardware immediately clocks both quarter and half frame units.
        if (fiveStepMode) {
            listener.quarterFrameTick();
            listener.halfFrameTick();
        }
    }

    boolean isFrameIrq() {
        return frameIrq;
    }

    void clearFrameIrq() {
        frameIrq = false;
    }

    private void scheduleNext() {
        int[] table = fiveStepMode ? FIVE_STEP_EVENTS_2X : FOUR_STEP_EVENTS_2X;
        nextEventAt2x = table[eventIndex];
    }

    private void advance() {
        int[] table = fiveStepMode ? FIVE_STEP_EVENTS_2X : FOUR_STEP_EVENTS_2X;
        eventIndex++;
        if (eventIndex >= table.length) {
            // Sequence end: 4-step sets frame IRQ (if not inhibited); 5-step none.
            if (!fiveStepMode && !irqInhibit) {
                frameIrq = true;
            }
            eventIndex = 0;
            // Wrap cycles so we keep relative timing small.
            cycles2x -= table[table.length - 1];
        }
        scheduleNext();
    }

    private void fireEvent(int idx) {
        if (fiveStepMode) {
            switch (idx) {
                case 0, 2, 4 -> listener.quarterFrameTick();
                case 1, 3 -> { listener.quarterFrameTick(); listener.halfFrameTick(); }
            }
        } else { // 4-step
            switch (idx) {
                case 0, 2 -> listener.quarterFrameTick();
                case 1, 3 -> { listener.quarterFrameTick(); listener.halfFrameTick(); }
            }
        }
    }
}
