package com.nesemu.mapper;

import com.nesemu.rom.INesRom;
import com.nesemu.util.Log;
import static com.nesemu.util.Log.Cat.*;

/**
 * NROM (Mapper 0): No bank switching.
 * CPU $8000-$BFFF: first 16KB page (or only page)
 * CPU $C000-$FFFF: last 16KB page (mirror of first if only one)
 * PPU $0000-$1FFF: CHR-ROM (or CHR-RAM if size==0) – here we treat size 0 as
 * 8KB RAM.
 */
public class Mapper0 extends Mapper {

    // extended RAM at $6000-$7FFF
    private final int prgPageCount; // 16KB units
    private final int chrPageCount; // 8KB units

    // Debug logging de acessos CHR
    private boolean chrLogEnabled = false;
    private int chrLogLimit = 128;
    private int chrLogCount = 0;

    // Cache mirroring type from header bit6 bit0
    private final boolean headerVertical;

    /**
     * Constructor: takes ownership of rom arrays.
     * @param rom
     */
    public Mapper0(INesRom rom) {
        this.prg = rom.getPrgRom();
        this.chr = rom.getChrRom();
        this.prgPageCount = rom.getHeader().getPrgRomPages();
        this.chrPageCount = rom.getHeader().getChrRomPages();
        this.chrRam = (chr.length == 0) ? new byte[0x2000] : null; // 8KB CHR RAM
        this.headerVertical = rom.getHeader().isVerticalMirroring();
    }

    /**
     * Enable debug logging of CHR accesses (reads and writes if CHR RAM).
     * @param limit
     */
    public void enableChrLogging(int limit) {
        this.chrLogEnabled = true;
        if (limit > 0)
            this.chrLogLimit = limit;
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
                if (address < chr.length) {
                    int val = chr[address] & 0xFF;
                    if (chrLogEnabled && chrLogCount < chrLogLimit) {
                        Log.debug(PPU, "[CHR RD] addr=%04X val=%02X", address, val);
                        chrLogCount++;
                    }
                    return val;
                }
                return 0;
            } else {
                int val = chrRam[address & 0x1FFF] & 0xFF;
                if (chrLogEnabled && chrLogCount < chrLogLimit) {
                    Log.debug(PPU, "[CHR RD] addr=%04X val=%02X (RAM)", address, val);
                    chrLogCount++;
                }
                return val;
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
    public byte[] saveState() {
        // Only dynamic state is CHR RAM (if present)
        if (chr.length == 0 && chrRam != null) {
            byte[] out = new byte[chrRam.length];
            System.arraycopy(chrRam, 0, out, 0, chrRam.length);
            return out;
        }
        return null; // purely ROM / stateless
    }

    @Override
    public void loadState(byte[] data) {
        if (data == null)
            return;
        if (chr.length == 0 && chrRam != null && data.length == chrRam.length) {
            System.arraycopy(data, 0, chrRam, 0, chrRam.length);
        }
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

    public int getChrPageCount() {
        return chrPageCount;
    }
}
