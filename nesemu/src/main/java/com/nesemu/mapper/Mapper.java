package com.nesemu.mapper;

/**
 * Interface for the NES memory mapper.
 * This interface defines methods for reading and writing to the CPU and PPU memory.
 * It is used to abstract the memory mapping logic, allowing different mappers to be implemented.
 * Each mapper will handle specific memory addressing and mapping for the NES system.
 */
public interface Mapper {
    int cpuRead(int address);
    void cpuWrite(int address, int value);
    int ppuRead(int address);
    void ppuWrite(int address, int value);
}