package com.nesemu.cpu.interfaces;

/**
 * Interface for the CPU of the NES emulator.
 * This interface defines the basic operations that a CPU should implement.
 */
public interface NesCPU {
    void reset();
    void clock();
    void nmi();
    void irq();
}