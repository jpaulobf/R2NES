package com.nesemu.mapper;

import com.nesemu.rom.INesRom;
import com.nesemu.util.Log;
import static com.nesemu.util.Log.Cat.*;

/**
 * MMC1 (Mapper 1) - Simplified implementation with PRG RAM exposure for persistence.
 * Implements serial 5-bit shift register writes (bit7 reset) at $8000-$FFFF.
 * Control register bits (after load):
 * 0-1: Mirroring (0=Single0,1=Single1,2=Vertical,3=Horizontal)
 * 2-3: PRG bank mode
 * 00,01 = Switch 32KB at $8000 (ignore low bit of bank number)
 * 10 = Fix first bank at $8000, switch 16KB at $C000
 * 11 = Fix last bank at $C000, switch 16KB at $8000 (common)
 * 4: CHR bank mode (0=8KB,1=2x4KB)
 *
 * Registers ordering (by write address):
 * $8000-$9FFF: Control
 * $A000-$BFFF: CHR bank 0
 * $C000-$DFFF: CHR bank 1 (if 4KB mode)
 * $E000-$FFFF: PRG bank
 */
public class Mapper1 extends Mapper {

    private final int prg16kBanks; // number of 16KB banks

    // MMC1 registers
    private int regControl = 0x0C; // default after power-on (....1100)
    private int regChrBank0 = 0;
    private int regChrBank1 = 0;
    private int regPrgBank = 0;

    // Serial write latch
    private int shift = 0x10; // when bit 4 set -> latch empty

    // Debug/logging
    private boolean bankLog = false;
    private int bankLogLimit = 64;
    private int bankLogCount = 0;

    // Vertical mirroring from header if no override in control register
    private boolean verticalFromHeader; // fallback mirroring if needed

    /**
     * Construct a Mapper1 for the given ROM.
     * 
     * @param rom
     */
    public Mapper1(INesRom rom) {
        this.prg = rom.getPrgRom();
        this.chr = rom.getChrRom();
        this.prg16kBanks = rom.getHeader().getPrgRomPages(); // 16KB units
        this.chrRam = (chr.length == 0) ? new byte[0x2000] : null; // 8KB RAM
        this.verticalFromHeader = rom.getHeader().isVerticalMirroring();
        // Battery flag available via rom.getHeader().isBatteryBacked(); not yet used
        // for conditional persistence.
        // Allocate PRG RAM unconditionally for now (some non-battery MMC1 carts also
        // have WRAM). Could gate on header if desired.
        this.prgRam = new byte[0x2000];
    }

    @Override
    public int cpuRead(int address) {
        address &= 0xFFFF;
        // $6000-$7FFF: PRG RAM (battery backed)
        if (address >= 0x6000 && address < 0x8000) {
            if (prgRam != null) {
                return prgRam[address - 0x6000] & 0xFF;
            }
            return 0; // no RAM allocated
        }
        if (address < 0x8000)
            return 0; // other lower regions handled by Bus (internal RAM / IO)
        int mode = (regControl >> 2) & 0x03; // PRG mode
        if (mode <= 1) { // 32KB switch (ignore low bit of regPrgBank)
            int bank32 = (regPrgBank & 0x0E) >> 1; // 32KB bank index
            int base = bank32 * 0x8000;
            int off = (address - 0x8000) & 0x7FFF;
            int idx = (base + off) % prg.length;
            return prg[idx] & 0xFF;
        } else if (mode == 2) { // fix first at $8000, switch at $C000
            if (address < 0xC000) { // first 16KB bank (0)
                int idx = (address - 0x8000) & 0x3FFF;
                if (idx < prg.length)
                    return prg[idx] & 0xFF;
                return 0;
            } else { // switchable upper
                int bank = regPrgBank & 0x0F;
                int base = bank * 0x4000;
                int idx = base + ((address - 0xC000) & 0x3FFF);
                if (idx < prg.length)
                    return prg[idx] & 0xFF;
                return 0;
            }
        } else { // mode == 3: switch at $8000, fix last at $C000
            if (address < 0xC000) { // switchable lower
                int bank = regPrgBank & 0x0F;
                int base = bank * 0x4000;
                int idx = base + ((address - 0x8000) & 0x3FFF);
                if (idx < prg.length)
                    return prg[idx] & 0xFF;
                return 0;
            } else { // fixed last
                int base = (prg16kBanks - 1) * 0x4000;
                int idx = base + ((address - 0xC000) & 0x3FFF);
                if (idx < prg.length)
                    return prg[idx] & 0xFF;
                return 0;
            }
        }
    }

    @Override
    public void cpuWrite(int address, int value) {
        address &= 0xFFFF;
        // Handle PRG RAM writes ($6000-$7FFF)
        if (address >= 0x6000 && address < 0x8000) {
            if (prgRam != null) {
                prgRam[address - 0x6000] = (byte) (value & 0xFF);
            }
            return;
        }
        if ((address & 0x8000) == 0)
            return; // ignore writes outside mapper control range
        value &= 0xFF;
        if ((value & 0x80) != 0) { // reset shift
            shift = 0x10;
            regControl |= 0x0C; // set PRG mode bits to 11 (fix last bank)
            log("[M1 RESET] ctrl=%02X", regControl & 0xFF);
            return;
        }
        // Shift serially LSB first into shift register; when 5 bits collected (bit4
        // set)
        boolean complete = (shift & 1) == 1; // bit0 marks ready when previous writes filled
        shift >>= 1;
        shift |= (value & 1) << 4; // new bit into bit4
        if (complete) {
            int data = shift & 0x1F;
            int region = (address >> 13) & 0x03; // 0,1,2,3
            switch (region) {
                case 0: // Control
                    regControl = data;
                    log("[M1 CTRL]=%02X", data);
                    break;
                case 1: // CHR bank 0
                    regChrBank0 = data;
                    log("[M1 CHR0] data=%02X", data);
                    break;
                case 2: // CHR bank 1
                    regChrBank1 = data;
                    log("[M1 CHR1] data=%02X", data);
                    break;
                case 3: // PRG bank
                    regPrgBank = data;
                    log("[M1 PRG] data=%02X", data);
                    break;
            }
            shift = 0x10; // reset latch
        }
    }

    @Override
    public int ppuRead(int address) {
        address &= 0x3FFF;
        if (address < 0x2000) {
            if (chrRam != null) {
                return chrRam[address & 0x1FFF] & 0xFF;
            }
            int chrMode = (regControl >> 4) & 0x01; // 0=8KB,1=4KB
            if (chrMode == 0) { // 8KB ignore lowest bit of bank0/1
                int bank8 = (regChrBank0 & 0x1E) >> 1; // 8KB index
                int base = bank8 * 0x2000;
                int idx = base + (address & 0x1FFF);
                if (idx < chr.length)
                    return chr[idx] & 0xFF;
                return 0;
            } else { // two 4KB banks
                if (address < 0x1000) {
                    int bank = regChrBank0 & 0x1F;
                    int base = bank * 0x1000;
                    int idx = base + (address & 0x0FFF);
                    if (idx < chr.length)
                        return chr[idx] & 0xFF;
                } else {
                    int bank = regChrBank1 & 0x1F;
                    int base = bank * 0x1000;
                    int idx = base + (address & 0x0FFF);
                    if (idx < chr.length)
                        return chr[idx] & 0xFF;
                }
                return 0;
            }
        }
        return 0;
    }

    @Override
    public void ppuWrite(int address, int value) {
        address &= 0x3FFF;
        if (address < 0x2000 && chrRam != null) {
            chrRam[address & 0x1FFF] = (byte) (value & 0xFF);
        }
    }

    @Override
    public MirrorType getMirrorType() {
        int mt = regControl & 0x03;
        return switch (mt) {
            case 0 -> MirrorType.SINGLE0;
            case 1 -> MirrorType.SINGLE1;
            case 2 -> MirrorType.VERTICAL;
            case 3 -> MirrorType.HORIZONTAL;
            default -> verticalFromHeader ? MirrorType.VERTICAL : MirrorType.HORIZONTAL;
        };
    }

    @Override
    public byte[] getPrgRam() {
        return prgRam; // direct reference for save/load
    }

    @Override
    public byte[] saveState() {
        // Serialize: control, chr0, chr1, prg, shift, verticalFromHeader flag,
        // registers + prgRam + chrRam(if any)
        int prgRamLen = prgRam != null ? prgRam.length : 0;
        int chrRamLen = chrRam != null ? chrRam.length : 0;
        int headerLen = 8; // 8 bytes of register/meta
        byte[] out = new byte[headerLen + prgRamLen + chrRamLen];
        int p = 0;
        out[p++] = (byte) (regControl & 0xFF);
        out[p++] = (byte) (regChrBank0 & 0xFF);
        out[p++] = (byte) (regChrBank1 & 0xFF);
        out[p++] = (byte) (regPrgBank & 0xFF);
        out[p++] = (byte) (shift & 0xFF);
        out[p++] = (byte) (verticalFromHeader ? 1 : 0);
        out[p++] = (byte) (prgRamLen & 0xFF);
        out[p++] = (byte) (chrRamLen & 0xFF);
        if (prgRamLen > 0) {
            System.arraycopy(prgRam, 0, out, p, prgRamLen);
            p += prgRamLen;
        }
        if (chrRamLen > 0) {
            System.arraycopy(chrRam, 0, out, p, chrRamLen);
            p += chrRamLen;
        }
        return out;
    }

    @Override
    public void loadState(byte[] data) {
        if (data == null || data.length < 8)
            return;
        int p = 0;
        regControl = data[p++] & 0x1F;
        regChrBank0 = data[p++] & 0x1F;
        regChrBank1 = data[p++] & 0x1F;
        regPrgBank = data[p++] & 0x1F;
        shift = data[p++] & 0xFF;
        verticalFromHeader = (data[p++] & 1) != 0;
        int prgRamLen = data[p++] & 0xFF;
        int chrRamLen = data[p++] & 0xFF;
        if (prgRam != null && prgRamLen == prgRam.length && p + prgRamLen <= data.length) {
            System.arraycopy(data, p, prgRam, 0, prgRamLen);
            p += prgRamLen;
        }
        if (chrRam != null && chrRamLen == chrRam.length && p + chrRamLen <= data.length) {
            System.arraycopy(data, p, chrRam, 0, chrRamLen);
            p += chrRamLen;
        }
    }

    /**
     * Enable logging of bank switch messages to debug log.
     * 
     * @param limit
     */
    public void enableBankLogging(int limit) {
        this.bankLog = true;
        if (limit > 0)
            this.bankLogLimit = limit;
    }

    /**
     * Log a bank switch message if logging enabled and under limit.
     * 
     * @param fmt
     * @param args
     */
    private void log(String fmt, Object... args) {
        if (bankLog && bankLogCount < bankLogLimit) {
            Log.debug(GENERAL, fmt, args);
            bankLogCount++;
        }
    }

    // -------------------------- Accessors for tests --------------------------
    public int getControl() {
        return regControl & 0x1F;
    }

    public int getChrBank0() {
        return regChrBank0 & 0x1F;
    }

    public int getChrBank1() {
        return regChrBank1 & 0x1F;
    }

    public int getPrgBank() {
        return regPrgBank & 0x1F;
    }
}