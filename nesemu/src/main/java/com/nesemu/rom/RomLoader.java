package com.nesemu.rom;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Loads iNES (*.nes) files into {@link INesRom}.
 */
public class RomLoader {

    public static INesRom load(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            return load(in);
        }
    }

    public static INesRom load(InputStream in) throws IOException {
    byte[] header = in.readNBytes(INesHeader.HEADER_SIZE);
    if (header.length < INesHeader.HEADER_SIZE) {
            throw new IOException("File shorter than 16 bytes");
        }
    INesHeader h = INesHeader.parse(header);
        byte[] trainer = null;
        if (h.hasTrainer()) {
            trainer = in.readNBytes(512);
            if (trainer.length < 512) throw new IOException("Incomplete trainer");
        }
        int prgSize = h.getPrgRomPages() * 16384;
        byte[] prg = in.readNBytes(prgSize);
        if (prg.length < prgSize) throw new IOException("Incomplete PRG-ROM");
        int chrSize = h.getChrRomPages() * 8192;
        byte[] chr = new byte[chrSize];
        if (chrSize > 0) {
            byte[] chrRead = in.readNBytes(chrSize);
            if (chrRead.length < chrSize) throw new IOException("Incomplete CHR-ROM");
            System.arraycopy(chrRead, 0, chr, 0, chrSize);
        }
        return new INesRom(h, Arrays.copyOf(prg, prg.length), chr, trainer);
    }
}
