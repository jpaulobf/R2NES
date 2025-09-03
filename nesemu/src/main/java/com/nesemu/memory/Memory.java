package com.nesemu.memory;

import com.nesemu.memory.interfaces.NesMemory;

/**
 * NES Memory Map implementation.
 * Handles internal RAM + mirrors, PPU/APU/IO stubs, SRAM and PRG-ROM.
 * Future: integrate PPU, APU, Mapper, IO, etc.
 */
public class Memory implements NesMemory {

    // 2KB internal RAM (0x0000–0x07FF)
    private final int[] ram = new int[0x0800];

    // PRG-ROM (cartridge)
    private int[] prgRom = new int[0x8000]; // 32KB (0x8000–0xFFFF)

    // SRAM (battery-backed RAM, optional)
    private int[] sram = new int[0x2000]; // 8KB (0x6000–0x7FFF)

    // Future: hooks to connect PPU/APU/Mapper
    // private PPU ppu;
    // private APU apu;
    // private Mapper mapper;

    @Override
    public void loadPRGROM(int[] data) {
        int len = Math.min(data.length, prgRom.length);
        System.arraycopy(data, 0, prgRom, 0, len);
    }

    @Override
    public void loadCartridge(com.nesemu.rom.INesRom rom) {
        int[] prg = rom.buildPrgRom32k();
        loadPRGROM(prg);
    }

    @Override
    public int read(int address) {
        address &= 0xFFFF;
        if (address < 0x2000) {
            // 2KB RAM mirrored up to 0x1FFF
            return ram[address & 0x07FF];
        } else if (address < 0x4000) {
            // PPU registers, mirrored every 8 bytes (stub returns 0)
            return 0;
        } else if (address < 0x4020) {
            // APU & IO registers
            // TODO: Integrar com APU/IO
            // if (address >= 0x4000 && address <= 0x4017) return apu.readRegister(address);
            return 0; // Stub
        } else if (address < 0x6000) {
            // Expansion area (rarely used)
            return 0; // Stub
        } else if (address < 0x8000) {
            // SRAM (battery-backed RAM)
            return sram[address - 0x6000];
        } else {
            // PRG-ROM (cartridge)
            // TODO: Integrar com Mapper
            return prgRom[address - 0x8000];
        }
    }

    @Override
    public void write(int address, int value) {
        address &= 0xFFFF;
        value &= 0xFF;
        if (address < 0x2000) {
            // 2KB RAM mirrored
            ram[address & 0x07FF] = value;
        } else if (address < 0x4000) {
            // PPU registers (stub)
            // ppu.writeRegister(0x2000 + (address & 0x7), value);
        } else if (address < 0x4020) {
            // APU & IO registers
            // TODO: Integrar com APU/IO
            // if (address >= 0x4000 && address <= 0x4017) apu.writeRegister(address,
            // value);
        } else if (address < 0x6000) {
            // Expansion area (rarely used)
            // Não faz nada
        } else if (address < 0x8000) {
            // SRAM (battery-backed RAM)
            sram[address - 0x6000] = value;
        } else {
            // PRG-ROM region. In production this would be read-only (mapper controlled),
            // but for unit tests (no mapper) we allow direct injection so the Bus
            // fallback can populate opcodes and vectors.
            prgRom[address - 0x8000] = value;
        }
    }

    @Override
    public void clearRAM() {
        for (int i = 0; i < ram.length; i++)
            ram[i] = 0;
    }

    @Override
    public void clearSRAM() {
        for (int i = 0; i < sram.length; i++)
            sram[i] = 0;
    }

    @Override
    public void clearPRGROM() {
        for (int i = 0; i < prgRom.length; i++)
            prgRom[i] = 0;
    }

    @Override
    public int readInternalRam(int address) {
        return ram[address & 0x07FF] & 0xFF;
    }

    @Override
    public void writeInternalRam(int address, int value) {
        ram[address & 0x07FF] = value & 0xFF;
    }

    @Override
    public int readSram(int address) { // address in full CPU space 0x6000-0x7FFF
        return sram[address - 0x6000] & 0xFF;
    }

    @Override
    public void writeSram(int address, int value) {
        sram[address - 0x6000] = value & 0xFF;
    }
}
