package com.nesemu.tools;

import com.nesemu.cpu.CPU;
import com.nesemu.memory.Memory;
import com.nesemu.rom.INesRom;
import com.nesemu.rom.RomLoader;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Minimal runner to execute nestest.nes and produce a trace log similar to
 * nestest.log.
 * Usage (example): java -cp target/nesemu-1.0-SNAPSHOT.jar
 * com.nesemu.tools.NestestRunner path/to/nestest.nes out.log
 */
public class NestestRunner {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: NestestRunner <nestest.nes> <out.log>");
            System.exit(1);
        }
        Path romPath = Path.of(args[0]);
        Path outPath = Path.of(args[1]);

        INesRom rom = RomLoader.load(romPath);
        if (rom.getHeader().getMapper() != 0) {
            throw new IOException("Only mapper 0 supported in this simple runner");
        }
        Memory mem = new Memory();
        mem.loadCartridge(rom);

        CPU cpu = new CPU(mem);
        // Force nestest start state (bypassing reset vector)
        cpu.forceState(0xC000, 0x00, 0x00, 0x00, 0x24, 0xFD);

        try (PrintWriter w = new PrintWriter(outPath.toFile())) {
            // Run until the well-known end address 0xC66E reached (after BRK)
            while (true) {
                w.println(formatTrace(cpu, mem));
                if (cpu.getPC() == 0xC66E)
                    break; // stop before executing BRK at end
                cpu.stepInstruction();
            }
        }
        System.out.println("Trace written to " + outPath);
    }

    private static String formatTrace(CPU cpu, Memory mem) {
        int pc = cpu.getPC();
        int opcode = mem.read(pc);
        // Fetch up to 2 operand bytes for display
        int op1 = mem.read((pc + 1) & 0xFFFF);
        int op2 = mem.read((pc + 2) & 0xFFFF);

        // Simple disassembly decoding (minimal; improve as needed)
        String bytes = String.format(Locale.ROOT, "%02X %02X %02X", opcode, op1, op2);

        String mnemonic = "???"; // placeholder; mapping could improve by sharing decode
        // For now we just show raw opcode; full nestest comparison would need proper
        // mnemonic & operand formatting.
        mnemonic = String.format(Locale.ROOT, "OP%02X", opcode);

        int p = cpu.getStatusByte();
        return String.format(Locale.ROOT,
                "%04X  %-8s %-8s A:%02X X:%02X Y:%02X P:%02X SP:%02X CYC:%d",
                pc,
                bytes,
                mnemonic,
                cpu.getA(), cpu.getX(), cpu.getY(), p, cpu.getSP(), cpu.getTotalCycles());
    }
}
