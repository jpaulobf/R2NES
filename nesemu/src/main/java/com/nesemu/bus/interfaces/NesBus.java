package com.nesemu.bus.interfaces;

/**
 * Minimal CPU-facing bus interface.
 * The CPU only needs byte granularity read/write on a 16-bit address space.
 * Additional signalling lines (IRQ/NMI/RDY) can be added later if needed.
 */
public interface NesBus {
    int read(int address);

    void write(int address, int value);
}
