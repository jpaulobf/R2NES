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
    private boolean dmcIrq; // set by DMC when enabled and sample ends (not yet implemented)

    // ---- DMC (Delta Modulation Channel) minimal state
    private boolean enaDmc; // enabled via $4015 bit4
    private boolean dmcLoop; // $4010 bit6
    private boolean dmcIrqEnable; // $4010 bit7
    private int dmcRateIndex; // lower 4 bits of $4010
    private int dmcDirectLoad; // $4011 initial DAC value (0..127)
    private int dmcSampleAddr; // $4012 -> 0xC000 + value*64
    private int dmcSampleLen; // $4013 -> value*16 + 1

    // runtime DMC state
    private int dmcCurrentAddress;
    private int dmcBytesRemaining;
    private int dmcSampleBuffer;
    private boolean dmcSampleBufferAvailable;
    private int dmcShiftReg;
    private int dmcBitsRemaining;
    private int dmcOutputLevel; // 0..127
    private int dmcTimerCounter; // counts down CPU cycles until next bit
    private boolean dmcRequest; // CPU should fetch when true (APU provides supplyDmcSampleByte)

    private static final int[] DMC_RATES = new int[] {
            428, 380, 340, 320, 286, 254, 226, 214,
            190, 160, 142, 128, 106, 85, 72, 54
    };

    // Frame sequencer extracted to dedicated class
    private final FrameSequencer frameSequencer = new FrameSequencer(new FrameSequencer.Listener() {
        @Override
        public void quarterFrameTick() {
            APU.this.quarterFrameTick();
        }

        @Override
        public void halfFrameTick() {
            APU.this.halfFrameTick();
        }
    });

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
    private float prevSample = 0.0f; // for linear interpolation between samples

    // Output filters to simulate NES analog response
    private double hpfPrevIn = 0.0;
    private double hpfPrevOut = 0.0;
    private double lpfPrevOut = 0.0;
    // HPF cutoff ~20 Hz, LPF cutoff ~14 kHz at 44.1kHz sample rate
    private static final double HPF_ALPHA = 0.998; // low cutoff
    private static final double LPF_ALPHA = 0.85; // approx 14kHz at 44.1kHz

    // ---- Envelope generators ----
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
    // Bandlimited pulse synthesis state (polyBLEP)
    private double p1Phase, p2Phase; // normalized phase [0,1)
    private static final double[] DUTY_FRACTIONS = new double[] { 0.125, 0.25, 0.5, 0.75 };

    // ---- Triangle state ----
    private int triTimer; // 11-bit
    private int triTimerCounter; // downcounter
    private int triStep; // 0..31

    // ---- Noise state ----
    private int noisePeriodIdx; // 0..15
    private boolean noiseModeShort; // true => short mode (tap 6)
    private int noiseTimerCounter; // downcounter
    private int noiseLfsr; // 15-bit LFSR

    // Duty cycle table for pulse channels
    private static final int[][] DUTY_TABLE = new int[][] {
            // 8-step sequences per NES spec
            { 0, 1, 0, 0, 0, 0, 0, 0 }, // 12.5%
            { 0, 1, 1, 0, 0, 0, 0, 0 }, // 25%
            { 0, 1, 1, 1, 1, 0, 0, 0 }, // 50%
            { 1, 0, 0, 1, 1, 1, 1, 1 } // 75%
    };

    // Triangle output levels (0..15)
    private static final int[] TRI_TABLE = new int[] {
            15, 14, 13, 12, 11, 10, 9, 8,
            7, 6, 5, 4, 3, 2, 1, 0,
            0, 1, 2, 3, 4, 5, 6, 7,
            8, 9, 10, 11, 12, 13, 14, 15
    };

    // Noise periods in CPU/2 cycles (NTSC)
    private static final int[] NOISE_PERIODS = new int[] {
            4, 8, 16, 32, 64, 96, 128, 160,
            202, 254, 380, 508, 762, 1016, 2034, 4068
    };

    // Precomputed pulse mixer lookup table for sums 0..30 (p1+p2)
    private static final double[] PULSE_MIX_TABLE;
    static {
        PULSE_MIX_TABLE = new double[31];
        for (int i = 0; i < PULSE_MIX_TABLE.length; i++) {
            if (i == 0) {
                PULSE_MIX_TABLE[i] = 0.0;
            } else {
                PULSE_MIX_TABLE[i] = 95.88 / (8128.0 / (double) i + 100.0);
            }
        }
    }

    // Precomputed TND mixer table: tri(0..15), noise(0..15), dmc(0..127)
    private static final double[] TND_MIX_TABLE;
    static {
        TND_MIX_TABLE = new double[16 * 16 * 128];
        for (int tri = 0; tri < 16; tri++) {
            for (int noise = 0; noise < 16; noise++) {
                for (int dmc = 0; dmc < 128; dmc++) {
                    int idx = (tri * 16 + noise) * 128 + dmc;
                    double tri_contrib = tri / 8227.0;
                    double noise_contrib = noise / 12241.0;
                    double dmc_contrib = dmc / 22638.0;
                    double sum = tri_contrib + noise_contrib + dmc_contrib;
                    if (sum <= 0.0) {
                        TND_MIX_TABLE[idx] = 0.0;
                    } else {
                        TND_MIX_TABLE[idx] = 159.79 / (1.0 / sum + 100.0);
                    }
                }
            }
        }
    }

    // (Frame sequencer constants moved to FrameSequencer class)

    // ------ Debug/testing counters
    private int quarterTickCount;
    private int halfTickCount;

    @Override
    public void reset() {
        for (int i = 0; i < regs.length; i++)
            regs[i] = 0;
        // Frame / DMC IRQ
        dmcIrq = false;
        // DMC control/runtime init
        enaDmc = false;
        dmcLoop = false;
        dmcIrqEnable = false;
        dmcRateIndex = 0;
        dmcDirectLoad = 0;
        dmcSampleAddr = 0;
        dmcSampleLen = 0;
        dmcCurrentAddress = 0;
        dmcBytesRemaining = 0;
        dmcSampleBuffer = 0;
        dmcSampleBufferAvailable = false;
        dmcShiftReg = 0;
        dmcBitsRemaining = 0;
        dmcOutputLevel = 0;
        dmcTimerCounter = 0;
        dmcRequest = false;
        frameSequencer.reset();
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
        // Reset output filters
        hpfPrevIn = 0.0;
        hpfPrevOut = 0.0;
        lpfPrevOut = 0.0;
        prevSample = 0.0f;
    }

    @Override
    public void clockCpuCycle() {
        // Frame sequencer operates in 2x CPU cycle units
        frameSequencer.step2x(2);
        // Clock APU timers at CPU/2
        apuTickPhase = !apuTickPhase;
        if (apuTickPhase) {
            clockChannelTimers();
        }
        // Sampling: generate samples at fixed rate based on 2x units
        sampleAccum2xUnits += 2; // each CPU cycle adds two 2x units
        if (sampleAccum2xUnits >= sampleInterval2xUnits) {
            sampleAccum2xUnits -= sampleInterval2xUnits;
            float currentSample = mixOutputSample();
            // Linear interpolation between consecutive samples for smoother output
            float s = (prevSample + currentSample) / 2.0f;
            prevSample = currentSample;
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
            enaDmc = (value & 0x10) != 0;
            if (enaDmc && dmcBytesRemaining == 0) {
                // start sample if enabled
                dmcCurrentAddress = dmcSampleAddr;
                dmcBytesRemaining = dmcSampleLen;
                dmcSampleBufferAvailable = false;
                dmcBitsRemaining = 0;
                dmcRequest = true;
            }
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
        } else if (address == 0x4010) { // DMC control: irq/loop/rate
            dmcIrqEnable = (value & 0x80) != 0;
            dmcLoop = (value & 0x40) != 0;
            dmcRateIndex = value & 0x0F;
            // reload timer from rate table
            dmcTimerCounter = DMC_RATES[dmcRateIndex];
        } else if (address == 0x4011) { // DMC direct load (DAC)
            dmcDirectLoad = value & 0x7F;
            dmcOutputLevel = dmcDirectLoad;
        } else if (address == 0x4012) { // DMC sample address
            dmcSampleAddr = 0xC000 | ((value & 0xFF) << 6);
        } else if (address == 0x4013) { // DMC sample length
            dmcSampleLen = ((value & 0xFF) << 4) + 1;
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
        } else if (address == 0x4017) { // Frame counter control
            frameSequencer.writeControl(value & 0xFF); // includes immediate ticks for 5-step
        }
    }

    /**
     * CPU provides a fetched byte for DMC via this API. CPU should call this
     * when APU sets dmcRequest = true.
     */
    public void supplyDmcSampleByte(int b) {
        dmcSampleBuffer = b & 0xFF;
        dmcSampleBufferAvailable = true;
        // CPU satisfied request
        dmcRequest = false;
        // advance address and decrement remaining count upon fetch
        if (dmcBytesRemaining > 0) {
            dmcBytesRemaining--;
            dmcCurrentAddress = (dmcCurrentAddress + 1) & 0xFFFF; // wrap 16-bit
        }
    }

    // Accessors for CPU-side DMC handling
    public boolean isDmcRequest() {
        return dmcRequest;
    }

    public int getDmcCurrentAddress() {
        return dmcCurrentAddress & 0xFFFF;
    }

    public int getDmcBytesRemaining() {
        return dmcBytesRemaining;
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
        // Bit 4: DMC active when any sample bytes or shift state pending
        if (enaDmc && (dmcBytesRemaining > 0 || dmcSampleBufferAvailable || dmcBitsRemaining > 0))
            status |= 0x10;
        if (dmcIrq)
            status |= 0x80; // bit7: DMC IRQ
        if (frameSequencer.isFrameIrq())
            status |= 0x40; // bit6: Frame IRQ
        // Clear frame IRQ on read (DMC IRQ not cleared here)
        frameSequencer.clearFrameIrq();
        return status & 0xFF;
    }

    /**
     * Handle quarter-frame tick: envelope and triangle linear counter
     * updates.
     */
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

    /**
     * Handle half-frame tick: length counter and sweep updates.
     */
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

    /**
     * Clock the channel timers (called at CPU/2 rate).
     */
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

        // DMC: timer counts in CPU cycles; when it hits zero we clock one bit
        if (enaDmc) {
            if (dmcTimerCounter <= 0) {
                dmcTimerCounter = DMC_RATES[Math.max(0, Math.min(DMC_RATES.length - 1, dmcRateIndex))];
                // If no bits remaining in shift reg, reload from sample buffer
                if (dmcBitsRemaining == 0) {
                    if (dmcSampleBufferAvailable) {
                        dmcShiftReg = dmcSampleBuffer & 0xFF;
                        dmcSampleBufferAvailable = false;
                        dmcBitsRemaining = 8;
                        // request next byte
                        dmcRequest = true;
                    } else {
                        // no data: silence or request
                        dmcRequest = true;
                    }
                }
                if (dmcBitsRemaining > 0) {
                    int bit = dmcShiftReg & 1;
                    if (bit == 1) {
                        if (dmcOutputLevel <= 125)
                            dmcOutputLevel += 2;
                    } else {
                        if (dmcOutputLevel >= 2)
                            dmcOutputLevel -= 2;
                    }
                    dmcShiftReg >>= 1;
                    dmcBitsRemaining--;
                    if (dmcBitsRemaining == 0) {
                        // when we've consumed a byte, decrement bytes remaining and start fetch
                        if (dmcBytesRemaining > 0)
                            dmcBytesRemaining--;
                        if (dmcBytesRemaining == 0) {
                            if (dmcLoop) {
                                dmcCurrentAddress = dmcSampleAddr;
                                dmcBytesRemaining = dmcSampleLen;
                            } else if (dmcIrqEnable) {
                                dmcIrq = true;
                            }
                        }
                    }
                }
            } else {
                dmcTimerCounter--;
            }
        }
    }

    /**
     * Recompute sample interval in 2x units based on current sample rate and CPU
     */
    private void recomputeSampleInterval() {
        // 2x-units per second = 2 * cpuClockHz; interval per sample is that /
        // sampleRate
        double interval = (2.0 * cpuClockHz) / (double) sampleRate;
        sampleInterval2xUnits = (int) Math.max(1, Math.round(interval));
    }

    /**
     * Mix the current output sample from all channels, applying the NES APU mixer
     * 
     * @return
     */
    private float mixOutputSample() {
        // instantaneous DAC-like levels
        double p1 = getBandlimitedPulseOutput(1); // 0..15
        double p2 = getBandlimitedPulseOutput(2); // 0..15
        int tri = getTriangleOutputLevel(); // 0..15
        int noi = getNoiseOutputLevel(); // 0..15
        double pulseSum = p1 + p2;
        // linear interpolation in pulse LUT
        double idx = pulseSum;
        int idxLow = (int) Math.max(0, Math.min(PULSE_MIX_TABLE.length - 1, Math.floor(idx)));
        int idxHigh = (int) Math.max(0, Math.min(PULSE_MIX_TABLE.length - 1, Math.ceil(idx)));
        double frac = idx - Math.floor(idx);
        double pulseOut = PULSE_MIX_TABLE[idxLow] * (1.0 - frac) + PULSE_MIX_TABLE[idxHigh] * frac;
        double dmc = (enaDmc ? (double) dmcOutputLevel : 0.0);
        double tndDen = (tri / 8227.0) + (noi / 12241.0) + (dmc / 22638.0);
        double tndOut = (tndDen > 0)
                ? 159.79 / (1.0 / tndDen + 100.0)
                : 0.0;
        double out = pulseOut + tndOut; // approx 0..1
        // Apply output filters to simulate NES analog response
        // LPF first (~8kHz cutoff)
        double lpfOut = LPF_ALPHA * out + (1.0 - LPF_ALPHA) * lpfPrevOut;
        lpfPrevOut = lpfOut;
        // HPF to remove DC (~10Hz cutoff)
        double hpfOut = HPF_ALPHA * (lpfOut + hpfPrevIn - hpfPrevOut);
        hpfPrevOut = hpfOut;
        hpfPrevIn = lpfOut;
        out = hpfOut;
        // clamp to [0,1]
        if (out < 0)
            out = 0;
        if (out > 1)
            out = 1;
        return (float) out;
    }

    /**
     * Soft clipping using normalized tanh to compress peaks gently.
     */
    private double softClip(double x) {
        // strength k: higher -> stronger compression
        double k = 2.0;
        double denom = Math.tanh(k);
        if (denom == 0.0)
            return x;
        return Math.tanh(x * k) / denom;
    }

    /**
     * Compute a band-limited pulse output for channel ch (1 or 2) in the same
     * 0..15 amplitude range used by envelope outputs. Uses polyBLEP to smooth
     * the rising/falling edges based on current channel frequency and sample
     * rate.
     */
    private double getBandlimitedPulseOutput(int ch) {
        int timer = (ch == 1) ? (p1Timer & 0x7FF) : (p2Timer & 0x7FF);
        boolean muted = (ch == 1) ? p1Muted : p2Muted;
        int len = (ch == 1) ? lenP1 : lenP2;
        int dutyIdx = (ch == 1) ? p1Duty : p2Duty;
        int envOut = (ch == 1) ? envP1.getOutput() : envP2.getOutput();

        if (muted || len == 0 || timer <= 0)
            return 0.0;

        // frequency in Hz for NES pulse channel: CPU clock / (16 * (timer+1))
        int period = timer + 1; // avoid zero
        double freq = cpuClockHz / (16.0 * (double) period);
        double phaseInc = freq / (double) sampleRate; // normalized phase increment
        // Note: no clamp on phaseInc to avoid artifacts at high frequencies

        // advance phase
        if (ch == 1) {
            p1Phase += phaseInc;
            if (p1Phase >= 1.0)
                p1Phase -= Math.floor(p1Phase);
        } else {
            p2Phase += phaseInc;
            if (p2Phase >= 1.0)
                p2Phase -= Math.floor(p2Phase);
        }

        double phase = (ch == 1) ? p1Phase : p2Phase;
        double duty = DUTY_FRACTIONS[Math.max(0, Math.min(DUTY_FRACTIONS.length - 1, dutyIdx))];

        // basic square wave (0 or 1) multiplied by envelope amplitude
        double amp = (double) envOut;
        double pulse = (phase < duty) ? amp : 0.0;

        // apply polyBLEP corrections at rising (phase=0) and falling (phase=duty)
        if (phaseInc > 0.0) {
            // rising edge at t=0
            pulse += amp * polyBLEP(phase, phaseInc);
            // falling edge at t=duty -> pass a wrapped phase
            double tFall = phase - duty;
            if (tFall < 0)
                tFall += 1.0;
            pulse -= amp * polyBLEP(tFall, phaseInc);
        }

        return pulse;
    }

    /**
     * Simple polyBLEP implementation for band-limiting a discontinuity at t in
     * [0,1) with transition width dt (both normalized to period).
     */
    private double polyBLEP(double t, double dt) {
        // canonical polyBLEP for a unit step at t=0, transition width dt
        // protect dt: it must be >0 and significantly <1
        dt = Math.max(1e-12, Math.min(dt, 0.5 - 1e-12));
        if (t < 0.0 || t >= 1.0)
            return 0.0;
        if (t < dt) {
            double x = t / dt;
            return x + x - x * x - 1.0; // smooth ramp start
        } else if (t > 1.0 - dt) {
            double x = (t - 1.0) / dt;
            return x * x + x + x + 1.0; // smooth ramp end
        }
        return 0.0;
    }

    /**
     * Write a sample to the ring buffer, dropping if full.
     * 
     * @param s
     */
    private void writeSample(float s) {
        int next = (sampleWriteIdx + 1) & (sampleBuf.length - 1);
        if (next != sampleReadIdx) { // drop if buffer full
            sampleBuf[sampleWriteIdx] = s;
            sampleWriteIdx = next;
        }
    }

    /**
     * Update pulse mute flags based on current timer values.
     */
    private void updatePulseMuteFlags() {
        p1Muted = (p1Timer <= 8) || (p1Timer > 0x7FF);
        p2Muted = (p2Timer <= 8) || (p2Timer > 0x7FF);
    }

    // --------- Public test helpers and state accessors -----

    /**
     * Get current Pulse1 output level (0..15) before mixer curve.
     * 
     * @return
     */
    public int getPulse1OutputLevel() {
        // Returns the instantaneous DAC level for Pulse1 (without mixer curve)
        if (p1Muted || lenP1 == 0)
            return 0;
        int bit = DUTY_TABLE[p1Duty][p1DutyStep];
        return bit == 1 ? envP1.getOutput() : 0;
    }

    /**
     * Get current Pulse2 output level (0..15) before mixer curve.
     * 
     * @return
     */
    public int getPulse2OutputLevel() {
        if (p2Muted || lenP2 == 0)
            return 0;
        int bit = DUTY_TABLE[p2Duty][p2DutyStep];
        return bit == 1 ? envP2.getOutput() : 0;
    }

    /**
     * Get current Triangle output level (0..15) before mixer curve.
     * 
     * @return
     */
    public int getTriangleOutputLevel() {
        if (lenTri == 0 || (triTimer & 0x7FF) < 2)
            return 0;
        // Output even if linear is zero; sequencer halts but level remains last
        return TRI_TABLE[triStep];
    }

    /**
     * Set desired sample rate in Hz (minimum 1000).
     * 
     * @param hz
     */
    public void setSampleRate(int hz) {
        if (hz <= 1000)
            return;
        this.sampleRate = hz;
        recomputeSampleInterval();
    }

    /**
     * Set CPU clock rate in Hz (minimum 100000).
     * 
     * @param hz
     */
    public void setCpuClockHz(int hz) {
        if (hz <= 100000)
            return;
        this.cpuClockHz = hz;
        recomputeSampleInterval();
    }

    /**
     * Get number of pending samples in ring buffer.
     * 
     * @return
     */
    public int getPendingSampleCount() {
        int diff = sampleWriteIdx - sampleReadIdx;
        if (diff < 0)
            diff += sampleBuf.length;
        return diff;
    }

    /**
     * Read one sample from ring buffer, or 0 if none available.
     * 
     * @return
     */
    public float readSample() {
        if (sampleReadIdx == sampleWriteIdx)
            return 0f;
        float v = sampleBuf[sampleReadIdx];
        sampleReadIdx = (sampleReadIdx + 1) & (sampleBuf.length - 1);
        return v;
    }

    /**
     * Get current Noise output level (0..15) before mixer curve.
     * 
     * @return
     */
    public int getNoiseOutputLevel() {
        if (lenNoise == 0)
            return 0;
        // If bit0 is 1, output is 0; else envelope output
        int b0 = noiseLfsr & 1;
        return b0 == 0 ? envNoise.getOutput() : 0;
    }

    // ---- Test helpers (expose counters) ----

    public int getQuarterTickCount() {
        return quarterTickCount;
    }

    public int getHalfTickCount() {
        return halfTickCount;
    }

    public boolean isFrameIrq() {
        return frameSequencer.isFrameIrq();
    }

    // Test helpers
    public int getPulse1EnvelopeVolume() {
        return envP1.getOutput();
    }

    public int getPulse1Length() {
        return lenP1;
    }

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

    public int getPulse1DutyStep() {
        return p1DutyStep;
    }

    public int getPulse2DutyStep() {
        return p2DutyStep;
    }

    public int getTriangleTimer() {
        return triTimer & 0x7FF;
    }

    public int getTriangleStep() {
        return triStep & 31;
    }

    public int getNoiseLfsrBit0() {
        return noiseLfsr & 1;
    }

    // getFrameCycle removed (frameCycle no longer tracked externally)

    // -------------------- Helper Envelope class --------------------

    /**
     * Envelope generator state and logic (for pulse and noise channels)
     */
    private static class Envelope {

        // Envelope state
        int period; // 0..15 from reg low nibble
        boolean constantVolume; // bit4
        boolean loop; // bit5 (also halts length when set on pulse/noise)
        boolean start;
        int divider;
        int decay; // 0..15

        /**
         * Get current output volume level (0..15)
         * 
         * @return
         */
        int getOutput() {
            return constantVolume ? period : decay;
        }

        /**
         * Clock a quarter-frame tick: handle starting, divider countdown, decay
         */
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

        /**
         * Load envelope parameters from register value.
         * 
         * @param regVal
         */
        void reloadFromReg(int regVal) {
            period = regVal & 0x0F;
            constantVolume = (regVal & 0x10) != 0;
            loop = (regVal & 0x20) != 0;
        }
    }

    // -------------------- Helper Sweep class --------------------

    /**
     * Sweep unit state and logic (for pulse channels)
     */
    private final class Sweep {

        // Sweep state
        final int ch; // 1 or 2
        boolean enabled;
        int period; // 0..7
        boolean negate;
        int shift; // 0..7
        int divider;
        boolean reload;

        /**
         * Constructor
         * 
         * @param ch
         */
        Sweep(int ch) {
            this.ch = ch;
        }

        /**
         * Write sweep register value
         * 
         * @param v
         */
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

        /**
         * Called when timer or relevant registers change to recompute mute state
         */
        void onTimerOrRegChange() {
            // No-op here; placeholder if we need to recompute mute
            updatePulseMuteFlags();
        }

        /**
         * Clock a half-frame tick: handle divider countdown and sweep application
         */
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
}