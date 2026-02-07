package com.nesemu.mapper;

/**
 * Interface for the NES memory mapper.
 * This interface defines methods for reading and writing to the CPU and PPU
 * memory.
 * It is used to abstract the memory mapping logic, allowing different mappers
 * to be implemented.
 * Each mapper will handle specific memory addressing and mapping for the NES
 * system.
 */
public abstract class Mapper {

    // Raw ROM/RAM data arrays
    byte[] prg;
    byte[] chr;
    byte[] chrRam;
    byte[] prgRam;
    byte[] exRam = new byte[1024];
    
    // Callback to signal IRQ to the CPU
    protected Runnable irqCallback;

    /**
     * Indicates which subsystem is currently driving a CHR fetch. Default is NONE
     * so mappers can treat CPU/PPU reads uniformly when they do not care about the
     * source. MMC5 relies on this to distinguish background vs sprite fetches.
     */
    public enum ChrReadMode {
        NONE,
        BACKGROUND,
        SPRITE
    }

    private ChrReadMode chrReadMode = ChrReadMode.NONE;

    /**
     * CPU read from mapped PRG/CHR/extra space. Only addresses >= $8000 (and mapper
     * specific ranges like $6000-$7FFF for PRG RAM) are typically serviced here;
     * others handled by bus.
     * 
     * @param address 16-bit CPU address
     * @return unsigned byte (0-255)
     */
    public abstract int cpuRead(int address);

    /**
     * CPU write to mapper-controlled region (bank select registers, PRG RAM, IRQ
     * registers, etc.).
     * 
     * @param address 16-bit CPU address
     * @param value   byte value (only low 8 bits used)
     */
    public abstract void cpuWrite(int address, int value);

    /**
     * PPU pattern table / CHR space read (addresses < $2000). Mapper translates
     * bank registers to physical CHR ROM/RAM.
     * 
     * @param address 14-bit PPU address
     * @return unsigned byte (0-255)
     */
    public abstract int ppuRead(int address);

    /**
     * PPU write into CHR RAM (if present) or mapper special areas (ExRAM, etc.).
     * Ignored for pure CHR ROM mappers.
     * 
     * @param address 14-bit PPU address
     * @param value   byte value
     */
    public abstract void ppuWrite(int address, int value);

    /**
     * Nametable mirroring type (horizontal/vertical) to guide PPU address decode.
     */
    public MirrorType getMirrorType() {
        return MirrorType.HORIZONTAL;
    }

    public enum MirrorType {
        /** Nametable layout: [A A | B B] horizontally. */
        HORIZONTAL,
        /** Nametable layout: [A B | A B] vertically. */
        VERTICAL,
        /** Single-screen using CIRAM page 0. */
        SINGLE0,
        /** Single-screen using CIRAM page 1. */
        SINGLE1
    }

    /**
     * Optional direct access to battery-backed PRG RAM (WRAM) underlying bytes for
     * save persistence.
     * Implementations with PRG RAM should return a live reference (do NOT copy) so
     * emulator can serialize/deserialize.
     * Return null if mapper has no PRG RAM.
     */
    public byte[] getPrgRam() {
        return null;
    }

    /**
     * Optional hook invoked after PRG RAM has been externally loaded (deserialized)
     * so mapper can refresh any checksums/protection.
     * Default no-op.
     */
    public void onPrgRamLoaded() {
    }

    /**
     * Optional opaque mapper state serialization (bank registers, CHR RAM, IRQ
     * counters, etc.).
     * Return a byte array containing ONLY mapper-specific data (do NOT include PRG
     * ROM).
     * Default: no state (null) meaning mapper is stateless.
     */
    public byte[] saveState() {
        return null;
    }

    /**
     * Counterpart to {@link #saveState()} restoring mapper internal registers / CHR
     * RAM.
     * Implementations must tolerate unknown / null data (ignore gracefully).
     */
    public void loadState(byte[] data) {
    }

    /**
     * Reads a byte from the logical nametable space ($2000-$2FFF) applying the
     * mapper's mirroring rules. Mappers that provide their own nametable storage
     * (e.g. MMC5 ExRAM) should override this method.
     */
    public int ppuReadNametable(int address, byte[] ciram) {
        int nt = (address - 0x2000) & 0x0FFF;
        int index = nt & 0x03FF;
        int table = (nt >> 10) & 0x03;
        MirrorType mt = getMirrorType();
        int physical = switch (mt) {
            case VERTICAL -> table & 0x01; // 0,1,0,1
            case HORIZONTAL -> (table >> 1); // 0,0,1,1
            case SINGLE0 -> 0;
            case SINGLE1 -> 1;
            default -> table & 0x01;
        };
        int offset = (physical * 0x0400) + index;
        return ciram[offset & 0x07FF] & 0xFF;
    }

    /**
     * Writes a byte to logical nametable space applying mirroring rules. Override
     * to direct writes to mapper-specific storage.
     */
    public void ppuWriteNametable(int address, int value, byte[] ciram) {
        int nt = (address - 0x2000) & 0x0FFF;
        int index = nt & 0x03FF;
        int table = (nt >> 10) & 0x03;
        MirrorType mt = getMirrorType();
        int physical = switch (mt) {
            case VERTICAL -> table & 0x01;
            case HORIZONTAL -> (table >> 1);
            case SINGLE0 -> 0;
            case SINGLE1 -> 1;
            default -> table & 0x01;
        };
        int offset = (physical * 0x0400) + index;
        ciram[offset & 0x07FF] = (byte) (value & 0xFF);
    }

    /**
     * Allows mappers to override the attribute byte used for background rendering
     * after the PPU has fetched it. Default implementation returns the input
     * value unchanged. MMC5 uses this hook to expose extended attributes stored in
     * ExRAM.
     */
    public int adjustAttribute(int coarseX, int coarseY, int attributeAddress, int currentValue) {
        return currentValue;
    }

    /**
     * Sets the current CHR fetch mode before {@link #ppuRead(int)} is invoked.
     * Default implementation simply records the mode so subclasses can query via
     * {@link #getChrReadMode()}. Subclasses overriding this method should call
     * {@code super.setChrReadMode(mode)} to keep the stored value in sync.
     */
    public void setChrReadMode(ChrReadMode mode) {
        this.chrReadMode = mode != null ? mode : ChrReadMode.NONE;
    }

    /**
     * Retrieves the most recently assigned CHR fetch mode.
     */
    public ChrReadMode getChrReadMode() {
        return chrReadMode;
    }

    /**
     * Sets the callback to trigger a CPU IRQ.
     * @param cb
     */
    public void setIrqCallback(Runnable cb) {
        this.irqCallback = cb;
    }

    /**
     * Called by PPU at a specific cycle (e.g. 260) of each scanline if rendering is enabled.
     * Used by mappers like MMC3 to clock IRQ counters.
     */
    public void onScanline(int scanline) {
    }
}