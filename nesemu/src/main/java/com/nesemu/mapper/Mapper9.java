package com.nesemu.mapper;

import com.nesemu.rom.INesRom;

/**
 * Mapper 9 (MMC2) - Used primarily by Punch-Out!!
 * Features:
 * - PRG ROM: 8KB switchable ($8000-$9FFF) + Fixed last 3 banks ($A000-$FFFF)
 * - CHR ROM: Two 4KB banks, switched automatically by PPU reads (Latches)
 * - Mirroring: Switchable H/V
 */
public class Mapper9 extends Mapper {

    // PRG Banking
    private int prgBankSelect = 0; // Selects 8KB bank at $8000
    private final int prg8kBanks;

    // CHR Banking Registers (4KB banks)
    private int chrBank0FD = 0; // Latch 0 FD value (Bank A for $0000-$0FFF)
    private int chrBank0FE = 0; // Latch 0 FE value (Bank B for $0000-$0FFF)
    private int chrBank1FD = 0; // Latch 1 FD value (Bank A for $1000-$1FFF)
    private int chrBank1FE = 0; // Latch 1 FE value (Bank B for $1000-$1FFF)

    // Latches (false = FD state, true = FE state)
    // Latch 0 controls $0000-$0FFF, Latch 1 controls $1000-$1FFF
    private boolean latch0 = false; 
    private boolean latch1 = false;

    // Mirroring
    private boolean horizontalMirroring = false; // 0=Vert, 1=Horz

    public Mapper9(INesRom rom) {
        this.prg = rom.getPrgRom();
        this.chr = rom.getChrRom();
        this.prg8kBanks = prg.length / 0x2000;
        // Usually MMC2 uses CHR ROM, but we alloc RAM if needed for safety
        this.chrRam = (chr.length == 0) ? new byte[0x2000] : null;
        
        // Initial state: usually latches start in FD mode (false)
        // Fixed banks are set logically in cpuRead
    }

    @Override
    public int cpuRead(int address) {
        address &= 0xFFFF;
        if (address < 0x8000) return 0;

        int bankIndex;
        int offset = address & 0x1FFF;

        if (address < 0xA000) {
            // $8000-$9FFF: Switchable 8KB bank
            bankIndex = prgBankSelect;
        } else {
            // $A000-$FFFF: Fixed to last three 8KB banks
            // $A000-$BFFF: Second to last - 2
            // $C000-$DFFF: Second to last - 1
            // $E000-$FFFF: Last
            int slot = (address - 0xA000) / 0x2000; // 0, 1, 2
            bankIndex = Math.max(0, prg8kBanks - 3) + slot;
        }

        int flatAddr = (bankIndex * 0x2000) + offset;
        if (flatAddr < prg.length) {
            return prg[flatAddr] & 0xFF;
        }
        return 0;
    }

    @Override
    public void cpuWrite(int address, int value) {
        address &= 0xFFFF;
        if (address < 0xA000) return; // Writes only accepted in $A000-$FFFF

        // Register select based on address range (masking $F000)
        int range = (address >> 12) & 0xF;
        value &= 0xFF;

        switch (range) {
            case 0xA -> chrBank0FD = value & 0x1F; // $A000-$AFFF: Latch 0 FD
            case 0xB -> chrBank0FE = value & 0x1F; // $B000-$BFFF: Latch 0 FE
            case 0xC -> chrBank1FD = value & 0x1F; // $C000-$CFFF: Latch 1 FD
            case 0xD -> chrBank1FE = value & 0x1F; // $D000-$DFFF: Latch 1 FE
            case 0xE -> horizontalMirroring = (value & 1) != 0; // $E000-$EFFF: Mirroring
            case 0xF -> prgBankSelect = value & 0x0F; // $F000-$FFFF: PRG Bank ($8000)
        }
    }

    @Override
    public int ppuRead(int address) {
        address &= 0x3FFF;
        int result = 0;

        // 1. Determine which 4KB bank to use based on address and current latch state
        int bank4k = 0;
        if (address < 0x1000) {
            // Pattern table 0 ($0000-$0FFF) controlled by Latch 0
            bank4k = latch0 ? chrBank0FE : chrBank0FD;
        } else if (address < 0x2000) {
            // Pattern table 1 ($1000-$1FFF) controlled by Latch 1
            bank4k = latch1 ? chrBank1FE : chrBank1FD;
        }

        // 2. Read the data
        if (address < 0x2000) {
            int offset = address & 0x0FFF;
            int flatAddr = (bank4k * 0x1000) + offset;
            
            if (chrRam != null) {
                result = chrRam[flatAddr & (chrRam.length - 1)] & 0xFF;
            } else if (flatAddr < chr.length) {
                result = chr[flatAddr] & 0xFF;
            }
        }

        // 3. Update Latches (The "Magic" of MMC2)
        // The hardware monitors the PPU address bus. When specific "trigger" tiles are read,
        // the latch flips state for the *next* read.
        
        // Latch 0 Triggers:
        // Hardware behavior: Latch updates on the HIGH byte read ($xFD8-$xFDF).
        // Updating on low byte ($xFD0) would cause the high byte to be read from the NEW bank!
        if (address >= 0x0FD8 && address <= 0x0FDF) {
            latch0 = false; // Switch to FD bank
        } else if (address >= 0x0FE8 && address <= 0x0FEF) {
            latch0 = true;  // Switch to FE bank
        }
        // Latch 1 Triggers:
        else if (address >= 0x1FD8 && address <= 0x1FDF) {
            latch1 = false; // Switch to FD bank
        } else if (address >= 0x1FE8 && address <= 0x1FEF) {
            latch1 = true;  // Switch to FE bank
        }

        return result;
    }

    @Override
    public void ppuWrite(int address, int value) {
        address &= 0x3FFF;
        // MMC2 usually uses CHR ROM, but if RAM is present (unlikely), allow writes.
        // Note: PPU writes do NOT typically trigger the latches.
        if (address < 0x2000 && chrRam != null) {
             int bank4k = 0;
             if (address < 0x1000) bank4k = latch0 ? chrBank0FE : chrBank0FD;
             else bank4k = latch1 ? chrBank1FE : chrBank1FD;
             
             int offset = address & 0x0FFF;
             int flatAddr = (bank4k * 0x1000) + offset;
             chrRam[flatAddr & (chrRam.length - 1)] = (byte)value;
        }
    }

    @Override
    public MirrorType getMirrorType() {
        return horizontalMirroring ? MirrorType.HORIZONTAL : MirrorType.VERTICAL;
    }

    // --- Save State Support ---

    @Override
    public byte[] saveState() {
        int chrLen = (chrRam != null) ? chrRam.length : 0;
        // 8 bytes header + CHR RAM
        byte[] data = new byte[8 + chrLen];
        data[0] = (byte) prgBankSelect;
        data[1] = (byte) chrBank0FD;
        data[2] = (byte) chrBank0FE;
        data[3] = (byte) chrBank1FD;
        data[4] = (byte) chrBank1FE;
        data[5] = (byte) (latch0 ? 1 : 0);
        data[6] = (byte) (latch1 ? 1 : 0);
        data[7] = (byte) (horizontalMirroring ? 1 : 0);
        if (chrLen > 0) {
            System.arraycopy(chrRam, 0, data, 8, chrLen);
        }
        return data;
    }

    @Override
    public void loadState(byte[] data) {
        if (data == null || data.length < 8) return;
        prgBankSelect = data[0] & 0xFF;
        chrBank0FD = data[1] & 0xFF;
        chrBank0FE = data[2] & 0xFF;
        chrBank1FD = data[3] & 0xFF;
        chrBank1FE = data[4] & 0xFF;
        latch0 = data[5] != 0;
        latch1 = data[6] != 0;
        horizontalMirroring = data[7] != 0;
        
        int chrLen = (chrRam != null) ? chrRam.length : 0;
        if (chrLen > 0 && data.length >= 8 + chrLen) {
            System.arraycopy(data, 8, chrRam, 0, chrLen);
        }
    }
}
