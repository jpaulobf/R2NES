package com.nesemu.mapper;

/**
 * Mapper 0 implementation for the NES emulator.
 * This mapper is used for games that do not require any special memory mapping.
 */
public class Mapper0 implements Mapper {
    @Override
    public int cpuRead(int address) {
        // TODO: implementar leitura da ROM sem mapeamento
        return 0;
    }

    @Override
    public void cpuWrite(int address, int value) {
        // TODO: implementar, se necessário
    }

    @Override
    public int ppuRead(int address) {
        // TODO: implementar leitura da CHR-ROM
        return 0;
    }

    @Override
    public void ppuWrite(int address, int value) {
        // TODO: implementar, se a CHR for RAM (ex: emular gravação)
    }
}
