package com.nesemu.rom;

import com.nesemu.mapper.Mapper;

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
