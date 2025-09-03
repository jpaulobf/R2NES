package com.nesemu.ppu;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.nesemu.emulator.NesEmulator;
import com.nesemu.rom.INesHeader;
import com.nesemu.rom.INesRom;

/**
 * Verifica efeito de fine X: a transição entre tile opaco (tile0) e
 * transparente (tile1)
 * deve ocorrer em X = 8 - fineX (tolerância +/-1 por latência de pipeline
 * inicial) quando
 * a coluna esquerda NÃO está clipada (bit MASK_BG_LEFT ligado).
 */
public class PPUFineXScrollTest {

    private INesRom buildRom() {
        byte[] header = new byte[16];
        header[0] = 'N';
        header[1] = 'E';
        header[2] = 'S';
        header[3] = 0x1A;
        header[4] = 1; // PRG
        header[5] = 1; // CHR
        INesHeader h = INesHeader.parse(header);
        byte[] prg = new byte[0x4000];
        byte[] chr = new byte[0x2000];
        // tile0 opaque (low plane rows 0..7 = 0xFF)
        for (int r = 0; r < 8; r++)
            chr[0x00 + r] = (byte) 0xFF;
        // tile1 transparent (all zeros)
        return new INesRom(h, prg, chr, null);
    }

    private void write(NesEmulator emu, int addr, int value) {
        emu.getBus().write(0x2006, (addr >> 8) & 0x3F);
        emu.getBus().write(0x2006, addr & 0xFF);
        emu.getBus().write(0x2007, value & 0xFF);
    }

    // Retorna primeiro x onde muda de opaco (!=0) para transparente (0) após ter
    // visto opaco
    private int opaqueToTransparentBoundary(int[] idx) {
        int y = 0;
        boolean seenOpaque = false;
        for (int x = 0; x < 64; x++) { // suficiente para dois tiles
            int v = idx[y * 256 + x] & 0x0F;
            if (!seenOpaque) {
                if (v != 0)
                    seenOpaque = true;
            } else {
                if (v == 0)
                    return x; // primeira transparência após trecho opaco
            }
        }
        return -1;
    }

    @Test
    public void fineXScrollBoundaryShifts() {
        INesRom rom = buildRom();

        // Construir sequência de tiles: tile0 (opaco) depois tile1 (transparente)
        // repetindo.
        // Testar fineX 0..7.
        for (int fineX = 0; fineX < 8; fineX++) {
            NesEmulator emu = new NesEmulator(rom);
            // Habilita BG e mostra coluna esquerda (bits: BG enable + BG left)
            emu.getBus().write(0x2001, 0x0A); // 00001010
            // Scroll: primeira escrita define coarseX=0 + fineX; segunda vertical =0
            // Sempre escreve PPUSCROLL para garantir coarseX=0 e fineX pré-definido
            emu.getBus().write(0x2005, fineX & 0x07);
            emu.getBus().write(0x2005, 0x00);
            // Nametable primeira linha: tile0, tile1, tile0, tile1
            write(emu, 0x2000, 0x00);
            write(emu, 0x2001, 0x01);
            write(emu, 0x2002, 0x00);
            write(emu, 0x2003, 0x01);
            // Após usar PPUADDR para escrever tiles, tempAddress foi alterado (coarseX=3).
            // Reescreve PPUSCROLL para restaurar coarseX=0 e fineX desejado antes do início
            // da linha visível.
            emu.getBus().write(0x2005, fineX & 0x07);
            emu.getBus().write(0x2005, 0x00);
            emu.stepFrame();
            int[] idx = emu.getPpu().getBackgroundIndexBufferCopy();
            int boundary = opaqueToTransparentBoundary(idx);
            int expected = 8 - fineX; // desloca para esquerda conforme fineX
            assertTrue(boundary >= expected - 1 && boundary <= expected + 1,
                    "fineX=" + fineX + " boundary=" + boundary + " esperado~=" + expected);
        }
    }
}
