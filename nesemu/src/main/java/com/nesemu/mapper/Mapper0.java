package com.nesemu.mapper;

import com.nesemu.rom.INesRom;

/**
 * NROM (Mapper 0): No bank switching.
 * CPU $8000-$BFFF: first 16KB page (or only page)
 * CPU $C000-$FFFF: last 16KB page (mirror of first if only one)
 * PPU $0000-$1FFF: CHR-ROM (or CHR-RAM if size==0) – here we treat size 0 as
 * 8KB RAM.
 */
public class Mapper0 implements Mapper {
    private final int prgPageCount; // 16KB units
    private final int chrPageCount; // 8KB units
    private final byte[] prg; // full raw PRG
    private final byte[] chr; // CHR data (can be length 0 -> treat as RAM)
    private final byte[] chrRam; // allocated if chr length == 0

    public Mapper0(INesRom rom) {
        this.prg = rom.getPrgRom();
        this.chr = rom.getChrRom();
        this.prgPageCount = rom.getHeader().getPrgRomPages();
        this.chrPageCount = rom.getHeader().getChrRomPages();
        this.chrRam = (chr.length == 0) ? new byte[0x2000] : null; // 8KB CHR RAM
        this.headerVertical = rom.getHeader().isVerticalMirroring();
    }

    @Override
    public int cpuRead(int address) {
        address &= 0xFFFF;
        if (address < 0x8000)
            return 0; // mapper only handles PRG region
        int offset = address - 0x8000;
        if (prgPageCount == 1) {
            // 16KB mirrored twice
            offset &= 0x3FFF;
        } else {
            // 32KB directly
            offset &= 0x7FFF;
        }
        if (offset >= prg.length)
            return 0; // safety
        return prg[offset] & 0xFF;
    }

    @Override
    public void cpuWrite(int address, int value) {
        // NROM: no bank switching; ignore writes to PRG region.
    }

    @Override
    public int ppuRead(int address) {
        address &= 0x3FFF; // PPU address space
        if (address < 0x2000) {
            if (chr.length > 0) {
                if (address < chr.length)
                    return chr[address] & 0xFF;
                return 0;
            } else {
                return chrRam[address & 0x1FFF] & 0xFF;
            }
        }
        // Nametables / palette not handled here (Bus/PPU will manage). Return 0.
        return 0;
    }

    @Override
    public void ppuWrite(int address, int value) {
        address &= 0x3FFF;
        if (address < 0x2000 && chr.length == 0) {
            chrRam[address & 0x1FFF] = (byte) (value & 0xFF); // CHR RAM writable
        }
        // Otherwise ignore (CHR ROM read-only).
    }

    @Override
    public MirrorType getMirrorType() {
        // Derive from iNES header flag6 bit0 via stored page counts; need header. We
        // don't keep header here, so assume vertical if chrPageCount even not helpful.
        // Better: pass rom header in ctor (rom available). For now, infer from rom
        // param.
        // We'll store a cached value.
        // (Refactor: stash boolean on construction.)
        return headerVertical ? MirrorType.VERTICAL : MirrorType.HORIZONTAL;
    }

    private final boolean headerVertical;
}
