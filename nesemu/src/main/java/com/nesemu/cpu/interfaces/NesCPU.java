package com.nesemu.cpu.interfaces;

import com.nesemu.emulator.Clockable;

/**
 * Interface for the CPU of the NES emulator.
 * This interface defines the basic operations that a CPU should implement.
 */
public interface NesCPU extends Clockable {
    void reset();
    void clock();
    void nmi();
    void irq();
}