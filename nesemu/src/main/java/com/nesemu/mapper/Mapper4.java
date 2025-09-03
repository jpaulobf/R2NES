package com.nesemu.mapper;

import com.nesemu.rom.INesRom;
import com.nesemu.util.Log;
import static com.nesemu.util.Log.Cat.GENERAL;

/**
 * MMC3 (Mapper 4) – Minimal phase 1 implementation.
 * Supported now:
 *  - PRG banking (R6/R7 + mode bit)
 *  - CHR banking (2x2KB + 4x1KB pattern with mode bit)
 *  - Mirroring control ($A000 even writes)
 *  - PRG RAM enable flag stored (no protection logic enforced yet)
 * Not yet implemented (stubs kept for later):
 *  - Scanline IRQ counter (A12 rising edge detection)
 *  - PRG RAM write protect nuances
 *  - Open bus / bus conflicts emulation
 *  - WRAM size variations
 */
public class Mapper4 implements Mapper {
    // ROM / RAM data
    private final byte[] prg;
    private final byte[] chr; // may be 0 => chrRam
    private final byte[] chrRam;
    private final byte[] prgRam; // 8KB battery RAM (simple)

    // Banking registers
    private int bankSelect; // last value written to $8000 even (bits: ....RSTC)
    private final int[] bankRegs = new int[8]; // R0..R7
    private boolean prgMode; // bit6 of bankSelect
    private boolean chrMode; // bit7 of bankSelect

    // Mirroring
    private boolean headerVertical; // fallback from iNES header
    private boolean forceMirroring; // true once $A000 even written
    private boolean mirrorHorizontal; // value from $A000 even (0=vertical,1=horizontal per NES docs)

    // PRG banking derived counts
    private final int prg8kBanks; // number of 8KB units

    // IRQ (stubbed)
    private int irqLatch = 0;
    private int irqCounter = 0;
    private boolean irqReloadPending = false;
    private boolean irqEnabled = false;

    // Logging
    private boolean logBanks = false;
    private int logLimit = 64;
    private int logCount = 0;

    public Mapper4(INesRom rom) {
        this.prg = rom.getPrgRom();
        this.chr = rom.getChrRom();
        this.chrRam = (chr.length == 0) ? new byte[0x2000] : null; // 8KB CHR RAM
        this.prgRam = new byte[0x2000]; // allocate simple 8KB
        this.prg8kBanks = prg.length / 0x2000;
        this.headerVertical = rom.getHeader().isVerticalMirroring();
        // Power-on: common initialize – last bank fixed in one slot depending on mode
        bankRegs[6] = 0; // R6 (PRG) default
        bankRegs[7] = Math.max(0, prg8kBanks - 1); // last bank
    }

    private void trace(String fmt, Object... args) {
        if (logBanks && logCount < logLimit) {
            Log.debug(GENERAL, fmt, args);
            logCount++;
        }
    }

    public void enableBankLogging(int limit) {
        this.logBanks = true;
        if (limit > 0) this.logLimit = limit;
    }

    @Override
    public int cpuRead(int address) {
        address &= 0xFFFF;
        if (address >= 0x6000 && address < 0x8000) {
            return prgRam[address - 0x6000] & 0xFF;
        }
        if (address < 0x8000) return 0;
        int region = (address - 0x8000) / 0x2000; // 0..3 each 8KB
        int bank = mapPrgBank(region);
        int base = (bank % Math.max(prg8kBanks,1)) * 0x2000;
        int idx = base + ((address - 0x8000) & 0x1FFF);
        if (idx >= 0 && idx < prg.length) return prg[idx] & 0xFF;
        return 0;
    }

    private int mapPrgBank(int region) {
        // PRG layout differs by prgMode.
        // If prgMode = 0:
        //   $8000-$9FFF: bank R6
        //   $A000-$BFFF: bank R7
        //   $C000-$DFFF: second last bank (fixed)
        //   $E000-$FFFF: last bank (fixed)
        // If prgMode = 1:
        //   $8000-$9FFF: second last bank (fixed)
        //   $A000-$BFFF: bank R7
        //   $C000-$DFFF: bank R6
        //   $E000-$FFFF: last bank (fixed)
        int last = Math.max(0, prg8kBanks - 1);
        int secondLast = Math.max(0, prg8kBanks - 2);
        if (!prgMode) { // mode 0
            return switch (region) {
                case 0 -> bankRegs[6];
                case 1 -> bankRegs[7];
                case 2 -> secondLast;
                case 3 -> last;
                default -> last;
            };
        } else { // mode 1
            return switch (region) {
                case 0 -> secondLast;
                case 1 -> bankRegs[7];
                case 2 -> bankRegs[6];
                case 3 -> last;
                default -> last;
            };
        }
    }

    @Override
    public void cpuWrite(int address, int value) {
        address &= 0xFFFF; value &= 0xFF;
        if (address >= 0x6000 && address < 0x8000) {
            prgRam[address - 0x6000] = (byte) value; // ignore enable for now
            return;
        }
        if (address < 0x8000) return;
        boolean isEven = (address & 1) == 0;
        int range = (address >> 12) & 0xF; // 8=0x8000, A=0xA000, C=0xC000, E=0xE000 upper nibble
        switch (range & 0xE) { // mask lower bit to group even/odd within
            case 0x8: // $8000/$8001
                if (isEven) { // bank select
                    bankSelect = value;
                    prgMode = (value & 0x40) != 0;
                    chrMode = (value & 0x80) != 0;
                } else { // bank data
                    int target = bankSelect & 0x07; // 0..7
                    bankRegs[target] = value & 0xFF;
                }
                trace("[M4 BANK SEL=%02X PRGm=%b CHRm=%b R=%d val=%02X]", bankSelect & 0xFF, prgMode, chrMode, bankSelect & 7, value & 0xFF);
                break;
            case 0xA: // $A000/$A001
                if (isEven) { // mirroring
                    forceMirroring = true;
                    mirrorHorizontal = (value & 1) == 1; // 0 = vertical per hardware
                } else {
                    // PRG RAM protect/enable bits (bit7 enable, bit6 write protect) – store only
                    // Could enforce later
                }
                break;
            case 0xC: // $C000/$C001 IRQ latch / reload
                if (isEven) {
                    irqLatch = value & 0xFF;
                } else {
                    irqReloadPending = true; // will take effect on next A12 edge (future)
                }
                break;
            case 0xE: // $E000/$E001 IRQ disable / enable
                if (isEven) {
                    irqEnabled = false; // also acknowledge pending (future)
                } else {
                    irqEnabled = true;
                }
                break;
        }
    }

    @Override
    public int ppuRead(int address) {
        address &= 0x3FFF;
        if (address < 0x2000) {
            return readChrMapped(address) & 0xFF;
        }
        return 0;
    }

    private int readChrMapped(int address) {
        int bankIndex;
        int offset;
        // CHR layout depends on chrMode.
        // When chrMode == false (0):
        //   0000-07FF: 2KB bank R0 (value forced even)
        //   0800-0FFF: 2KB bank R1 (value forced even)
        //   1000-13FF: 1KB bank R2
        //   1400-17FF: 1KB bank R3
        //   1800-1BFF: 1KB bank R4
        //   1C00-1FFF: 1KB bank R5
        // When chrMode == true (1): (the two 2KB banks move to upper half)
        //   0000-03FF: 1KB bank R2
        //   0400-07FF: 1KB bank R3
        //   0800-0BFF: 1KB bank R4
        //   0C00-0FFF: 1KB bank R5
        //   1000-17FF: 2KB bank R0 (even)
        //   1800-1FFF: 2KB bank R1 (even)
        if (!chrMode) {
            if (address < 0x0800) { // 2KB R0
                int bank2 = bankRegs[0] & 0xFE; // force even
                bankIndex = bank2 * 0x400; // 1KB units * 1KB
                offset = address & 0x07FF;
            } else if (address < 0x1000) { // 2KB R1
                int bank2 = bankRegs[1] & 0xFE;
                bankIndex = bank2 * 0x400;
                offset = (address - 0x0800) & 0x07FF;
            } else {
                int sub = (address - 0x1000) >> 10; // 0..3 mapping R2..R5
                int reg = 2 + sub;
                int bank1 = bankRegs[reg] & 0xFF;
                bankIndex = bank1 * 0x400;
                offset = address & 0x03FF;
            }
        } else { // chrMode == 1 (swap halves)
            if (address < 0x1000) { // four 1KB banks using R2..R5
                int sub = address >> 10; // 0..3
                int reg = 2 + sub;
                int bank1 = bankRegs[reg] & 0xFF;
                bankIndex = bank1 * 0x400;
                offset = address & 0x03FF;
            } else { // two 2KB banks R0,R1
                if (address < 0x1800) { // 2KB bank R0
                    int bank2 = bankRegs[0] & 0xFE;
                    bankIndex = bank2 * 0x400;
                    offset = (address - 0x1000) & 0x07FF;
                } else { // 2KB bank R1
                    int bank2 = bankRegs[1] & 0xFE;
                    bankIndex = bank2 * 0x400;
                    offset = (address - 0x1800) & 0x07FF;
                }
            }
        }
        int chrLen = (chrRam != null) ? chrRam.length : chr.length;
        if (chrLen == 0) return 0;
        int linear = (bankIndex + offset) % chrLen;
        return (chrRam != null ? chrRam[linear] : chr[linear]) & 0xFF;
    }

    @Override
    public void ppuWrite(int address, int value) {
        address &= 0x3FFF; value &= 0xFF;
        if (address < 0x2000 && chrRam != null) {
            int idx = address & (chrRam.length - 1);
            chrRam[idx] = (byte) value;
        }
        // IRQ A12 edge tracking would hook here via pattern fetch addresses in future.
    }

    @Override
    public MirrorType getMirrorType() {
        if (forceMirroring) {
            return mirrorHorizontal ? MirrorType.HORIZONTAL : MirrorType.VERTICAL;
        }
        return headerVertical ? MirrorType.VERTICAL : MirrorType.HORIZONTAL;
    }

    // --- Future IRQ Support Hooks (no effect yet) ---
    public void onA12RisingEdge() {
        if (!irqEnabled) return;
        if (irqReloadPending || irqCounter == 0) {
            irqCounter = irqLatch;
            irqReloadPending = false;
        } else {
            irqCounter = (irqCounter - 1) & 0xFF;
        }
        if (irqCounter == 0) {
            // ...: Signal IRQ line to CPU (requires bus/cpu integration path)
        }
    }
}
