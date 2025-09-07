package com.nesemu.debbuger;

import com.nesemu.cpu.CPU;
import com.nesemu.bus.Bus;
import com.nesemu.bus.interfaces.NesBus;
import com.nesemu.ppu.PPU;
import com.nesemu.mapper.Mapper0;
import com.nesemu.rom.RomLoader;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import com.nesemu.util.Log;
import static com.nesemu.util.Log.Cat.*;

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
            Log.error(TEST, "Usage: NestestDiffer <romFile> <referenceLog> [limit]");
            return;
        }
        File romFile = new File(args[0]);
        File refFile = new File(args[1]);
        int limit = args.length >= 3 ? Integer.parseInt(args[2]) : 10000;

        // Load ROM and construct minimal system using Bus + Mapper0 + PPU
        com.nesemu.rom.INesRom rom = RomLoader.load(romFile.toPath());
        if (rom.getHeader().getMapper() != 0) {
            Log.error(TEST, "Only mapper 0 supported in this simple differ");
            return;
        }
        Mapper0 mapper0 = new Mapper0(rom);
        PPU ppu = new PPU();
        ppu.reset();
        Bus bus = new Bus();
        bus.attachPPU(ppu);
        bus.attachMapper(mapper0, rom);
        NesBus mem = bus; // CPU view
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
            Log.info(TEST, "Finished without mismatch up to steps=%d refCycles=%d", step, refCycles);
        }
    }

    private static void dumpMismatch(int step, String kind, long expected, long actual, CPU cpu, NesBus mem,
            String refLine) {
        Log.error(TEST, "=== MISMATCH step=%d kind=%s expected=%d actual=%d ===", step, kind, expected, actual);
        Log.error(TEST, "Reference: %s", refLine);
        Log.error(TEST, String.format("CPU State: PC=%04X A=%02X X=%02X Y=%02X P=%02X SP=%02X TotalCyc=%d",
                cpu.getPC(), cpu.getA(), cpu.getX(), cpu.getY(), cpu.getStatusByte(), cpu.getSP(),
                cpu.getTotalCycles()));
        Log.error(TEST, String.format("Last Op: %02X base=%d extra=%d branchTaken=%s pageCross=%s",
                cpu.getLastOpcodeByte(), cpu.getLastBaseCycles(), cpu.getLastExtraCycles(),
                cpu.wasLastBranchTaken(), cpu.wasLastBranchPageCross()));
        int sp = cpu.getSP() & 0xFF;
        StringBuilder stack = new StringBuilder();
        stack.append("Stack (top 8): ");
        for (int i = 0; i < 8; i++) {
            int addr = 0x0100 | ((sp - i) & 0xFF);
            stack.append(String.format("%02X ", mem.read(addr)));
        }
        Log.error(TEST, stack.toString());
    }
}