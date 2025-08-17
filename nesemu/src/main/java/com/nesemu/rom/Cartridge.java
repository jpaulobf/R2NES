package com.nesemu.rom;

import com.nesemu.mapper.Mapper;

/**
 * Class representing a NES cartridge.
 * This class contains the ROM data and the mapper used for memory mapping.
 */
public class Cartridge {
    private INesRom rom;
    private Mapper mapper;

    public Cartridge(INesRom rom, Mapper mapper) {
        this.rom = rom;
        this.mapper = mapper;
    }

    public INesRom getRom() { return rom; }
    public Mapper getMapper() { return mapper; }
}
