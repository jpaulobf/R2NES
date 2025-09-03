package com.nesemu.emulator;

import com.nesemu.cpu.CPU;
import com.nesemu.bus.Bus;
import com.nesemu.bus.interfaces.NesBus;
import com.nesemu.mapper.Mapper0;
import com.nesemu.mapper.Mapper3;
import com.nesemu.mapper.Mapper2;
import com.nesemu.mapper.Mapper1;
import com.nesemu.mapper.Mapper5;
import com.nesemu.mapper.Mapper4;
import com.nesemu.mapper.Mapper;
import com.nesemu.ppu.PPU;
import com.nesemu.rom.INesRom;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * NES emulator façade. Now builds a proper Bus + Mapper0 + PPU stack.
 * Legacy constructor (iMemory) kept for CPU unit tests; new constructor accepts
 * an iNES ROM.
 */
public class NesEmulator {
    private final CPU cpu;
    private final NesBus bus; // system bus (CPU visible view via iBus)
    private final PPU ppu; // minimal PPU skeleton
    private final Mapper mapper; // current mapper (Mapper0 or Mapper3 for now)
    private Path romPath; // optional original ROM path for deriving .sav
    private Path autoSavePath;
    private boolean autoSaveEnabled = true;
    private long autoSaveIntervalFrames = 600; // ~10s @60fps
    private long lastAutoSaveFrame = 0;
    // Save state constants
    private static final int STATE_MAGIC = 0x4E455353; // 'NESS'
    private static final int STATE_VERSION = 2;

    /**
     * Legacy path: build minimal stack with no PPU or mapper (for CPU unit tests).
     */
    public NesEmulator() {
        this.bus = new Bus();
        this.ppu = null;
        this.mapper = null;
        this.cpu = new CPU(bus);
    }

    /**
     * New path: build full stack from ROM
     * 
     * @param rom
     */
    public NesEmulator(INesRom rom) {
        int mapperNum = rom.getHeader().getMapper();
        switch (mapperNum) {
            case 0 -> this.mapper = new Mapper0(rom);
            case 2 -> this.mapper = new Mapper2(rom);
            case 1 -> this.mapper = new Mapper1(rom); // MMC1
            case 3 -> this.mapper = new Mapper3(rom);
            case 4 -> this.mapper = new Mapper4(rom); // MMC3 (partial, no IRQ yet)
            case 5 -> this.mapper = new Mapper5(rom); // MMC5 (partial)
            default ->
                throw new IllegalArgumentException(
                        "Unsupported mapper " + mapperNum + " (only 0,1,2,3,4,5 implemented)");
        }
        this.ppu = new PPU();
        this.ppu.reset();
        this.ppu.attachMapper(this.mapper);
        this.bus = new Bus();
        bus.attachPPU(ppu);
        bus.attachMapper(mapper, rom);
        this.cpu = new CPU(bus);
        this.ppu.attachCPU(this.cpu);
    }

    /** Alternative constructor with ROM path (enables automatic .sav naming). */
    public NesEmulator(INesRom rom, Path romFilePath) {
        this(rom);
        this.romPath = romFilePath;
        deriveAutoSavePath();
        tryAutoLoad();
    }

    /**
     * Alternative constructor with ROM + explicit save directory.
     * If saveDir != null, the autosave path is placed inside saveDir (creating it)
     * and PRG RAM is auto-loaded from there (if existing). No lookup is attempted
     * in the ROM directory in this path.
     */
    public NesEmulator(INesRom rom, Path romFilePath, Path saveDir) {
        this(rom);
        this.romPath = romFilePath;
        if (saveDir != null) {
            setSaveDirectory(saveDir); // sets autoSavePath + attempts load from saveDir
        } else {
            deriveAutoSavePath();
            tryAutoLoad();
        }
    }

    /**
     * Get the CPU instance (for direct control in tests or introspection).
     * 
     * @return
     */
    public CPU getCpu() {
        return cpu;
    }

    /**
     * Get the Bus instance (for direct memory access in tests or introspection).
     * 
     * @return
     */
    public NesBus getBus() {
        return bus;
    }

    /**
     * Get the PPU instance (for direct control in tests or introspection).
     * 
     * @return
     */
    public PPU getPpu() {
        return ppu;
    }

    /**
     * Reset CPU and PPU to power-on state (PC from reset vector).
     */
    public void reset() {
        cpu.reset();
        if (ppu != null)
            ppu.reset();
    }

    /**
     * Run N CPU cycles (each CPU cycle advances PPU 3 cycles).
     */
    public void runCycles(long cpuCycles) {
        if (bus == null) {
            // Legacy mode: no PPU stepping
            for (long i = 0; i < cpuCycles; i++)
                cpu.clock();
            return;
        }
        for (long i = 0; i < cpuCycles; i++) {
            cpu.clock();
            ppu.clock();
            ppu.clock();
            ppu.clock();
            // Future: APU clock (every CPU cycle) & poll NMI from PPU
            autoSaveTick();
        }
    }

    /** Run a number of full instructions (blocking). */
    public void runInstructions(long count) {
        for (long i = 0; i < count; i++)
            cpu.stepInstruction();
    }

    /** Advance until end of current frame (when PPU scanline wraps to -1). */
    public void stepFrame() {
        long targetFrame = ppu.getFrame();
        while (ppu.getFrame() == targetFrame) {
            runCycles(1); // 1 CPU cycle -> 3 PPU cycles
        }
    }

    /** Convenience: run a number of whole frames. */
    public void runFrames(int frames) {
        for (int i = 0; i < frames; i++) {
            stepFrame();
        }
    }

    /** Expose current rendered frame index (proxy to PPU). */
    public long getFrame() {
        return ppu.getFrame();
    }

    /**
     * Persist battery-backed PRG RAM (if mapper exposes) to a .sav file.
     * 
     * @param path target path (e.g., romFile.withExtension(".sav"))
     * @throws IOException if IO fails
     */
    public void saveSram(Path path) throws IOException {
        if (mapper == null)
            return;
        byte[] prgRam = mapper.getPrgRam();
        if (prgRam == null)
            return; // nothing to save
        // Write to temp then atomic move to reduce corruption risk
        Path tmp = path.resolveSibling(path.getFileName().toString() + ".tmp");
        Files.write(tmp, prgRam);
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /**
     * Load battery-backed PRG RAM from a .sav file if present.
     * Size mismatch larger than existing buffer is truncated; smaller fills prefix.
     * 
     * @param path path to .sav
     * @return true if loaded, false if file missing or mapper has no PRG RAM
     * @throws IOException on IO error
     */
    public boolean loadSram(Path path) throws IOException {
        if (mapper == null)
            return false;
        byte[] buf = mapper.getPrgRam();
        if (buf == null)
            return false;
        if (!Files.exists(path))
            return false;
        byte[] data = Files.readAllBytes(path);
        int len = Math.min(buf.length, data.length);
        System.arraycopy(data, 0, buf, 0, len);
        mapper.onPrgRamLoaded();
        return true;
    }

    // --- Auto save helpers ---
    private void deriveAutoSavePath() {
        if (romPath == null)
            return;
        String fileName = romPath.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        String base = (dot > 0) ? fileName.substring(0, dot) : fileName;
        this.autoSavePath = romPath.getParent().resolve(base + ".sav");
    }

    private void tryAutoLoad() {
        if (autoSavePath == null)
            return;
        try {
            loadSram(autoSavePath);
        } catch (IOException ignored) {
        }
    }

    private void autoSaveTick() {
        if (!autoSaveEnabled || autoSavePath == null)
            return;
        if (mapper == null || mapper.getPrgRam() == null)
            return;
        long frame = ppu.getFrame();
        if (frame - lastAutoSaveFrame >= autoSaveIntervalFrames) {
            try {
                saveSram(autoSavePath);
                lastAutoSaveFrame = frame;
            } catch (IOException ignored) {
            }
        }
    }

    /** Enable/disable automatic periodic SRM saves. */
    public void setAutoSaveEnabled(boolean enabled) {
        this.autoSaveEnabled = enabled;
    }

    /** Set autosave interval in frames (default 600 ≈10s). */
    public void setAutoSaveIntervalFrames(long frames) {
        if (frames > 0)
            this.autoSaveIntervalFrames = frames;
    }

    /** Force immediate save if possible. */
    public void forceAutoSave() {
        if (autoSavePath != null) {
            try {
                saveSram(autoSavePath);
            } catch (IOException ignored) {
            }
        }
    }

    /** Get current autosave path (may be null). */
    public Path getAutoSavePath() {
        return autoSavePath;
    }

    /**
     * Override the autosave directory. The filename keeps ROM base name + .sav.
     * Creates directory if missing. Call after constructing with ROM path.
     */
    public void setSaveDirectory(Path dir) {
        if (dir == null || romPath == null)
            return;
        try {
            Files.createDirectories(dir);
        } catch (IOException ignored) {
        }
        String fileName = romPath.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        String base = (dot > 0) ? fileName.substring(0, dot) : fileName;
        this.autoSavePath = dir.resolve(base + ".sav");
        // Attempt autoload from new location if file exists
        try {
            loadSram(autoSavePath);
        } catch (IOException ignored) {
        }
    }

    // -------- Save State (snapshot) --------
    /**
     * Serialize full emulator state (CPU registers, internal RAM, PPU core
     * registers, VRAM/OAM/palettes, mapper + CHR RAM, PRG RAM)
     */
    public void saveState(Path path) throws IOException {
        if (cpu == null || bus == null || ppu == null)
            return;
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream(1024 * 64);
        java.io.DataOutputStream dos = new java.io.DataOutputStream(baos);
        // Header
        dos.writeInt(STATE_MAGIC);
        dos.writeInt(STATE_VERSION);
        // CPU core
        dos.writeInt(cpu.getPC());
        dos.writeByte(cpu.getA());
        dos.writeByte(cpu.getX());
        dos.writeByte(cpu.getY());
        dos.writeByte(cpu.getSP());
        dos.writeByte(cpu.getStatusByte());
        // Internal RAM (2KB)
        var mem = bus.getMemory();
        for (int i = 0; i < 0x800; i++)
            dos.writeByte(mem.readInternalRam(i));
        // PPU core registers/state (direct accessors)
        dos.writeInt((int) (ppu.getFrame() & 0x7FFFFFFF));
        dos.writeInt(ppu.getScanline());
        dos.writeInt(ppu.getCycle());
        dos.writeByte(ppu.getMaskRegister()); // 0
        dos.writeByte(ppu.getStatusRegister()); // 1
        dos.writeByte(ppu.getCtrl()); // 2
        if (STATE_VERSION >= 2) {
            // Extra internal latch state (addrLatchHigh, oamAddr, readBuffer)
            dos.writeBoolean(ppu.isAddrLatchHigh()); // 3
            dos.writeByte(ppu.getOamAddr()); // 4
            dos.writeByte(ppu.getReadBuffer()); // 5
        }
        dos.writeShort(ppu.getVramAddress() & 0x3FFF);
        dos.writeShort(ppu.getTempAddress() & 0x3FFF);
        dos.writeByte(ppu.getFineX() & 0x07);
        // PPU memory copies
        byte[] oam = ppu.getOamCopy();
        dos.writeInt(oam.length);
        dos.write(oam);
        byte[] nt = ppu.getNameTableCopy();
        dos.writeInt(nt.length);
        dos.write(nt);
        byte[] pal = ppu.getPaletteCopy();
        dos.writeInt(pal.length);
        dos.write(pal);
        // Mapper specific
        byte[] mapperData = mapper != null ? mapper.saveState() : null;
        if (mapperData != null) {
            dos.writeInt(mapperData.length);
            dos.write(mapperData);
        } else {
            dos.writeInt(0);
        }
        // PRG RAM (battery) embed for completeness
        byte[] prgRam = mapper != null ? mapper.getPrgRam() : null;
        if (prgRam != null) {
            dos.writeInt(prgRam.length);
            dos.write(prgRam);
        } else {
            dos.writeInt(0);
        }
        dos.flush();
        byte[] blob = baos.toByteArray();
        Path tmp = path.resolveSibling(path.getFileName().toString() + ".tmp");
        Files.write(tmp, blob);
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** Restore emulator state from saveState file. */
    public boolean loadState(Path path) throws IOException {
        if (!Files.exists(path))
            return false;
        if (cpu == null || bus == null || ppu == null)
            return false;
        byte[] data = Files.readAllBytes(path);
        java.io.DataInputStream dis = new java.io.DataInputStream(new java.io.ByteArrayInputStream(data));
        int magic = dis.readInt();
        if (magic != STATE_MAGIC)
            return false;
        int ver = dis.readInt();
        if (ver > STATE_VERSION)
            return false; // unknown future version
        int pc = dis.readInt();
        int a = dis.readUnsignedByte();
        int x = dis.readUnsignedByte();
        int y = dis.readUnsignedByte();
        int sp = dis.readUnsignedByte();
        int p = dis.readUnsignedByte();
        // Restore RAM
        var mem = bus.getMemory();
        for (int i = 0; i < 0x800; i++) {
            int val = dis.readUnsignedByte();
            mem.writeInternalRam(i, val);
        }
        // PPU subset
        long frameVal = dis.readInt() & 0xFFFFFFFFL; // may ignore
        int scanline = dis.readInt();
        int cyc = dis.readInt();
        int mask = dis.readUnsignedByte();
        int status = dis.readUnsignedByte();
        int ctrl = dis.readUnsignedByte();
        boolean latchHigh = true;
        int oamAddrVal = 0;
        int readBuf = 0;
        if (ver >= 2) {
            latchHigh = dis.readBoolean();
            oamAddrVal = dis.readUnsignedByte();
            readBuf = dis.readUnsignedByte();
        }
        int vram = dis.readUnsignedShort();
        int tAddr = dis.readUnsignedShort();
        int fineX = dis.readUnsignedByte();
        // Reconstruct CPU core
        cpu.forceState(pc, a, x, y, p, sp);
        // Normalize potentially unsafe scanline/cycle values (defensive for older
        // states)
        if (scanline < -1 || scanline > 260)
            scanline = -1;
        if (cyc < 0 || cyc > 340)
            cyc = 0;
        ppu.forceCoreState(mask, status, ctrl, scanline, cyc, vram, tAddr, fineX, (int) (frameVal & 0xFFFFFFFFL));
        if (ver >= 2) {
            ppu.loadMiscInternalState(latchHigh, oamAddrVal, readBuf);
        }
        // Variable sections
        int oamLen = dis.readInt();
        if (oamLen > 0 && oamLen <= 4096) {
            byte[] oamR = new byte[oamLen];
            dis.readFully(oamR);
            ppu.loadOam(oamR);
        } else if (oamLen > 0) {
            dis.skipBytes(oamLen);
        }
        int ntLen = dis.readInt();
        if (ntLen > 0 && ntLen <= 0x2000) {
            byte[] ntR = new byte[ntLen];
            dis.readFully(ntR);
            ppu.loadNameTable(ntR);
        } else if (ntLen > 0) {
            dis.skipBytes(ntLen);
        }
        int palLen = dis.readInt();
        if (palLen > 0 && palLen <= 256) {
            byte[] palR = new byte[palLen];
            dis.readFully(palR);
            ppu.loadPalette(palR);
        } else if (palLen > 0) {
            dis.skipBytes(palLen);
        }
        int mapperLen = dis.readInt();
        if (mapperLen > 0 && mapperLen < 1_000_000) {
            byte[] mdat = new byte[mapperLen];
            dis.readFully(mdat);
            if (mapper != null)
                mapper.loadState(mdat);
        } else if (mapperLen > 0) {
            dis.skipBytes(mapperLen);
        }
        int prgRamLen = dis.readInt();
        if (prgRamLen > 0) {
            byte[] prg = new byte[prgRamLen];
            dis.readFully(prg);
            if (mapper != null && mapper.getPrgRam() != null && mapper.getPrgRam().length == prgRamLen) {
                System.arraycopy(prg, 0, mapper.getPrgRam(), 0, prgRamLen);
                mapper.onPrgRamLoaded();
            }
        }
        // If we restored mid-frame (cycle!=0 or scanline not at boundary), normalize
        // timing
        if (cyc != 0 || (scanline >= 0 && scanline <= 239)) {
            // This avoids frozen frame due to missing transient pipeline contents
            ppu.normalizeTimingAfterLoad();
        }
        return true;
    }
}