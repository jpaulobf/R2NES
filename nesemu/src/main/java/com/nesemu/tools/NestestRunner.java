package com.nesemu.tools;

import com.nesemu.cpu.CPU;
import com.nesemu.cpu.Opcode;
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
            System.err.println("Usage: NestestRunner <nestest.nes> <out.log> [maxInstructions]");
            System.exit(1);
        }
        Path romPath = Path.of(args[0]);
        Path outPath = Path.of(args[1]);
        long maxInstructions = (args.length >= 3) ? Long.parseLong(args[2]) : 200_000; // safety cap

        INesRom rom = RomLoader.load(romPath);
        if (rom.getHeader().getMapper() != 0) {
            throw new IOException("Only mapper 0 supported in this simple runner");
        }
        Memory mem = new Memory();
        mem.loadCartridge(rom);

        CPU cpu = new CPU(mem);
        // Force nestest start state (bypassing reset vector) per official doc
        cpu.forceState(0xC000, 0x00, 0x00, 0x00, 0x24, 0xFD);

        long executed = 0;
        String termination = null;
        try (PrintWriter w = new PrintWriter(outPath.toFile())) {
            while (true) {
                int pc = cpu.getPC();
                int opcode = mem.read(pc);
                w.println(formatTrace(cpu, mem));
                executed++;

                // Termination conditions:
                // 1. Reached BRK at final expected address 0xC66E
                if (pc == 0xC66E && opcode == 0x00) { // BRK opcode
                    termination = "Reached expected BRK at $C66E";
                    break;
                }
                // 2. Safety cap
                if (executed >= maxInstructions) {
                    termination = "Reached instruction cap (" + maxInstructions + ")";
                    break;
                }

                cpu.stepInstruction();
            }
        }
        if (termination == null) termination = "Unknown"; // fallback
        System.out.printf(Locale.ROOT, "Trace written to %s (%d instructions, cycles=%d). Termination: %s%n",
                outPath, executed, cpu.getTotalCycles(), termination);
    }

    private static String formatTrace(CPU cpu, Memory mem) {
        int pc = cpu.getPC();
        int opcode = mem.read(pc);
        // Fetch up to 2 operand bytes for display
        int op1 = mem.read((pc + 1) & 0xFFFF);
        int op2 = mem.read((pc + 2) & 0xFFFF);
        String bytes = String.format(Locale.ROOT, "%02X %02X %02X", opcode, op1, op2);

        DisResult dis = disassemble(cpu, mem, pc, opcode, op1, op2);
        int p = cpu.getStatusByte();
        return String.format(Locale.ROOT,
                "%04X  %-8s %-32s A:%02X X:%02X Y:%02X P:%02X SP:%02X CYC:%d",
                pc,
                bytes,
                dis.text,
                cpu.getA(), cpu.getX(), cpu.getY(), p, cpu.getSP(), cpu.getTotalCycles());
    }

    // --- Minimal disassembler (Task 3) ---
    private static class DisResult { String text; }

    private static DisResult disassemble(CPU cpu, Memory mem, int pc, int opcode, int op1, int op2) {
        DisResult r = new DisResult();
        Opcode opEnum = Opcode.fromByte(opcode);
        String mnemonic = (opEnum != null) ? opEnum.name() : "???";
        // Normalize some unofficial names to match commonly used nestest log labels
        if (mnemonic.equals("DOP") || mnemonic.equals("TOP")) mnemonic = "NOP";
        // Addressing length & formatting
    String operandStr = ""; int effAddr = -1; boolean showMemValue = false; boolean showEffAddr = false;
        switch (opcode) {
            // Accumulator
            case 0x0A: case 0x2A: case 0x4A: case 0x6A:
                operandStr = "A"; break;
            // Immediate (pattern subset)
            case 0xA9: case 0xA2: case 0xA0: case 0x69: case 0x29: case 0x0B: case 0x2B: case 0xAB:
            case 0xC9: case 0xE0: case 0xC0: case 0x49: case 0x09: case 0xE9: case 0x4B: case 0x6B:
            case 0xCB: case 0x80: case 0x82: case 0x89: case 0xC2: case 0xE2: case 0xEB:
                operandStr = String.format(Locale.ROOT, "#$%02X", op1); break;
            // JSR / Absolute / BIT / etc (3 bytes no index)
            case 0x20: case 0x0C: case 0x2C: case 0x4C: case 0x6C: // JSR, TOP, BIT, JMP abs, JMP indirect
            case 0xAD: case 0xAE: case 0xAC: case 0x6D: case 0x2D: case 0xCD: case 0xEC: case 0xCC:
            case 0x4D: case 0x0D: case 0xED: case 0x0E: case 0x2E: case 0x4E: case 0x6E: case 0x8D:
            case 0x8E: case 0x8C: case 0xCE: case 0xEE: case 0xAF: case 0xCF: case 0xEF: case 0x6F:
            case 0x2F: case 0x4F: case 0x0F:
                int addr = (op2 << 8) | op1; if (opcode==0x6C) operandStr = String.format(Locale.ROOT,"($%04X)",addr); else operandStr = String.format(Locale.ROOT,"$%04X",addr); effAddr = addr; showMemValue = !isStore(mnemonic); break;
            // Absolute,X
            case 0xBD: case 0xBC: case 0x7D: case 0x3D: case 0xDD: case 0x5D: case 0x1D: case 0xFD:
            case 0x1E: case 0x3E: case 0x5E: case 0x7E: case 0x1C: case 0x3C: case 0x5C: case 0x7C:
            case 0xDC: case 0xFC: case 0x9D: case 0xDE: case 0xFE: case 0xDF: case 0xFF: case 0x1F: case 0x3F: case 0x5F: case 0x7F:
                addr = (op2 << 8) | op1; effAddr = (addr + cpu.getX()) & 0xFFFF; operandStr = String.format(Locale.ROOT, "$%04X,X", addr); showMemValue = !isStore(mnemonic); showEffAddr = true; break;
            // Absolute,Y
            case 0xB9: case 0xBE: case 0x79: case 0x39: case 0xD9: case 0x59: case 0x19: case 0xF9:
            case 0x99: case 0x9B: case 0x7B: case 0x9F: case 0x9E: case 0x5B: case 0x1B: case 0xDB: case 0xFB: case 0xBF: case 0xBB: case 0x3B:
                addr = (op2 << 8) | op1; effAddr = (addr + cpu.getY()) & 0xFFFF; operandStr = String.format(Locale.ROOT, "$%04X,Y", addr); showMemValue = !isStore(mnemonic); showEffAddr = true; break;
            // Zero page
            case 0xA5: case 0xA6: case 0xA4: case 0x65: case 0x25: case 0xC5: case 0xE4: case 0xC4: case 0x45: case 0x05: case 0xE5: case 0x06:
            case 0x26: case 0x46: case 0x66: case 0x47: case 0x07: case 0x67: case 0x27: case 0x85: case 0x86: case 0x84: case 0x87: case 0xA7:
            case 0x24: case 0xC6: case 0xE6: case 0xC7: case 0xE7: case 0x04: case 0x44: case 0x64:
                effAddr = op1 & 0xFF; operandStr = String.format(Locale.ROOT, "$%02X", effAddr); showMemValue = !isStore(mnemonic); break;
            // Zero page,X
            case 0xB5: case 0xB4: case 0x75: case 0x35: case 0xD5: case 0x55: case 0x15: case 0xF5: case 0x16: case 0x36: case 0x56: case 0x76:
            case 0x57: case 0x17: case 0x77: case 0x37: case 0x95: case 0x94: case 0xD6: case 0xF6: case 0xD7: case 0xF7: case 0x14: case 0x34:
            case 0x54: case 0x74: case 0xD4: case 0xF4:
                effAddr = (op1 + cpu.getX()) & 0xFF; operandStr = String.format(Locale.ROOT, "$%02X,X", op1); showMemValue = !isStore(mnemonic); showEffAddr = true; break;
            // Zero page,Y
            case 0xB6: case 0x96: case 0x97: case 0xB7:
                effAddr = (op1 + cpu.getY()) & 0xFF; operandStr = String.format(Locale.ROOT, "$%02X,Y", op1); showMemValue = !isStore(mnemonic); showEffAddr = true; break;
            // (Indirect,X)
            case 0xA1: case 0x61: case 0x21: case 0xC1: case 0x41: case 0x01: case 0xE1: case 0x81: case 0x83: case 0x43: case 0x03: case 0xC3: case 0xE3: case 0xA3: case 0x63: case 0x23:
                int zp = (op1 + cpu.getX()) & 0xFF; int lo = mem.read(zp); int hi = mem.read((zp + 1)&0xFF); effAddr = (hi<<8)|lo; operandStr = String.format(Locale.ROOT, "($%02X,X)", op1); showMemValue = !isStore(mnemonic); showEffAddr = true; break;
            // (Indirect),Y
            case 0xB1: case 0x71: case 0x31: case 0xD1: case 0x51: case 0x11: case 0xF1: case 0x91: case 0x93: case 0x53: case 0x13: case 0xD3: case 0xF3: case 0xB3: case 0x73: case 0x33:
                zp = op1 & 0xFF; lo = mem.read(zp); hi = mem.read((zp + 1)&0xFF); int base = (hi<<8)|lo; effAddr = (base + cpu.getY()) & 0xFFFF; operandStr = String.format(Locale.ROOT, "($%02X),Y", op1); showMemValue = !isStore(mnemonic); showEffAddr = true; break;
            // Relative branches
            case 0x10: case 0x30: case 0x50: case 0x70: case 0x90: case 0xB0: case 0xD0: case 0xF0:
                int relTarget = (pc + 2 + (byte)op1) & 0xFFFF; operandStr = String.format(Locale.ROOT, "$%04X", relTarget); break;
            // Implied / single byte
            default:
                // includes BRK, PHP, etc.
                break;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "%s", mnemonic));
        if (!operandStr.isEmpty()) sb.append(' ').append(operandStr);
        if (showEffAddr && effAddr >= 0) sb.append(String.format(Locale.ROOT, " @ %04X", effAddr));
        if (showMemValue && effAddr >= 0) sb.append(String.format(Locale.ROOT, " = %02X", mem.read(effAddr)));
        r.text = sb.toString();
        return r;
    }

    private static boolean isStore(String mnemonic) {
        return mnemonic.equals("STA") || mnemonic.equals("STX") || mnemonic.equals("STY") || mnemonic.equals("SAX") || mnemonic.equals("AAX") || mnemonic.equals("SHA") || mnemonic.equals("AHX") || mnemonic.equals("SHX") || mnemonic.equals("SHY") || mnemonic.equals("SHS") || mnemonic.equals("TAS") || mnemonic.equals("AXA");
    }
}
