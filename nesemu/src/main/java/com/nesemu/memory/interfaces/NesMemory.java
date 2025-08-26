package com.nesemu.memory.interfaces;

/**
 * Interface for the NES memory.
 * This interface defines methods for reading and writing to the memory.
 */
public interface NesMemory {
    int read(int address);

    void write(int address, int value);
}
