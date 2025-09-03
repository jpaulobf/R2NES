package com.nesemu.mapper;

/**
 * Interface for the NES memory mapper.
 * This interface defines methods for reading and writing to the CPU and PPU
 * memory.
 * It is used to abstract the memory mapping logic, allowing different mappers
 * to be implemented.
 * Each mapper will handle specific memory addressing and mapping for the NES
 * system.
 */
public interface Mapper {
    /**
     * CPU read from mapped PRG/CHR/extra space. Only addresses >= $8000 (and mapper specific ranges like $6000-$7FFF for PRG RAM) are typically serviced here; others handled by bus.
     * @param address 16-bit CPU address
     * @return unsigned byte (0-255)
     */
    int cpuRead(int address);

    /**
     * CPU write to mapper-controlled region (bank select registers, PRG RAM, IRQ registers, etc.).
     * @param address 16-bit CPU address
     * @param value byte value (only low 8 bits used)
     */
    void cpuWrite(int address, int value);

    /**
     * PPU pattern table / CHR space read (addresses < $2000). Mapper translates bank registers to physical CHR ROM/RAM.
     * @param address 14-bit PPU address
     * @return unsigned byte (0-255)
     */
    int ppuRead(int address);

    /**
     * PPU write into CHR RAM (if present) or mapper special areas (ExRAM, etc.). Ignored for pure CHR ROM mappers.
     * @param address 14-bit PPU address
     * @param value byte value
     */
    void ppuWrite(int address, int value);

    /**
     * Nametable mirroring type (horizontal/vertical) to guide PPU address decode.
     */
    default MirrorType getMirrorType() {
        return MirrorType.HORIZONTAL;
    }

    enum MirrorType {
    /** Nametable layout: [A A | B B] horizontally. */
    HORIZONTAL,
    /** Nametable layout: [A B | A B] vertically. */
    VERTICAL,
    /** Single-screen using CIRAM page 0. */
    SINGLE0,
    /** Single-screen using CIRAM page 1. */
    SINGLE1
    }
}