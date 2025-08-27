package com.nesemu.emulator;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.nesemu.rom.INesHeader;
import com.nesemu.rom.INesRom;

/**
 * Basic tests for the emulator frame stepping loop: ensures stepFrame()
 * advances
 * at least one PPU frame boundary and runFrames(N) advances N frames.
 */
public class EmulatorFrameLoopTest {

    private INesRom dummyRom() {
        byte[] header = new byte[16];
        header[0] = 'N';
        header[1] = 'E';
        header[2] = 'S';
        header[3] = 0x1A;
        header[4] = 1; // PRG 16KB
        header[5] = 1; // CHR 8KB
        header[6] = 0;
        header[7] = 0;
        INesHeader h = INesHeader.parse(header);
        byte[] prg = new byte[0x4000];
        byte[] chr = new byte[0x2000];
        return new INesRom(h, prg, chr, null);
    }

    @Test
    public void stepFrameAdvancesExactlyOneOrMore() {
        NesEmulator emu = new NesEmulator(dummyRom());
        long before = emu.getFrame();
        emu.stepFrame();
        long after = emu.getFrame();
        assertTrue(after > before, "stepFrame deve avançar pelo menos um frame");
        assertEquals(before + 1, after, "stepFrame deve avançar exatamente 1 frame");
    }

    @Test
    public void runFramesAdvancesRequestedCount() {
        NesEmulator emu = new NesEmulator(dummyRom());
        long start = emu.getFrame();
        emu.runFrames(5);
        assertEquals(start + 5, emu.getFrame(), "runFrames deve avançar N frames");
    }
}
