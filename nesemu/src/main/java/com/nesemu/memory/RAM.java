package com.nesemu.memory;

public class RAM implements Memory {
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
