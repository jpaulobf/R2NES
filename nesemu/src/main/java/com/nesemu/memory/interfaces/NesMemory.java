package com.nesemu.memory.interfaces;

/**
 * Interface for the NES memory.
 * This interface defines methods for reading and writing to the memory.
 */
public interface NesMemory {

    /**
     * Reads a byte from the memory at the specified address.
     * Handles RAM, PPU registers, APU/IO registers, SRAM, and PRG-ROM.
     * Addresses are wrapped to fit the NES memory map.
     */
    int read(int address);

    /**
     * Writes a byte to the memory at the specified address.
     * Handles RAM, PPU registers, APU/IO registers, SRAM, and PRG-ROM.
     * Addresses are wrapped to fit the NES memory map.
     */
    void write(int address, int value);

    /**
     * Loads raw PRG-ROM data (already expanded or mirrored as needed).
     */
    public void loadPRGROM(int[] data);

    /**
     * Loads iNES cartridge (Mapper 0) applying 16KB mirroring if needed.
     */
    public void loadCartridge(com.nesemu.rom.INesRom rom);

    /**
     * Clear internal 2KB work RAM ($0000-$07FF) and its mirrors to a power-on state (usually 0x00 or implementation pattern).
     */
    public void clearRAM();

    /**
     * Clear battery-backed save RAM region (PRG SRAM) if present.
     */
    public void clearSRAM();

    /**
     * Wipe PRG ROM buffer (mainly for tests/reset scenarios) – normally immutable in real hardware.
     */
    public void clearPRGROM();

    /**
     * Direct read from internal work RAM ignoring mirroring logic (address masked appropriately by caller).
     * @param address raw address within RAM range
     * @return unsigned byte
     */
    public int readInternalRam(int address);

    /**
     * Direct write to internal work RAM ignoring external bus side-effects.
     * @param address RAM address
     * @param value byte value
     */
    public void writeInternalRam(int address, int value);

    /**
     * Read from PRG battery SRAM (if allocated) at given offset.
     * @param address SRAM offset
     * @return unsigned byte
     */
    public int readSram(int address);

    /**
     * Write to PRG battery SRAM (if allocated/allowed) at given offset.
     * @param address SRAM offset
     * @param value byte value
     */
    public void writeSram(int address, int value);
}
