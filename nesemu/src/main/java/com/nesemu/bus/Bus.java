package com.nesemu.bus;

import com.nesemu.apu.APU;
import com.nesemu.bus.interfaces.NesBus;
import com.nesemu.cpu.CPU;
import com.nesemu.io.Controller;
import com.nesemu.mapper.Mapper;
import com.nesemu.memory.Memory;
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
public class Bus implements NesBus {

    // --- Backing unified Memory (internal RAM, PRG ROM, SRAM) ---
    private final Memory memory = new Memory();

    // --- Connected devices ---
    private CPU cpuRef;
    private PPU ppu; // PPU (register interface via $2000-$2007)
    @SuppressWarnings("unused")
    private APU apu; // APU ($4000-$4017 subset) placeholder until APU implemented
    private Controller pad1; // Controller 1 ($4016)
    private Controller pad2; // Controller 2 ($4017, read side when not APU)
    private Mapper mapper; // Cartridge mapper for PRG / CHR
    @SuppressWarnings("unused")
    private INesRom rom; // Raw iNES ROM (for potential direct CHR access later)

    // --- Test shadow for unmapped regions (0x2000-0x5FFF) ---
    // Many unit tests write/read arbitrary addresses in these ranges as if they
    // were
    // generic RAM. Real NES hardware maps PPU registers (0x2000-0x3FFF), APU/IO
    // (0x4000-0x401F) and expansion (0x4020-0x5FFF). To keep tests simple while we
    // haven't fully emulated those devices yet, we provide a shadow backing so
    // values written there can be read back. This avoids false negatives where
    // the previous flat Memory implementation accepted the write but the Bus
    // discarded it.
    private final int[] testShadow = new int[0x6000 - 0x2000]; // 0x4000 bytes

    // OAM DMA source page latch (write to $4014)
    private int pendingDmaPage = -1;

    // (PRG RAM / SRAM now accessed via backingMemory)

    // Minimal APU register latches ($4000-$4017)
    private final int[] apuRegs = new int[0x18];

    // --- Debug instrumentation ---
    private boolean logPpuRegs = false;
    private int ppuRegLogLimit = 500; // evita inundar saída
    private int ppuRegLogCount = 0;
    private int watchReadAddress = -1; // endereço para break em leitura
    private boolean watchTriggered = false;
    private int watchTriggerCount = 0;
    private int watchReadLimit = 1;

    // Global verbose logging toggle (shared concept with PPU) to silence bus
    // diagnostics
    private static volatile boolean globalVerbose = true;

    public static void setGlobalVerbose(boolean enable) {
        globalVerbose = enable;
    }

    public static boolean isGlobalVerbose() {
        return globalVerbose;
    }

    private static void vprintf(String fmt, Object... args) {
        if (globalVerbose)
            System.out.printf(fmt, args);
    }

    public void enablePpuRegLogging(int limit) {
        this.logPpuRegs = true;
        if (limit > 0)
            this.ppuRegLogLimit = limit;
    }

    public void setWatchReadAddress(int address, int limit) {
        this.watchReadAddress = address & 0xFFFF;
        this.watchTriggered = false;
        this.watchTriggerCount = 0;
        if (limit > 0)
            this.watchReadLimit = limit;
    }

    public boolean isWatchTriggered() {
        return watchTriggered;
    }

    public void clearWatchTrigger() {
        watchTriggered = false;
    }

    public void attachPPU(PPU ppu) {
        this.ppu = ppu;
        // If CPU already attached through some pathway, attempt to link for NMI
        tryLinkCpuToPpu();
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
        if (rom != null && mapper == null) {
            // Load ROM PRG into backing memory for fallback CPU tests without mapper logic.
            memory.loadCartridge(rom);
        }
    }

    public void attachCPU(CPU cpu) {
        this.cpuRef = cpu;
        tryLinkCpuToPpu();
    }

    private void tryLinkCpuToPpu() {
        if (ppu != null && cpuRef != null) {
            try {
                // Call Ppu2C02.attachCPU if exists
                ppu.getClass().getMethod("attachCPU", CPU.class).invoke(ppu, cpuRef);
            } catch (Exception ignored) {
            }
        }
    }

    /** CPU read (8-bit). */
    public int cpuRead(int address) {
        address &= 0xFFFF;
        if (address < 0x2000) {
            return memory.readInternalRam(address);
        } else if (address < 0x4000) {
            // PPU registers mirrored every 8 bytes
            if (ppu == null) {
                // Fallback to shadow for tests when no PPU attached
                return testShadow[address - 0x2000] & 0xFF;
            }
            int reg = 0x2000 + (address & 0x7);
            int value = readPpuRegister(reg) & 0xFF;
            if (logPpuRegs && ppuRegLogCount < ppuRegLogLimit && globalVerbose) {
                vprintf("[PPU REG RD] %04X = %02X frame=%d scan=%d cyc=%d\n", reg, value, getPpuFrame(),
                        getPpuScanline(), getPpuCycle());
                ppuRegLogCount++;
            }
            if (watchReadAddress == reg) {
                watchTriggerCount++;
                watchTriggered = true;
                if (watchTriggerCount <= watchReadLimit) {
                    vprintf("[WATCH READ HIT] addr=%04X count=%d frame=%d scan=%d cyc=%d val=%02X\n", reg,
                            watchTriggerCount, getPpuFrame(), getPpuScanline(), getPpuCycle(), value);
                }
            }
            return value;
        } else if (address < 0x6000) {
            // Expansion ROM / rarely used (shadow)
            return testShadow[address - 0x2000] & 0xFF;
        } else {
            // PRG ROM region; prefer mapper; else fall back to backingMemory contents.
            if (mapper != null) {
                return mapper.cpuRead(address);
            }
            // Mirror 16KB if only first half loaded (handled by loadCartridge logic in
            // backingMemory)
            return memory.read(address);
        }
    }

    /** CPU write (8-bit). */
    public void cpuWrite(int address, int value) {
        address &= 0xFFFF;
        value &= 0xFF;
        if (address < 0x2000) {
            memory.writeInternalRam(address, value);
            return;
        } else if (address < 0x4000) {
            if (ppu != null) {
                int regFull = 0x2000 + (address & 0x7);
                if (logPpuRegs && ppuRegLogCount < ppuRegLogLimit && globalVerbose) {
                    vprintf("[PPU REG WR] %04X = %02X frame=%d scan=%d cyc=%d\n", regFull, value & 0xFF,
                            getPpuFrame(), getPpuScanline(), getPpuCycle());
                    ppuRegLogCount++;
                }
                writePpuRegister(0x2000 + (address & 0x7), value);
            } else {
                // Shadow store for tests
                testShadow[address - 0x2000] = value;
            }
            return;
        } else if (address < 0x4014) {
            // APU + IO writes
            if (address == 0x4016) {
                if (pad1 != null)
                    pad1.write(value);
                if (pad2 != null)
                    pad2.write(value);
            }
            if (address >= 0x4000 && address <= 0x4013) {
                apuRegs[address - 0x4000] = value; // store
            } else if (address == 0x4015) {
                apuRegs[0x15] = value;
            } else if (address == 0x4017) {
                apuRegs[0x17] = value; // frame counter mode latch
            }
            // TODO: route APU register writes (0x4000-0x4013,4015,4017) once APU
            // implemented.
            return;
        } else if (address == 0x4014) {
            // OAM DMA trigger: value is high page of source address (value * 0x100)
            pendingDmaPage = value;
            if (globalVerbose) {
                vprintf("[CPU WR 4014] page=%02X frame=%d scan=%d cyc=%d\n", value & 0xFF, getPpuFrame(),
                        getPpuScanline(), getPpuCycle());
            }
            performOamDma();
            return;
        } else if (address < 0x4020) {
            // APU status / frame counter etc. (stub)
            return;
        } else if (address < 0x6000) {
            testShadow[address - 0x2000] = value; // expansion shadow
            return;
        } else if (address < 0x8000) {
            if (mapper != null) {
                mapper.cpuWrite(address, value);
            }
            memory.writeSram(address, value);
            return;
        } else {
            // Mapper control / PRG writes (if mapper wants them). Backing memory is ROM,
            // ignore.
            if (mapper != null) {
                mapper.cpuWrite(address, value);
            } else {
                // Test scenario: allow direct injection of program bytes into fallback
                // backingMemory
                // so unit tests can place opcodes / vectors in $8000-$FFFF without a mapper.
                memory.write(address, value);
            }
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
        if (pendingDmaPage < 0) {
            return; // nothing queued
        }
        if (ppu == null) {
            if (globalVerbose)
                vprintf("[DMA OAM SKIP] page=%02X motivo=PPU-null\n", pendingDmaPage & 0xFF);
            pendingDmaPage = -1;
            return;
        }
        boolean haveCpu = (cpuRef != null);
        if (!haveCpu && globalVerbose) {
            vprintf("[DMA OAM WARN] page=%02X CPU-ref ausente (sem stall)\n", pendingDmaPage & 0xFF);
        }
        int base = (pendingDmaPage & 0xFF) << 8;
        int[] firstBytes = new int[32];
        // Copy 256 bytes from CPU memory space (using cpuRead for mapper/RAM
        // visibility)
        for (int i = 0; i < 256; i++) {
            int val = cpuRead(base + i);
            if (i < firstBytes.length)
                firstBytes[i] = val & 0xFF;
            try {
                ppu.getClass().getMethod("dmaOamWrite", int.class, int.class).invoke(ppu, i, val & 0xFF);
            } catch (Exception e) {
                // fallback: if method missing abort silently
                break;
            }
        }
        // Raw dump (8 sprites * 4 bytes) independent of reflection helper success
        if (globalVerbose) {
            vprintf("[DMA OAM RAW] page=%02X bytes0-31:", pendingDmaPage & 0xFF);
            for (int i = 0; i < firstBytes.length; i++) {
                if (i % 16 == 0)
                    System.out.print("\n  ");
                vprintf("%02X ", firstBytes[i]);
            }
            System.out.println();
        }
        // Após cópia, logar primeiros sprites para diagnóstico (máx 8)
        try {
            byte[] oamDump = (byte[]) ppu.getClass().getMethod("getOamCopy").invoke(ppu);
            int spritesToShow = 8;
            if (globalVerbose) {
                vprintf("[DMA OAM] page=%02X primeiros %d sprites:\n", pendingDmaPage & 0xFF, spritesToShow);
                for (int i = 0; i < spritesToShow; i++) {
                    int off = i * 4;
                    int y = oamDump[off] & 0xFF;
                    int tile = oamDump[off + 1] & 0xFF;
                    int attr = oamDump[off + 2] & 0xFF;
                    int x = oamDump[off + 3] & 0xFF;
                    vprintf("  #%02d Y=%02X tile=%02X attr=%02X X=%02X\n", i, y, tile, attr, x);
                }
            }
        } catch (Exception e) {
            // ignore if accessor missing
        }
        // Stall CPU: 513 cycles normally, +1 if current CPU total cycles is odd (i.e.,
        // if DMA
        // started on an odd CPU cycle). We approximate by inspecting
        // cpuRef.getTotalCycles().
        if (haveCpu) {
            long cpuCycles = cpuRef.getTotalCycles();
            int stall = 513 + ((cpuCycles & 1) != 0 ? 1 : 0);
            try {
                cpuRef.getClass().getMethod("addDmaStall", int.class).invoke(cpuRef, stall);
            } catch (Exception e) {
                // ignore
            }
        }
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
        memory.clearRAM();
    }

    // Test helper: expose minimal write for loading code without mapper
    public Memory getMemory() {
        return memory;
    }

    // Debug: retornar mapper concreto se for Mapper0
    public com.nesemu.mapper.Mapper0 getMapper0() {
        if (mapper instanceof com.nesemu.mapper.Mapper0) {
            return (com.nesemu.mapper.Mapper0) mapper;
        }
        return null;
    }

    // PPU state helpers for logging
    private long getPpuFrame() {
        try {
            return (long) ppu.getClass().getMethod("getFrame").invoke(ppu);
        } catch (Exception e) {
            return -1;
        }
    }

    private int getPpuScanline() {
        try {
            return (int) ppu.getClass().getMethod("getScanline").invoke(ppu);
        } catch (Exception e) {
            return -1;
        }
    }

    private int getPpuCycle() {
        try {
            return (int) ppu.getClass().getMethod("getCycle").invoke(ppu);
        } catch (Exception e) {
            return -1;
        }
    }
}
