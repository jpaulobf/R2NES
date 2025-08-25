package com.nesemu.tools;

import com.nesemu.cpu.CPU;
import com.nesemu.bus.Bus;
import com.nesemu.bus.interfaces.iBus;
import com.nesemu.ppu.Ppu2C02;
import com.nesemu.mapper.Mapper0;
import com.nesemu.rom.RomLoader;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

/**
 * Differential runner: executes nestest and compares each produced trace line's
 * PC and cumulative cycles against a reference nestest.log, stopping at first
 * mismatch.
 * Focus is to identify earliest timing divergence (e.g., +2 cycles at CDF6
 * TXS).
 */
public class NestestDiffer {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: NestestDiffer <romFile> <referenceLog> [limit]");
            return;
        }
        File romFile = new File(args[0]);
        File refFile = new File(args[1]);
        int limit = args.length >= 3 ? Integer.parseInt(args[2]) : 10000;

        // Load ROM and construct minimal system using Bus + Mapper0 + PPU
        com.nesemu.rom.INesRom rom = RomLoader.load(romFile.toPath());
        if (rom.getHeader().getMapper() != 0) {
            System.err.println("Only mapper 0 supported in this simple differ");
            return;
        }
        Mapper0 mapper0 = new Mapper0(rom);
        Ppu2C02 ppu = new Ppu2C02();
        ppu.reset();
        Bus bus = new Bus();
        bus.attachPPU(ppu);
        bus.attachMapper(mapper0, rom);
        iBus mem = bus; // CPU view
        CPU cpu = new CPU(mem);
        // Force nestest canonical start state
        cpu.forceState(0xC000, 0x00, 0x00, 0x00, 0x24, 0xFD);
        cpu.setTotalCycles(7);

        try (BufferedReader br = new BufferedReader(new FileReader(refFile))) {
            String refLine;
            int step = 0;
            long refCycles = -1;
            while (step < limit && (refLine = br.readLine()) != null) {
                // Parse reference PC and CYC
                String pcHex = refLine.substring(0, 4);
                int refPC = Integer.parseInt(pcHex, 16);
                int refCycIdx = refLine.indexOf("CYC:");
                long refCyc = Long.parseLong(refLine.substring(refCycIdx + 4).trim());
                refCycles = refCyc;

                int actualPC = cpu.getPC();
                if (actualPC != refPC) {
                    dumpMismatch(step, "PC", refPC, actualPC, cpu, mem, refLine);
                    return;
                }

                long actualCycles = cpu.getTotalCycles();
                if (actualCycles != refCyc) {
                    dumpMismatch(step, "CYC", refCyc, actualCycles, cpu, mem, refLine);
                    return;
                }

                // Execute one full instruction (advances totalCycles by its full cost)
                cpu.stepInstruction();
                step++;
            }
            System.out.println("Finished without mismatch up to steps=" + step + " refCycles=" + refCycles);
        }
    }

    private static void dumpMismatch(int step, String kind, long expected, long actual, CPU cpu, iBus mem,
            String refLine) {
        System.out.println("=== MISMATCH at step " + step + " kind=" + kind + " expected=" + expected + " actual="
                + actual + " ===");
        System.out.println("Reference: " + refLine);
        System.out.printf("CPU State: PC=%04X A=%02X X=%02X Y=%02X P=%02X SP=%02X TotalCyc=%d\n",
                cpu.getPC(), cpu.getA(), cpu.getX(), cpu.getY(), cpu.getStatusByte(), cpu.getSP(),
                cpu.getTotalCycles());
        System.out.printf("Last Op: %02X base=%d extra=%d branchTaken=%s pageCross=%s\n",
                cpu.getLastOpcodeByte(), cpu.getLastBaseCycles(), cpu.getLastExtraCycles(),
                cpu.wasLastBranchTaken(), cpu.wasLastBranchPageCross());
        // Dump small stack slice
        int sp = cpu.getSP() & 0xFF;
        System.out.print("Stack (top 8): ");
        for (int i = 0; i < 8; i++) {
            int addr = 0x0100 | ((sp - i) & 0xFF);
            System.out.printf("%02X ", mem.read(addr));
        }
        System.out.println();
    }
}