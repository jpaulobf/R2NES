package com.nesemu.apu.interfaces;

/**
 * NES APU (2A03) interface.
 * Minimal contract for wiring and initial tests:
 * - Register writes to $4000-$4017 via writeRegister.
 * - Status read from $4015 via readStatus (clear-on-read handled internally).
 * - One clock per CPU cycle via clockCpuCycle().
 */
public interface NesAPU {
    /** Power-on/reset state. */
    void reset();

    /** Advance APU by one CPU cycle (NTSC ~1.789773 MHz). */
    void clockCpuCycle();

    /** Write APU/Frame Counter/DMC registers in $4000-$4017 range. */
    void writeRegister(int address, int value);

    /** Read $4015 status (may clear IRQ flags internally). */
    int readStatus();

    /**
     * DMC fetch handshake: return true when APU needs the CPU to provide the
     * next sample byte for the DMC channel. The Bus/CPU should call
     * supplyDmcSampleByte(...) when this returns true.
     */
    boolean isDmcRequest();

    /** Current 16-bit address the APU requests for the DMC sample fetch. */
    int getDmcCurrentAddress();

    /** Supply a fetched byte for the DMC sample buffer (CPU provides this). */
    void supplyDmcSampleByte(int b);
}