package com.nesemu.rom;

import com.nesemu.mapper.Mapper;

/**
 * Class representing a NES cartridge.
 * This class contains the ROM data and the mapper used for memory mapping.
 */
public class Cartridge {

    // iNES ROM data and mapper
    private INesRom rom;
    private Mapper mapper;

    /**
     * Constructor for the Cartridge class.
     * @param rom
     * @param mapper
     */
    public Cartridge(INesRom rom, Mapper mapper) {
        this.rom = rom;
        this.mapper = mapper;
    }

    /**
     * Gets the iNES ROM.
     * @return
     */
    public INesRom getRom() {
        return rom;
    }

    /**
     * Gets the mapper.
     * @return
     */
    public Mapper getMapper() {
        return mapper;
    }
}
