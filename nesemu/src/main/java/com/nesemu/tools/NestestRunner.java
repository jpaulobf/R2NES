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
        // Baseline cycles (nestest expects first logged line CYC:7); we set pre-exec
        // cycles=7
        cpu.setTotalCycles(7);

        long executed = 0;
        String termination = null;
        try (PrintWriter w = new PrintWriter(outPath.toFile())) {
            while (true) {
                int pcBefore = cpu.getPC();
                int opcode = mem.read(pcBefore);

                // Build trace line with cycles BEFORE executing instruction (nestest style)
                TraceLine tl = buildTraceLine(cpu, mem, pcBefore, opcode);
                tl.cyclesAfter = cpu.getTotalCycles(); // actually cycles before execution here
                computePpu(tl);
                w.println(tl.format());

                cpu.stepInstruction(); // execute one instruction (advances cycles)
                executed++;

                // Termination: reference trace ends after RTS (or historically BRK) at $C66E.
                if (pcBefore == 0xC66E && (opcode == 0x60 || opcode == 0x00)) {
                    termination = String.format(Locale.ROOT, "Reached expected %s at $C66E",
                            opcode == 0x60 ? "RTS" : "BRK");
                    break;
                }
                // Heuristic safety: only trigger if we *pass* the known final pre-exec cycle
                // count (26554). Using >= caused us to stop immediately after the inner
                // subroutine RTS at $C6A2 (which brings total cycles to 26554) and before we
                // could fetch & log the outer final RTS at $C66E. So we require strictly >.
                if (cpu.getTotalCycles() > 26554) {
                    termination = "Heuristic stop (exceeded expected end cycles without hitting $C66E)";
                    break;
                }
                if (executed >= maxInstructions) {
                    termination = "Reached instruction cap (" + maxInstructions + ")";
                    break;
                }
            }
        }
        if (termination == null)
            termination = "Unknown";
        System.out.printf(Locale.ROOT, "Trace written to %s (%d instructions, cycles=%d). Termination: %s%n", outPath,
                executed, cpu.getTotalCycles(), termination);
    }

    // -------- Compatibility trace building (items 1-7) --------
    private static class TraceLine {
        int pc;
        int op1;
        int op2;
        int length;
        String asm;
        long cyclesAfter;
        int ppuScanline;
        int ppuX;
        String bytesField; // padded bytes representation

        String format() {
            // Format similar to nestest.log:
            // PC(4) bytes(9) asm(padded) A:.. X:.. Y:.. P:.. SP:.. PPU: sss,xxx CYC:cycles
            return String.format(Locale.ROOT,
                    "%04X  %-9s %-31s A:%02X X:%02X Y:%02X P:%02X SP:%02X PPU:%3d,%3d CYC:%d",
                    pc, bytesField, asm, a, x, y, p, sp, ppuScanline, ppuX, cyclesAfter);
        }

        int a, x, y, p, sp; // register snapshot before execution (as per reference)
    }

    private static TraceLine buildTraceLine(CPU cpu, Memory mem, int pc, int opcode) {
        TraceLine tl = new TraceLine();
        tl.pc = pc;
        tl.op1 = mem.read((pc + 1) & 0xFFFF);
        tl.op2 = mem.read((pc + 2) & 0xFFFF);
        tl.a = cpu.getA();
        tl.x = cpu.getX();
        tl.y = cpu.getY();
        tl.p = cpu.getStatusByte();
        tl.sp = cpu.getSP();

        Decoded dec = decodeForAsm(cpu, mem, pc, opcode, tl.op1, tl.op2);
        tl.length = dec.length;
        tl.asm = dec.asm;
        tl.bytesField = formatBytes(opcode, tl.op1, tl.op2, tl.length);
        return tl;
    }

    private static String formatBytes(int op, int b1, int b2, int len) {
        switch (len) {
            case 1:
                return String.format(Locale.ROOT, "%02X", op); // will be padded by %-9s field
            case 2:
                return String.format(Locale.ROOT, "%02X %02X", op, b1);
            default:
                return String.format(Locale.ROOT, "%02X %02X %02X", op, b1, b2);
        }
    }

    private static class Decoded {
        int length;
        String asm;
    }

    private static Decoded decodeForAsm(CPU cpu, Memory mem, int pc, int opcode, int op1, int op2) {
        Decoded d = new Decoded();
        Opcode opEnum = Opcode.fromByte(opcode);
        String mnemonic = (opEnum != null) ? opEnum.name() : "???";
        if (mnemonic.equals("DOP") || mnemonic.equals("TOP"))
            mnemonic = "NOP"; // normalize
        int len = 1;
        String operand = "";
        boolean showValue = false;
        int effAddr = -1;

        switch (opcode) {
            // Accumulator (len 1)
            case 0x0A:
            case 0x2A:
            case 0x4A:
            case 0x6A:
                operand = "A";
                break;
            // Immediate (len 2)
            case 0xA9:
            case 0xA2:
            case 0xA0:
            case 0x69:
            case 0x29:
            case 0x0B:
            case 0x2B:
            case 0xAB:
            case 0xC9:
            case 0xE0:
            case 0xC0:
            case 0x49:
            case 0x09:
            case 0xE9:
            case 0x4B:
            case 0x6B:
            case 0xCB:
            case 0x80:
            case 0x82:
            case 0x89:
            case 0xC2:
            case 0xE2:
            case 0xEB:
                len = 2;
                operand = String.format(Locale.ROOT, "#$%02X", op1);
                break; // no memory value
            // Relative branches (len 2)
            case 0x10:
            case 0x30:
            case 0x50:
            case 0x70:
            case 0x90:
            case 0xB0:
            case 0xD0:
            case 0xF0:
                len = 2;
                int relTarget = (pc + 2 + (byte) op1) & 0xFFFF;
                operand = String.format(Locale.ROOT, "$%04X", relTarget);
                break;
            // Zero page (len 2)
            case 0xA5:
            case 0xA6:
            case 0xA4:
            case 0x65:
            case 0x25:
            case 0xC5:
            case 0xE4:
            case 0xC4:
            case 0x45:
            case 0x05:
            case 0xE5:
            case 0x06:
            case 0x26:
            case 0x46:
            case 0x66:
            case 0x47:
            case 0x07:
            case 0x67:
            case 0x27:
            case 0x85:
            case 0x86:
            case 0x84:
            case 0x87:
            case 0xA7:
            case 0x24:
            case 0xC6:
            case 0xE6:
            case 0xC7:
            case 0xE7:
            case 0x04:
            case 0x44:
            case 0x64:
                len = 2;
                effAddr = op1 & 0xFF;
                operand = String.format(Locale.ROOT, "$%02X", effAddr);
                showValue = true;
                break;
            // Zero page,X (len 2)
            case 0xB5:
            case 0xB4:
            case 0x75:
            case 0x35:
            case 0xD5:
            case 0x55:
            case 0x15:
            case 0xF5:
            case 0x16:
            case 0x36:
            case 0x56:
            case 0x76:
            case 0x57:
            case 0x17:
            case 0x77:
            case 0x37:
            case 0x95:
            case 0x94:
            case 0xD6:
            case 0xF6:
            case 0xD7:
            case 0xF7:
            case 0x14:
            case 0x34:
            case 0x54:
            case 0x74:
            case 0xD4:
            case 0xF4:
                len = 2;
                int eff = (op1 + cpu.getX()) & 0xFF;
                effAddr = eff;
                operand = String.format(Locale.ROOT, "$%02X,X", op1);
                showValue = true;
                break;
            // Zero page,Y
            case 0xB6:
            case 0x96:
            case 0x97:
            case 0xB7:
                len = 2;
                eff = (op1 + cpu.getY()) & 0xFF;
                effAddr = eff;
                operand = String.format(Locale.ROOT, "$%02X,Y", op1);
                showValue = true;
                break;
            // (Indirect,X)
            case 0xA1:
            case 0x61:
            case 0x21:
            case 0xC1:
            case 0x41:
            case 0x01:
            case 0xE1:
            case 0x81:
            case 0x83:
            case 0x43:
            case 0x03:
            case 0xC3:
            case 0xE3:
            case 0xA3:
            case 0x63:
            case 0x23:
                len = 2;
                int zp = (op1 + cpu.getX()) & 0xFF;
                int lo = mem.read(zp);
                int hi = mem.read((zp + 1) & 0xFF);
                effAddr = (hi << 8) | lo;
                operand = String.format(Locale.ROOT, "($%02X,X)", op1);
                showValue = true;
                break;
            // (Indirect),Y
            case 0xB1:
            case 0x71:
            case 0x31:
            case 0xD1:
            case 0x51:
            case 0x11:
            case 0xF1:
            case 0x91:
            case 0x93:
            case 0x53:
            case 0x13:
            case 0xD3:
            case 0xF3:
            case 0xB3:
            case 0x73:
            case 0x33:
                len = 2;
                zp = op1 & 0xFF;
                lo = mem.read(zp);
                hi = mem.read((zp + 1) & 0xFF);
                int base = (hi << 8) | lo;
                effAddr = (base + cpu.getY()) & 0xFFFF;
                operand = String.format(Locale.ROOT, "($%02X),Y", op1);
                showValue = true;
                break;
            // Absolute (len 3) (no index)
            case 0x20:
            case 0x0C:
            case 0x2C:
            case 0x4C:
            case 0x6C:
            case 0xAD:
            case 0xAE:
            case 0xAC:
            case 0x6D:
            case 0x2D:
            case 0xCD:
            case 0xEC:
            case 0xCC:
            case 0x4D:
            case 0x0D:
            case 0xED:
            case 0x0E:
            case 0x2E:
            case 0x4E:
            case 0x6E:
            case 0x8D:
            case 0x8E:
            case 0x8C:
            case 0xCE:
            case 0xEE:
            case 0xAF:
            case 0xCF:
            case 0xEF:
            case 0x6F:
            case 0x2F:
            case 0x4F:
            case 0x0F:
                len = 3;
                int addr = (op2 << 8) | op1;
                operand = (opcode == 0x6C) ? String.format(Locale.ROOT, "($%04X)", addr)
                        : String.format(Locale.ROOT, "$%04X", addr);
                effAddr = addr;
                // For absolute addressing we also show the pre-execution memory value for store
                // instructions (nestest style: value shown is value BEFORE the write).
                showValue = (opcode != 0x20 && opcode != 0x4C && opcode != 0x6C);
                break; // no value for JSR/JMP
            // Absolute,X
            case 0xBD:
            case 0xBC:
            case 0x7D:
            case 0x3D:
            case 0xDD:
            case 0x5D:
            case 0x1D:
            case 0xFD:
            case 0x1E:
            case 0x3E:
            case 0x5E:
            case 0x7E:
            case 0x1C:
            case 0x3C:
            case 0x5C:
            case 0x7C:
            case 0xDC:
            case 0xFC:
            case 0x9D:
            case 0xDE:
            case 0xFE:
            case 0xDF:
            case 0xFF:
            case 0x1F:
            case 0x3F:
            case 0x5F:
            case 0x7F:
                len = 3;
                addr = (op2 << 8) | op1;
                effAddr = (addr + cpu.getX()) & 0xFFFF;
                operand = String.format(Locale.ROOT, "$%04X,X", addr);
                showValue = true;
                break;
            // Absolute,Y
            case 0xB9:
            case 0xBE:
            case 0x79:
            case 0x39:
            case 0xD9:
            case 0x59:
            case 0x19:
            case 0xF9:
            case 0x99:
            case 0x9B:
            case 0x7B:
            case 0x9F:
            case 0x9E:
            case 0x5B:
            case 0x1B:
            case 0xDB:
            case 0xFB:
            case 0xBF:
            case 0xBB:
            case 0x3B:
                len = 3;
                addr = (op2 << 8) | op1;
                effAddr = (addr + cpu.getY()) & 0xFFFF;
                operand = String.format(Locale.ROOT, "$%04X,Y", addr);
                showValue = true;
                break;
            default:
                // implied / single byte
                break;
        }

        // Build asm string with optional memory value
        if (showValue && effAddr >= 0) {
            int memVal = mem.read(effAddr); // pre-execution value (stores: old value)
            operand = operand + String.format(Locale.ROOT, " = %02X", memVal);
        }
        d.length = len;
        d.asm = operand.isEmpty() ? mnemonic : (mnemonic + " " + operand);
        return d;
    }

    private static void computePpu(TraceLine tl) {
        // Approximate: PPU cycles = CPU cycles * 3
        long ppuTotal = (long) tl.cyclesAfter * 3L;
        tl.ppuScanline = (int) ((ppuTotal / 341) % 262);
        tl.ppuX = (int) (ppuTotal % 341);
    }

}
