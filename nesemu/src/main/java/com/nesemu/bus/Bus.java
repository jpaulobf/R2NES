package com.nesemu.bus;

import com.nesemu.apu.APU;
import com.nesemu.bus.interfaces.iBus;
import com.nesemu.io.Controller;
import com.nesemu.mapper.Mapper;
import com.nesemu.ppu.PPU;
import com.nesemu.rom.INesRom;

/**
 * NES system bus (CPU address space routing).
 *
 * Responsibilities:
 * - Map CPU reads/writes (0x0000-0xFFFF) to internal RAM, PPU registers, APU,
 * controllers, cartridge/mapper.
 * - Provide simple connect() methods to inject concrete
 * PPU/APU/Mapper/Controllers.
 * - Handle mirroring of internal RAM (2KB @ 0x0000 mirrored to 0x1FFF) and PPU
 * regs (0x2000-0x2007 mirrored to 0x3FFF).
 * - Stub behaviour for yet-unimplemented areas: returns 0 on unmapped reads.
 *
 * Future extensions:
 * - OAM DMA ($4014) timing (currently just performs immediate copy if a DMA
 * handler registered).
 * - Expansion ROM, cartridge RAM via mapper hooks.
 * - APU frame counter / IRQ lines wiring.
 */
public class Bus implements iBus {

    // --- Internal RAM (2KB) ---
    private final int[] ram = new int[0x0800];

    // --- Connected devices ---
    private PPU ppu; // PPU (register interface via $2000-$2007)
    private APU apu; // APU ($4000-$4017 subset)
    private Controller pad1; // Controller 1 ($4016)
    private Controller pad2; // Controller 2 ($4017, read side when not APU)
    private Mapper mapper; // Cartridge mapper for PRG / CHR
    private INesRom rom; // Raw iNES ROM (for potential direct CHR access later)

    // OAM DMA source page latch (write to $4014)
    private int pendingDmaPage = -1;

    public void attachPPU(PPU ppu) {
        this.ppu = ppu;
    }

    public void attachAPU(APU apu) {
        this.apu = apu;
    }

    public void attachControllers(Controller p1, Controller p2) {
        this.pad1 = p1;
        this.pad2 = p2;
    }

    public void attachMapper(Mapper mapper, INesRom rom) {
        this.mapper = mapper;
        this.rom = rom;
    }

    /** CPU read (8-bit). */
    public int cpuRead(int address) {
        address &= 0xFFFF;
        if (address < 0x2000) {
            return ram[address & 0x07FF];
        } else if (address < 0x4000) {
            // PPU registers mirrored every 8 bytes
            if (ppu == null)
                return 0;
            int reg = 0x2000 + (address & 0x7);
            return readPpuRegister(reg);
        } else if (address < 0x4014) {
            // APU + IO (partial). For now, only controllers at $4016/$4017 are meaningful
            // reads; others return 0.
            if (address == 0x4016)
                return pad1 != null ? (pad1.read() & 0xFF) : 0;
            if (address == 0x4017)
                return pad2 != null ? (pad2.read() & 0xFF) : 0; // APU frame counter read ignored for now.
            return 0; // Stub for APU registers until implemented.
        } else if (address == 0x4014) {
            // OAMDMA register read normally open bus; return 0
            return 0;
        } else if (address < 0x4020) {
            // APU / IO continuation (e.g., $4015 status) – stub 0 for now.
            return 0;
        } else if (address < 0x6000) {
            // Expansion ROM / rarely used
            return 0;
        } else if (address < 0x8000) {
            // PRG RAM / Save RAM (through mapper if present)
            if (mapper != null)
                return mapper.cpuRead(address); // allow mapper override
            return 0; // fallback none
        } else {
            // PRG ROM via mapper (or basic mirror logic)
            if (mapper != null)
                return mapper.cpuRead(address);
            // Fallback: open bus 0
            return 0;
        }
    }

    /** CPU write (8-bit). */
    public void cpuWrite(int address, int value) {
        address &= 0xFFFF;
        value &= 0xFF;
        if (address < 0x2000) {
            ram[address & 0x07FF] = value;
            return;
        } else if (address < 0x4000) {
            if (ppu != null)
                writePpuRegister(0x2000 + (address & 0x7), value);
            return;
        } else if (address < 0x4014) {
            // APU + IO writes
            if (address == 0x4016) {
                if (pad1 != null)
                    pad1.write(value);
                if (pad2 != null)
                    pad2.write(value);
            }
            // TODO: route APU register writes (0x4000-0x4013,4015,4017) once APU
            // implemented.
            return;
        } else if (address == 0x4014) {
            // OAM DMA trigger: value is high page of source address (value * 0x100)
            pendingDmaPage = value;
            performOamDma();
            return;
        } else if (address < 0x4020) {
            // APU status / frame counter etc. (stub)
            return;
        } else if (address < 0x6000) {
            // Expansion
            return;
        } else if (address < 0x8000) {
            // PRG RAM via mapper
            if (mapper != null)
                mapper.cpuWrite(address, value);
            return;
        } else {
            // Mapper control / bank switching
            if (mapper != null)
                mapper.cpuWrite(address, value);
        }
    }

    // CpuBus implementation (delegate to cpuRead/cpuWrite)
    @Override
    public int read(int address) {
        return cpuRead(address);
    }

    @Override
    public void write(int address, int value) {
        cpuWrite(address, value);
    }

    /** Perform OAM DMA immediately (blocking copy of 256 bytes). */
    private void performOamDma() {
        if (pendingDmaPage < 0 || ppu == null)
            return;
        // We don't yet have a PPU OAM interface; placeholder for future DMA logic.
        // In a full implementation, the CPU would be stalled for 513 or 514 cycles.
        pendingDmaPage = -1;
    }

    // --- PPU register access stubs ---
    private int readPpuRegister(int reg) {
        try {
            // Use reflection only if PPU concrete exposes methods; else adapt design later.
            // We added readRegister(int) in Ppu2C02.
            return (int) ppu.getClass().getMethod("readRegister", int.class).invoke(ppu, reg);
        } catch (Exception e) {
            return 0;
        }
    }

    private void writePpuRegister(int reg, int value) {
        try {
            ppu.getClass().getMethod("writeRegister", int.class, int.class).invoke(ppu, reg, value);
        } catch (Exception e) {
            // ignore
        }
    }

    // Convenience for tests
    public void clearRam() {
        for (int i = 0; i < ram.length; i++)
            ram[i] = 0;
    }
}
