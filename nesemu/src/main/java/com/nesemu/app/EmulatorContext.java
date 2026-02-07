package com.nesemu.app;

import com.nesemu.audio.AudioPlayer;
import com.nesemu.emulator.NesEmulator;
import com.nesemu.rom.INesRom;
import java.nio.file.Path;

/**
 * Holds the mutable state of the current emulation session.
 * Replaces the local 'ref' arrays used previously in Main.
 */
public class EmulatorContext {
    public NesEmulator emulator;
    public INesRom rom;
    public Path romPath;
    public AudioPlayer audio;

    /**
     * Safely stops and clears the current audio player.
     */
    public void stopAudio() {
        if (audio != null) {
            try {
                audio.stop();
            } catch (Exception ignored) {
            }
            audio = null;
        }
    }
}
