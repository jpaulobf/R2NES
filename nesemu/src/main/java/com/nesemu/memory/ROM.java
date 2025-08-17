package com.nesemu.memory;

public class ROM implements Memory {
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
