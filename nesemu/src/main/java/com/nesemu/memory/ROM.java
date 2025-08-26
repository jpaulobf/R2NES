package com.nesemu.memory;

import com.nesemu.memory.interfaces.NesMemory;

/**
 * Class representing the ROM memory for the NES emulator.
 * This class implements the Memory interface
 * and provides methods for reading from the ROM.
 */
public class ROM implements NesMemory {
    private byte[] data;

    public ROM(byte[] data) {
        this.data = data;
    }

    @Override
    public int read(int address) {
        // TODO: implementar read
        return 0;
    }

    @Override
    public void write(int address, int value) {
        // ROM geralmente é somente leitura, ignore ou lance exceção
    }
}
