package com.nesemu.rom;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Loads iNES (*.nes) files into {@link INesRom}.
 */
public class RomLoader {

    /**
     * Load iNES ROM from file path.
     * 
     * @param path
     * @return
     * @throws IOException
     */
    public static INesRom load(Path path) throws IOException {
        String nameLc = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (nameLc.endsWith(".zip")) {
            // Load from ZIP: must contain exactly one .nes file
            try (ZipFile zf = new ZipFile(path.toFile())) {
                ZipEntry found = null;
                Enumeration<? extends ZipEntry> en = zf.entries();
                while (en.hasMoreElements()) {
                    ZipEntry e = en.nextElement();
                    if (e.isDirectory())
                        continue;
                    String enLc = e.getName().toLowerCase(Locale.ROOT);
                    if (enLc.endsWith(".nes")) {
                        if (found != null) {
                            throw new IOException("Invalid zipped ROM"); // multiple .nes entries
                        }
                        found = e;
                    }
                }
                if (found == null) {
                    throw new IOException("No NES file in ZIP");
                }
                try (InputStream in = zf.getInputStream(found)) {
                    return load(in);
                }
            }
        } else {
            try (InputStream in = Files.newInputStream(path)) {
                return load(in);
            }
        }
    }

    /**
     * Load iNES ROM from input stream.
     * 
     * @param in
     * @return
     * @throws IOException
     */
    public static INesRom load(InputStream in) throws IOException {
        byte[] header = in.readNBytes(INesHeader.HEADER_SIZE);
        if (header.length < INesHeader.HEADER_SIZE) {
            throw new IOException("File shorter than 16 bytes");
        }
        INesHeader h = INesHeader.parse(header);
        byte[] trainer = null;
        if (h.hasTrainer()) {
            trainer = in.readNBytes(512);
            if (trainer.length < 512)
                throw new IOException("Incomplete trainer");
        }
        int prgSize = h.getPrgRomPages() * 16384;
        byte[] prg = in.readNBytes(prgSize);
        if (prg.length < prgSize)
            throw new IOException("Incomplete PRG-ROM");
        int chrSize = h.getChrRomPages() * 8192;
        byte[] chr = new byte[chrSize];
        if (chrSize > 0) {
            byte[] chrRead = in.readNBytes(chrSize);
            if (chrRead.length < chrSize)
                throw new IOException("Incomplete CHR-ROM");
            System.arraycopy(chrRead, 0, chr, 0, chrSize);
        }
        return new INesRom(h, Arrays.copyOf(prg, prg.length), chr, trainer);
    }
}
