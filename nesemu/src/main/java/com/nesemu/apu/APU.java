package com.nesemu.apu;

import com.nesemu.apu.interfaces.NesAPU;

/**
 * NES APU (2A03) skeleton: register latches + frame/status basics.
 * This is a minimal starting point; it does not generate audio yet.
 */
public class APU implements NesAPU {
    // Register latches ($4000-$4017)
    private final int[] regs = new int[0x18];

    // Status/IRQ flags (for $4015)
    private boolean frameIrq; // set by 4-step mode when IRQ enabled
    private boolean dmcIrq; // set by DMC when enabled and sample ends

    // Frame counter state ($4017)
    private boolean fiveStepMode; // bit 7
    private boolean irqInhibit; // bit 6
    private int frameCycle; // CPU cycles since last frame step (conceptual)

    // Timing: maintain a double-speed CPU cycle counter so we can hit .5 cycle
    // events
    private int cpuCycles2x; // increments by +2 per CPU cycle
    private int eventIndex; // current event within sequence
    private int nextEventAt2x; // absolute time (2x cycles) for next event
    // APU main timers clock at CPU/2. We toggle this each CPU cycle.
    private boolean apuTickPhase;

    // ---- Mixer / Sampling ----
    private int sampleRate = 44100; // Hz
    private int cpuClockHz = 1789773; // NTSC CPU clock
    private int sampleInterval2xUnits; // number of 2x units between samples
    private int sampleAccum2xUnits; // accumulator of 2x units
    private float[] sampleBuf = new float[8192];
    private int sampleWriteIdx = 0;
    private int sampleReadIdx = 0;

    // Debug/testing counters
    private int quarterTickCount;
    private int halfTickCount;

    // ---- Envelope generators (pulse1, pulse2, noise) ----
    private static class Envelope {
        int period; // 0..15 from reg low nibble
        boolean constantVolume; // bit4
        boolean loop; // bit5 (also halts length when set on pulse/noise)
        boolean start;
        int divider;
        int decay; // 0..15

        int getOutput() {
            return constantVolume ? period : decay;
        }

        void quarterTick() {
            if (start) {
                start = false;
                decay = 15;
                divider = period;
            } else {
                if (divider == 0) {
                    divider = period;
                    if (decay > 0)
                        decay--;
                    else if (loop)
                        decay = 15;
                } else {
                    divider--;
                }
            }
        }

        void reloadFromReg(int regVal) {
            period = regVal & 0x0F;
            constantVolume = (regVal & 0x10) != 0;
            loop = (regVal & 0x20) != 0;
        }
    }

    private final Envelope envP1 = new Envelope();
    private final Envelope envP2 = new Envelope();
    private final Envelope envNoise = new Envelope();

    // ---- Length counters ----
    private int lenP1, lenP2, lenTri, lenNoise;
    private boolean enaP1, enaP2, enaTri, enaNoise;
    private static final int[] LENGTH_TABLE = {
            10, 254, 20, 2, 40, 4, 80, 6,
            160, 8, 60, 10, 14, 12, 26, 14,
            12, 16, 24, 18, 48, 20, 96, 22,
            192, 24, 72, 26, 16, 28, 32, 30
    };

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
        apuTickPhase = false;
        lenP1 = lenP2 = lenTri = lenNoise = 0;
        enaP1 = enaP2 = enaTri = enaNoise = false;
        envP1.period = envP2.period = envNoise.period = 0;
        envP1.constantVolume = envP2.constantVolume = envNoise.constantVolume = false;
        envP1.loop = envP2.loop = envNoise.loop = false;
        envP1.start = envP2.start = envNoise.start = false;
        envP1.divider = envP2.divider = envNoise.divider = 0;
        envP1.decay = envP2.decay = envNoise.decay = 0;
        // Triangle linear counter
        triLinearValue = 0;
        triLinearControl = false;
        triLinearReloadFlag = false;
        triLinearCounter = 0;
        // Triangle timer/sequencer
        triTimer = 0;
        triTimerCounter = 0;
        triStep = 0;
        // Noise timer/LFSR
        noisePeriodIdx = 0;
        noiseModeShort = false;
        noiseTimerCounter = NOISE_PERIODS[0];
        noiseLfsr = 1; // any non-zero; hardware powers up non-zero
        // Pulse timers and sweep state
        p1Timer = p2Timer = 0;
        p1Muted = p2Muted = false;
        sw1 = new Sweep(1);
        sw2 = new Sweep(2);
        // Pulse duty/phase/timer counters
        p1Duty = p2Duty = 0;
        p1DutyStep = p2DutyStep = 0;
        p1TimerCounter = p2TimerCounter = 0;
        // Mixer timing
        recomputeSampleInterval();
        sampleAccum2xUnits = 0;
        sampleWriteIdx = sampleReadIdx = 0;
        scheduleNextEvent();
    }

    @Override
    public void clockCpuCycle() {
        // Advance 2x cycle counter (to handle .5 cycle event times)
        cpuCycles2x += 2;
        // Clock APU timers at CPU/2
        apuTickPhase = !apuTickPhase;
        if (apuTickPhase) {
            clockChannelTimers();
        }
        // Fire events in order; loop guards against missed events (shouldn't happen
        // with +2 increments)
        while (cpuCycles2x >= nextEventAt2x) {
            onSequencerEvent(eventIndex);
            advanceSequencer();
        }
        // Sampling: generate samples at fixed rate based on 2x units
        sampleAccum2xUnits += 2; // each CPU cycle adds two 2x units
        if (sampleAccum2xUnits >= sampleInterval2xUnits) {
            sampleAccum2xUnits -= sampleInterval2xUnits;
            float s = mixOutputSample();
            writeSample(s);
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
            enaP1 = (value & 0x01) != 0;
            enaP2 = (value & 0x02) != 0;
            enaTri = (value & 0x04) != 0;
            enaNoise = (value & 0x08) != 0;
            if (!enaP1)
                lenP1 = 0;
            if (!enaP2)
                lenP2 = 0;
            if (!enaTri)
                lenTri = 0;
            if (!enaNoise)
                lenNoise = 0;
        } else if (address == 0x4000) {
            envP1.reloadFromReg(value);
            p1Duty = (value >> 6) & 0x03;
        } else if (address == 0x4004) {
            envP2.reloadFromReg(value);
            p2Duty = (value >> 6) & 0x03;
        } else if (address == 0x400C) {
            envNoise.reloadFromReg(value);
        } else if (address == 0x400E) { // Noise: mode and period index
            noiseModeShort = (value & 0x80) != 0;
            noisePeriodIdx = (value & 0x0F);
            // do not reset counter immediately; it reloads on next tick when hits zero
        } else if (address == 0x4001) { // Pulse1 sweep
            if (sw1 == null)
                sw1 = new Sweep(1);
            sw1.write(value & 0xFF);
        } else if (address == 0x4005) { // Pulse2 sweep
            if (sw2 == null)
                sw2 = new Sweep(2);
            sw2.write(value & 0xFF);
        } else if (address == 0x4002) { // Pulse1 timer low
            p1Timer = (p1Timer & 0x700) | (value & 0xFF);
            updatePulseMuteFlags();
        } else if (address == 0x4003) { // Pulse1 length reload + envelope restart
            int idxLen = (value >> 3) & 0x1F;
            lenP1 = LENGTH_TABLE[idxLen];
            envP1.start = true;
            if (!enaP1)
                lenP1 = 0;
            // timer high
            p1Timer = ((value & 0x07) << 8) | (p1Timer & 0xFF);
            updatePulseMuteFlags();
            if (sw1 != null)
                sw1.onTimerOrRegChange();
            // Reset duty sequencer and timer counter per hardware behavior
            p1DutyStep = 0;
            p1TimerCounter = p1Timer & 0x7FF;
        } else if (address == 0x4006) { // Pulse2 timer low
            p2Timer = (p2Timer & 0x700) | (value & 0xFF);
            updatePulseMuteFlags();
        } else if (address == 0x4007) { // Pulse2
            int idxLen = (value >> 3) & 0x1F;
            lenP2 = LENGTH_TABLE[idxLen];
            envP2.start = true;
            if (!enaP2)
                lenP2 = 0;
            // timer high
            p2Timer = ((value & 0x07) << 8) | (p2Timer & 0xFF);
            updatePulseMuteFlags();
            if (sw2 != null)
                sw2.onTimerOrRegChange();
            p2DutyStep = 0;
            p2TimerCounter = p2Timer & 0x7FF;
        } else if (address == 0x4008) { // Triangle linear counter setup
            triLinearControl = (value & 0x80) != 0;
            triLinearValue = (value & 0x7F);
            // Note: reload flag is NOT set by $4008 per NES spec
        } else if (address == 0x400A) { // Triangle timer low
            triTimer = (triTimer & 0x700) | (value & 0xFF);
        } else if (address == 0x400B) { // Triangle
            int idxLen = (value >> 3) & 0x1F;
            lenTri = LENGTH_TABLE[idxLen];
            if (!enaTri)
                lenTri = 0;
            // Writing $400B sets the triangle linear counter reload flag
            triLinearReloadFlag = true;
            // Timer high for triangle (3 bits)
            triTimer = ((value & 0x07) << 8) | (triTimer & 0xFF);
            // Common practice: reset sequencer phase and counter
            triStep = 0;
            triTimerCounter = triTimer & 0x7FF;
        } else if (address == 0x400F) { // Noise length reload + envelope restart
            int idxLen = (value >> 3) & 0x1F;
            lenNoise = LENGTH_TABLE[idxLen];
            envNoise.start = true;
            if (!enaNoise)
                lenNoise = 0;
            // Common practice: (optionally) reset timer counter for immediate cadence
            noiseTimerCounter = NOISE_PERIODS[noisePeriodIdx & 0x0F];
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
        // Bits 0..3: length counter >0 for Pulse1, Pulse2, Triangle, Noise
        if (lenP1 > 0)
            status |= 0x01;
        if (lenP2 > 0)
            status |= 0x02;
        if (lenTri > 0)
            status |= 0x04;
        if (lenNoise > 0)
            status |= 0x08;
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
        envP1.quarterTick();
        envP2.quarterTick();
        envNoise.quarterTick();
        // Triangle linear counter
        if (triLinearReloadFlag) {
            triLinearCounter = triLinearValue;
        } else if (triLinearCounter > 0) {
            triLinearCounter--;
        }
        if (!triLinearControl) {
            triLinearReloadFlag = false; // cleared only when control=0
        }
    }

    private void halfFrameTick() {
        halfTickCount++;
        // Length counters decrement when >0 and not halted
        boolean p1Halt = (regs[0x000] & 0x20) != 0;
        boolean p2Halt = (regs[0x004] & 0x20) != 0;
        boolean noiHalt = (regs[0x00C] & 0x20) != 0;
        boolean triHalt = (regs[0x008] & 0x80) != 0; // control bit 7
        if (lenP1 > 0 && !p1Halt)
            lenP1--;
        if (lenP2 > 0 && !p2Halt)
            lenP2--;
        if (lenTri > 0 && !triHalt)
            lenTri--;
        if (lenNoise > 0 && !noiHalt)
            lenNoise--;
        // Sweep units tick on half frames
        if (sw1 != null)
            sw1.halfTick();
        if (sw2 != null)
            sw2.halfTick();
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

    // Test helpers
    public int getPulse1EnvelopeVolume() {
        return envP1.getOutput();
    }

    public int getPulse1Length() {
        return lenP1;
    }

    // ---- Additional test helpers ----
    public int getTriangleLinearCounter() {
        return triLinearCounter;
    }

    public int getPulse1Timer() {
        return p1Timer & 0x7FF;
    }

    public boolean isPulse1Muted() {
        return p1Muted;
    }

    public boolean isPulse2Muted() {
        return p2Muted;
    }

    // ---- Triangle linear counter fields ----
    private int triLinearValue;
    private boolean triLinearControl;
    private boolean triLinearReloadFlag;
    private int triLinearCounter;

    // ---- Pulse timers and sweep ----
    private int p1Timer, p2Timer; // 11-bit timers
    private boolean p1Muted, p2Muted;
    private Sweep sw1, sw2;
    // Pulse duty/phase and downcounters
    private int p1Duty, p2Duty; // 0..3
    private int p1DutyStep, p2DutyStep; // 0..7
    private int p1TimerCounter, p2TimerCounter; // downcounter

    private void updatePulseMuteFlags() {
        p1Muted = (p1Timer <= 8) || (p1Timer > 0x7FF);
        p2Muted = (p2Timer <= 8) || (p2Timer > 0x7FF);
    }

    private static final int[][] DUTY_TABLE = new int[][] {
            // 8-step sequences per NES spec
            { 0, 1, 0, 0, 0, 0, 0, 0 }, // 12.5%
            { 0, 1, 1, 0, 0, 0, 0, 0 }, // 25%
            { 0, 1, 1, 1, 1, 0, 0, 0 }, // 50%
            { 1, 0, 0, 1, 1, 1, 1, 1 } // 75%
    };

    private void clockChannelTimers() {
        // Pulse 1
        if (!p1Muted && lenP1 > 0) {
            if (p1TimerCounter == 0) {
                p1TimerCounter = (p1Timer & 0x7FF);
                p1DutyStep = (p1DutyStep + 1) & 7;
            } else {
                p1TimerCounter--;
            }
        }
        // Pulse 2
        if (!p2Muted && lenP2 > 0) {
            if (p2TimerCounter == 0) {
                p2TimerCounter = (p2Timer & 0x7FF);
                p2DutyStep = (p2DutyStep + 1) & 7;
            } else {
                p2TimerCounter--;
            }
        }
        // Triangle
        if (lenTri > 0) {
            if (triTimerCounter == 0) {
                triTimerCounter = (triTimer & 0x7FF);
                if (triLinearCounter > 0 && (triTimer & 0x7FF) >= 2) {
                    triStep = (triStep + 1) & 31; // 0..31
                }
            } else {
                triTimerCounter--;
            }
        }
        // Noise
        if (lenNoise > 0) {
            if (noiseTimerCounter <= 0) {
                noiseTimerCounter = NOISE_PERIODS[noisePeriodIdx & 0x0F];
                // Shift LFSR
                int bit0 = noiseLfsr & 1;
                int tap = noiseModeShort ? ((noiseLfsr >> 6) & 1) : ((noiseLfsr >> 1) & 1);
                int feedback = bit0 ^ tap;
                noiseLfsr >>= 1;
                noiseLfsr |= (feedback << 14); // into bit 14 of 15-bit reg
                noiseLfsr &= 0x7FFF; // keep 15 bits
            } else {
                noiseTimerCounter--;
            }
        }
    }

    // Minimal test helper outputs
    public int getPulse1DutyStep() {
        return p1DutyStep;
    }

    public int getPulse2DutyStep() {
        return p2DutyStep;
    }

    public int getPulse1OutputLevel() {
        // Returns the instantaneous DAC level for Pulse1 (without mixer curve)
        if (p1Muted || lenP1 == 0)
            return 0;
        int bit = DUTY_TABLE[p1Duty][p1DutyStep];
        return bit == 1 ? envP1.getOutput() : 0;
    }

    public int getPulse2OutputLevel() {
        if (p2Muted || lenP2 == 0)
            return 0;
        int bit = DUTY_TABLE[p2Duty][p2DutyStep];
        return bit == 1 ? envP2.getOutput() : 0;
    }

    // ---- Triangle state ----
    private int triTimer; // 11-bit
    private int triTimerCounter; // downcounter
    private int triStep; // 0..31

    private static final int[] TRI_TABLE = new int[] {
            15, 14, 13, 12, 11, 10, 9, 8,
            7, 6, 5, 4, 3, 2, 1, 0,
            0, 1, 2, 3, 4, 5, 6, 7,
            8, 9, 10, 11, 12, 13, 14, 15
    };

    // Minimal test helper for triangle
    public int getTriangleTimer() {
        return triTimer & 0x7FF;
    }

    public int getTriangleStep() {
        return triStep & 31;
    }

    public int getTriangleOutputLevel() {
        if (lenTri == 0 || (triTimer & 0x7FF) < 2)
            return 0;
        // Output even if linear is zero; sequencer halts but level remains last
        return TRI_TABLE[triStep];
    }

    // ---- Mixer helpers ----
    private void recomputeSampleInterval() {
        // 2x-units per second = 2 * cpuClockHz; interval per sample is that /
        // sampleRate
        double interval = (2.0 * cpuClockHz) / (double) sampleRate;
        sampleInterval2xUnits = (int) Math.max(1, Math.round(interval));
    }

    private float mixOutputSample() {
        // instantaneous DAC-like levels
        int p1 = getPulse1OutputLevel(); // 0..15
        int p2 = getPulse2OutputLevel(); // 0..15
        int tri = getTriangleOutputLevel(); // 0..15
        int noi = getNoiseOutputLevel(); // 0..15
        double pulseSum = p1 + p2;
        double pulseOut = (pulseSum > 0)
                ? 95.88 / (8128.0 / pulseSum + 100.0)
                : 0.0;
        double tndDen = (tri / 8227.0) + (noi / 12241.0); // DMC ignored for now
        double tndOut = (tndDen > 0)
                ? 159.79 / (1.0 / tndDen + 100.0)
                : 0.0;
        double out = pulseOut + tndOut; // approx 0..1
        // clamp to [0,1]
        if (out < 0)
            out = 0;
        if (out > 1)
            out = 1;
        return (float) out;
    }

    private void writeSample(float s) {
        int next = (sampleWriteIdx + 1) & (sampleBuf.length - 1);
        if (next != sampleReadIdx) { // drop if buffer full
            sampleBuf[sampleWriteIdx] = s;
            sampleWriteIdx = next;
        }
    }

    // Public sampling API for consumers/tests
    public void setSampleRate(int hz) {
        if (hz <= 1000)
            return;
        this.sampleRate = hz;
        recomputeSampleInterval();
    }

    public void setCpuClockHz(int hz) {
        if (hz <= 100000)
            return;
        this.cpuClockHz = hz;
        recomputeSampleInterval();
    }

    public int getPendingSampleCount() {
        int diff = sampleWriteIdx - sampleReadIdx;
        if (diff < 0)
            diff += sampleBuf.length;
        return diff;
    }

    public float readSample() {
        if (sampleReadIdx == sampleWriteIdx)
            return 0f;
        float v = sampleBuf[sampleReadIdx];
        sampleReadIdx = (sampleReadIdx + 1) & (sampleBuf.length - 1);
        return v;
    }

    // ---- Noise state ----
    private static final int[] NOISE_PERIODS = new int[] {
            4, 8, 16, 32, 64, 96, 128, 160,
            202, 254, 380, 508, 762, 1016, 2034, 4068
    };
    private int noisePeriodIdx; // 0..15
    private boolean noiseModeShort; // true => short mode (tap 6)
    private int noiseTimerCounter; // downcounter
    private int noiseLfsr; // 15-bit LFSR

    public int getNoiseOutputLevel() {
        if (lenNoise == 0)
            return 0;
        // If bit0 is 1, output is 0; else envelope output
        int b0 = noiseLfsr & 1;
        return b0 == 0 ? envNoise.getOutput() : 0;
    }

    // For tests / debug
    public int getNoiseLfsrBit0() {
        return noiseLfsr & 1;
    }

    private final class Sweep {
        final int ch; // 1 or 2
        boolean enabled;
        int period; // 0..7
        boolean negate;
        int shift; // 0..7
        int divider;
        boolean reload;

        Sweep(int ch) {
            this.ch = ch;
        }

        void write(int v) {
            enabled = (v & 0x80) != 0;
            period = (v >> 4) & 0x07;
            negate = (v & 0x08) != 0;
            shift = (v & 0x07);
            // Reload divider immediately so the countdown starts on the next half-frame
            // tick (hardware appears to make a sweep happen within one frame when
            // period=1 per our tests). We avoid applying a sweep on this same tick by
            // not flagging a pending reload for halfTick().
            divider = period;
            reload = false;
        }

        void onTimerOrRegChange() {
            // No-op here; placeholder if we need to recompute mute
            updatePulseMuteFlags();
        }

        void halfTick() {
            boolean doSweep = false;
            if (divider == 0) {
                doSweep = true;
            } else {
                divider--;
            }
            if (reload) {
                divider = period;
                reload = false;
                // According to hardware, when reloading divider, sweep isn't applied this tick
                doSweep = false;
            } else if (doSweep) {
                divider = period;
            }

            if (doSweep && enabled && shift > 0) {
                int cur = (ch == 1) ? p1Timer : p2Timer;
                int delta = cur >> shift;
                int target;
                if (negate) {
                    target = cur - delta - (ch == 1 ? 1 : 0);
                } else {
                    target = cur + delta;
                }
                boolean overflow = target > 0x7FF || target < 0;
                boolean mute = (cur < 8) || overflow;
                if (!mute) {
                    if (ch == 1) {
                        p1Timer = target & 0x7FF;
                    } else {
                        p2Timer = target & 0x7FF;
                    }
                }
                if (ch == 1)
                    p1Muted = mute;
                else
                    p2Muted = mute;
            }
        }
    }

    public int getFrameCycle() {
        return frameCycle;
    }
}
