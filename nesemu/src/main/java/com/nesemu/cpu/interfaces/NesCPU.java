package com.nesemu.cpu.interfaces;

import com.nesemu.emulator.Clockable;

/**
 * Interface for the CPU of the NES emulator.
 * This interface defines the basic operations that a CPU should implement.
 */
public interface NesCPU extends Clockable {

    /**
     * Perform a hardware style reset (reload vectors, clear internal state, set PC
     * to reset vector).
     */
    void reset();

    /**
     * Advance the CPU by one clock cycle (may or may not complete an instruction).
     */
    void clock();

    /**
     * Signal a Non-Maskable Interrupt (triggered by PPU VBlank); should be edge
     * processed at proper cycle boundary.
     */
    void nmi();

    /**
     * Signal a maskable IRQ (from APU / mapper); honored only if Interrupt Disable
     * flag is clear.
     */
    void irq();

    /** Accumulator register (8-bit). */
    int getA();

    /** X index register (8-bit). */
    int getX();

    /** Y index register (8-bit). */
    int getY();

    /** Stack Pointer (8-bit, offset within 0x0100 page). */
    int getSP();

    /** Program Counter (16-bit). */
    int getPC();

    /**
     * PC value at the start of the last fully decoded instruction (helpful for
     * tracing/debug).
     */
    int getLastInstrPC();

    /**
     * True if currently between instructions (no partial execution in progress).
     */
    boolean isInstructionBoundary();

    /** Full processor status register packed into a byte (NV-BDIZC). */
    int getStatusByte();

    /** Force all core registers/state (used for tests, savestates, rewinding). */
    void forceState(int pc, int a, int x, int y, int p, int sp);

    /** Execute exactly one full instruction (cycles until boundary). */
    void stepInstruction();

    /**
     * Cycles consumed by the current (or just finished) instruction so far
     * (base+extra).
     */
    int getCycles();

    /** Carry flag C. */
    boolean isCarry();

    /** Zero flag Z. */
    boolean isZero();

    /** Interrupt Disable flag I. */
    boolean isInterruptDisable();

    /** Decimal flag D (unused on NES CPU but still stored). */
    boolean isDecimal();

    /** Break flag B (software interrupt marker). */
    boolean isBreakFlag();

    /** Unused flag bit (always set to 1 when pushed, tracked separately). */
    boolean isUnused();

    /** Overflow flag V. */
    boolean isOverflow();

    /** Negative flag N (sign bit). */
    boolean isNegative();

    /** Opcode byte of the last executed instruction. */
    int getLastOpcodeByte();

    /**
     * Base cycle count for the last instruction (before page-cross / branch
     * penalties).
     */
    int getLastBaseCycles();

    /**
     * Additional cycles added (page cross, branch taken, RMW, etc.) for last
     * instruction.
     */
    int getLastExtraCycles();

    /** For read-modify-write ops: final value written (for debugging). */
    int getLastRmwModified();

    /** True if the last instruction was a branch and it was taken. */
    boolean wasLastBranchTaken();

    /** True if the last taken branch crossed a page (added extra cycle). */
    boolean wasLastBranchPageCross();

    /** Monotonic total CPU cycles executed since reset (for timing/PPU sync). */
    long getTotalCycles();

    /** Manually adjust total cycle counter (used by tests or restoring state). */
    void setTotalCycles(long cycles);

    /** Add a DMA stall (e.g., OAM DMA 513/514 cycles) to the cycle accounting. */
    void addDmaStall(int cycles);

    /** Cycles remaining (or last applied) due to an active DMA stall. */
    int getDmaStallCycles();

    /** True if currently stalling due to DMA (CPU not executing instructions). */
    boolean isDmaStalling();

    /** Directly set accumulator (for tests/state restore). */
    void setA(int a);

    /** Set X register. */
    void setX(int x);

    /** Set Y register. */
    void setY(int y);

    /** Set stack pointer. */
    void setSP(int sp);

    /** Set program counter (jump without affecting stack). */
    void setPC(int pc);

    /** Set Carry flag. */
    void setCarry(boolean carry);

    /** Set Zero flag. */
    void setZero(boolean zero);

    /** Set Interrupt Disable flag. */
    void setInterruptDisable(boolean interruptDisable);

    /** Set Decimal flag (ignored by ALU on NES but stored). */
    void setDecimal(boolean decimal);

    /** Set Break flag. */
    void setBreakFlag(boolean breakFlag);

    /** Set the 'unused' status flag bit representation. */
    void setUnused(boolean unused);

    /** Set Overflow flag. */
    void setOverflow(boolean overflow);

    /** Set Negative flag. */
    void setNegative(boolean negative);

    // --- Instrumentation additions ---
    /**
     * Total times an NMI handler sequence has been entered (after vector fetch).
     */
    default long getNmiHandlerCount() {
        return 0L;
    }

    /**
     * Last NMI handler vector PC loaded (address of first instruction executed in
     * handler).
     */
    default int getLastNmiHandlerVector() {
        return 0;
    }

    /** 
     * Total times an IRQ handler sequence has been entered (after vector fetch).
     */
    int getPc();
}