package com.nesemu.mapper;

public interface Mapper {
    int cpuRead(int address);
    void cpuWrite(int address, int value);
    int ppuRead(int address);
    void ppuWrite(int address, int value);
}