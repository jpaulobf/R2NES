package com.nesemu.memory;

import com.nesemu.memory.interfaces.iMemory;

/**
 * Class representing the RAM memory for the NES emulator.
 * This class implements the Memory interface
 * and provides methods for reading and writing to the RAM.
 */
public class RAM implements iMemory {
    private byte[] data;

    public RAM(int size) {
        data = new byte[size];
    }

    @Override
    public int read(int address) {
        // TODO: implementar read
        return 0;
    }

    @Override
    public void write(int address, int value) {
        // TODO: implementar write
    }
}
