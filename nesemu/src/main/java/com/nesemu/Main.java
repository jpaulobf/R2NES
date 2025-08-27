package com.nesemu;

import java.nio.file.*;

import com.nesemu.emulator.NesEmulator;
import com.nesemu.gui.NesWindow;
import com.nesemu.rom.INesRom;
import com.nesemu.rom.RomLoader;

/**
 * Simple headless runner: loads a .nes ROM, executes a number of frames and
 * dumps
 * background information for inspection (ASCII tile matrix + PPM of palette
 * indices).
 * Usage: java -cp nesemu.jar com.nesemu.Main path/to/game.nes [frames]
 */
public class Main {
    public static void main(String[] args) throws Exception {
        boolean gui = false;
        String romPath = null;
        int frames = 60;
        for (String a : args) {
            if (a.equalsIgnoreCase("--gui"))
                gui = true;
            else if (a.startsWith("--frames="))
                frames = Integer.parseInt(a.substring(9));
            else if (!a.startsWith("--"))
                romPath = a;
        }
        if (romPath == null)
            romPath = "roms/donkeykong.nes";
        Path p = Path.of(romPath);
        if (!Files.exists(p)) {
            System.err.println("ROM não encontrada: " + romPath);
            return;
        }
        System.out.println("Carregando ROM: " + p.toAbsolutePath());
        INesRom rom = RomLoader.load(p);
        NesEmulator emu = new NesEmulator(rom);
        // Optionally force background enable early (most games set this quickly anyway)
        emu.getBus().cpuWrite(0x2001, 0x08);
        if (gui) {
            NesWindow window = new NesWindow("NESemu - " + p.getFileName(), 3);
            window.show(emu.getPpu().getFrameBuffer());
            System.out.println("Iniciando loop de render em modo GUI (Ctrl+C para sair)...");
            window.startRenderLoop(() -> {
                emu.stepFrame();
            }, 60); // target 60 fps
        } else {
            long start = System.nanoTime();
            for (int i = 0; i < frames; i++) {
                emu.stepFrame();
            }
            long elapsedNs = System.nanoTime() - start;
            double fpsSim = frames / (elapsedNs / 1_000_000_000.0);
            System.out.printf("Frames simulados: %d (%.2f fps simulado)\n", frames, fpsSim);
            // Dump ASCII matrix (first pixel per tile)
            System.out.println("--- Tile index matrix (hex of first pixel per tile) ---");
            emu.getPpu().printTileIndexMatrix();
            // Dump PPM (palette index grayscale)
            Path out = Path.of("background.ppm");
            emu.getPpu().dumpBackgroundToPpm(out);
            System.out.println("PPM gerado: " + out.toAbsolutePath());
        }
    }
}