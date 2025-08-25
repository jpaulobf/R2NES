package com.nesemu.memory;

import com.nesemu.memory.interfaces.iMemory;

/**
 * NES Memory Map implementation
 * Handles RAM, mirrors, and stubs for PPU/APU/ROM.
 * Future: Integrate PPU, APU, Mapper, IO, etc.
 */
public class Memory implements iMemory {

    // 2KB internal RAM (0x0000–0x07FF)
    private final int[] ram = new int[0x0800];

    // PRG-ROM (cartucho)
    private int[] prgRom = new int[0x8000]; // 32KB (0x8000–0xFFFF)

    // SRAM (battery-backed RAM, opcional)
    private int[] sram = new int[0x2000]; // 8KB (0x6000–0x7FFF)

    // Métodos para conectar PPU/APU/Mapper futuramente
    // private PPU ppu;
    // private APU apu;
    // private Mapper mapper;

    /**
     * Loads PRG-ROM from the cartridge.
     * This method should be called after loading the cartridge data.
     * @param data The PRG-ROM data to load
     */
    public void loadPRGROM(int[] data) {
        int len = Math.min(data.length, prgRom.length);
        System.arraycopy(data, 0, prgRom, 0, len);
    }

    /**
     * Carrega cartucho iNES (Mapper 0) ajustando espelhamento de 16KB se necessário.
     */
    public void loadCartridge(com.nesemu.rom.INesRom rom) {
        int[] prg = rom.buildPrgRom32k();
        loadPRGROM(prg);
    }

    /**
     * Reads a byte from the memory at the specified address.
     * Handles RAM, PPU registers, APU/IO registers, SRAM, and PRG-ROM.
     * Addresses are wrapped to fit the NES memory map.
     */
    @Override
    public int read(int address) {
        address &= 0xFFFF;
        if (address < 0x2000) {
            // 2KB RAM, espelhada até 0x1FFF
            return ram[address & 0x07FF];
        } else if (address < 0x4000) {
            // PPU registers, espelhados a cada 8 bytes (stub retorna 0)
            return 0;
        } else if (address < 0x4020) {
            // APU e IO registers
            // TODO: Integrar com APU/IO
            // if (address >= 0x4000 && address <= 0x4017) return apu.readRegister(address);
            return 0; // Stub
        } else if (address < 0x6000) {
            // Área de expansão (raramente usada)
            return 0; // Stub
        } else if (address < 0x8000) {
            // SRAM (battery-backed RAM)
            return sram[address - 0x6000];
        } else {
            // PRG-ROM (cartucho)
            // TODO: Integrar com Mapper
            return prgRom[address - 0x8000];
        }
    }

    /**
     * Writes a byte to the memory at the specified address.
     * Handles RAM, PPU registers, APU/IO registers, SRAM, and PRG-ROM.
     * Addresses are wrapped to fit the NES memory map.
     */
    @Override
    public void write(int address, int value) {
        address &= 0xFFFF;
        value &= 0xFF;
        if (address < 0x2000) {
            // 2KB RAM, espelhada
            ram[address & 0x07FF] = value;
        } else if (address < 0x4000) {
            // PPU registers (stub)
            // ppu.writeRegister(0x2000 + (address & 0x7), value);
        } else if (address < 0x4020) {
            // APU e IO registers
            // TODO: Integrar com APU/IO
            // if (address >= 0x4000 && address <= 0x4017) apu.writeRegister(address, value);
        } else if (address < 0x6000) {
            // Área de expansão (raramente usada)
            // Não faz nada
        } else if (address < 0x8000) {
            // SRAM (battery-backed RAM)
            sram[address - 0x6000] = value;
        } else {
            // PRG-ROM (cartucho) é somente leitura
            // TODO: Integrar com Mapper
        }
    }

    /**
     * Utility methods for tests
     * Clears the RAM, SRAM, and PRG-ROM.
     */
    public void clearRAM() {
        for (int i = 0; i < ram.length; i++) ram[i] = 0;
    }
    public void clearSRAM() {
        for (int i = 0; i < sram.length; i++) sram[i] = 0;
    }
    public void clearPRGROM() {
        for (int i = 0; i < prgRom.length; i++) prgRom[i] = 0;
    }
}
