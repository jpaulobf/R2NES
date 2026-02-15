package com.nesemu.debbuger;

import com.nesemu.app.EmulatorContext;
import com.nesemu.bus.interfaces.NesBus;
import com.nesemu.cpu.interfaces.NesCPU;
import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.List;

public class Disassembler extends JFrame {

    private final EmulatorContext context;
    private final JList<String> list;
    private final DefaultListModel<String> listModel;
    private final Timer refreshTimer;
    
    // Cache de decodificação para performance
    private static final String[] MNEMONICS = new String[256];
    private static final int[] LENGTHS = new int[256];

    // Modos de endereçamento (simplificado para display)
    private static final int IMP = 0; // Implied
    private static final int IMM = 1; // Immediate #$00
    private static final int ZP  = 2; // Zero Page $00
    private static final int ZPX = 3; // Zero Page,X $00,X
    private static final int ZPY = 4; // Zero Page,Y $00,Y
    private static final int ABS = 5; // Absolute $0000
    private static final int ABX = 6; // Absolute,X $0000,X
    private static final int ABY = 7; // Absolute,Y $0000,Y
    private static final int IND = 8; // Indirect ($0000)
    private static final int IZX = 9; // (Indirect,X) ($00,X)
    private static final int IZY = 10; // (Indirect),Y ($00),Y
    private static final int REL = 11; // Relative $00 (PC+offset)

    static {
        // Inicializa tabela de opcodes (Subset comum 6502)
        // Formato: initOp(opcode, "MNEM", Mode, Length)
        for (int i = 0; i < 256; i++) {
            MNEMONICS[i] = "???";
            LENGTHS[i] = 1;
        }
        // Load/Store
        initOp(0xA9, "LDA", IMM, 2); initOp(0xA5, "LDA", ZP, 2); initOp(0xB5, "LDA", ZPX, 2); initOp(0xAD, "LDA", ABS, 3); initOp(0xBD, "LDA", ABX, 3); initOp(0xB9, "LDA", ABY, 3); initOp(0xA1, "LDA", IZX, 2); initOp(0xB1, "LDA", IZY, 2);
        initOp(0xA2, "LDX", IMM, 2); initOp(0xA6, "LDX", ZP, 2); initOp(0xB6, "LDX", ZPY, 2); initOp(0xAE, "LDX", ABS, 3); initOp(0xBE, "LDX", ABY, 3);
        initOp(0xA0, "LDY", IMM, 2); initOp(0xA4, "LDY", ZP, 2); initOp(0xB4, "LDY", ZPX, 2); initOp(0xAC, "LDY", ABS, 3); initOp(0xBC, "LDY", ABX, 3);
        initOp(0x85, "STA", ZP, 2);  initOp(0x95, "STA", ZPX, 2); initOp(0x8D, "STA", ABS, 3); initOp(0x9D, "STA", ABX, 3); initOp(0x99, "STA", ABY, 3); initOp(0x81, "STA", IZX, 2); initOp(0x91, "STA", IZY, 2);
        initOp(0x86, "STX", ZP, 2);  initOp(0x96, "STX", ZPY, 2); initOp(0x8E, "STX", ABS, 3);
        initOp(0x84, "STY", ZP, 2);  initOp(0x94, "STY", ZPX, 2); initOp(0x8C, "STY", ABS, 3);
        // Transfers
        initOp(0xAA, "TAX", IMP, 1); initOp(0xA8, "TAY", IMP, 1); initOp(0x8A, "TXA", IMP, 1); initOp(0x98, "TYA", IMP, 1); initOp(0x9A, "TXS", IMP, 1); initOp(0xBA, "TSX", IMP, 1);
        // Stack
        initOp(0x48, "PHA", IMP, 1); initOp(0x68, "PLA", IMP, 1); initOp(0x08, "PHP", IMP, 1); initOp(0x28, "PLP", IMP, 1);
        // Shifts
        initOp(0x0A, "ASL", IMP, 1); initOp(0x06, "ASL", ZP, 2); initOp(0x16, "ASL", ZPX, 2); initOp(0x0E, "ASL", ABS, 3); initOp(0x1E, "ASL", ABX, 3);
        initOp(0x4A, "LSR", IMP, 1); initOp(0x46, "LSR", ZP, 2); initOp(0x56, "LSR", ZPX, 2); initOp(0x4E, "LSR", ABS, 3); initOp(0x5E, "LSR", ABX, 3);
        initOp(0x2A, "ROL", IMP, 1); initOp(0x26, "ROL", ZP, 2); initOp(0x36, "ROL", ZPX, 2); initOp(0x2E, "ROL", ABS, 3); initOp(0x3E, "ROL", ABX, 3);
        initOp(0x6A, "ROR", IMP, 1); initOp(0x66, "ROR", ZP, 2); initOp(0x76, "ROR", ZPX, 2); initOp(0x6E, "ROR", ABS, 3); initOp(0x7E, "ROR", ABX, 3);
        // Logic/Math
        initOp(0x29, "AND", IMM, 2); initOp(0x25, "AND", ZP, 2); initOp(0x35, "AND", ZPX, 2); initOp(0x2D, "AND", ABS, 3); initOp(0x3D, "AND", ABX, 3); initOp(0x39, "AND", ABY, 3); initOp(0x21, "AND", IZX, 2); initOp(0x31, "AND", IZY, 2);
        initOp(0x09, "ORA", IMM, 2); initOp(0x05, "ORA", ZP, 2); initOp(0x15, "ORA", ZPX, 2); initOp(0x0D, "ORA", ABS, 3); initOp(0x1D, "ORA", ABX, 3); initOp(0x19, "ORA", ABY, 3); initOp(0x01, "ORA", IZX, 2); initOp(0x11, "ORA", IZY, 2);
        initOp(0x49, "EOR", IMM, 2); initOp(0x45, "EOR", ZP, 2); initOp(0x55, "EOR", ZPX, 2); initOp(0x4D, "EOR", ABS, 3); initOp(0x5D, "EOR", ABX, 3); initOp(0x59, "EOR", ABY, 3); initOp(0x41, "EOR", IZX, 2); initOp(0x51, "EOR", IZY, 2);
        initOp(0xC9, "CMP", IMM, 2); initOp(0xC5, "CMP", ZP, 2); initOp(0xD5, "CMP", ZPX, 2); initOp(0xCD, "CMP", ABS, 3); initOp(0xDD, "CMP", ABX, 3); initOp(0xD9, "CMP", ABY, 3); initOp(0xC1, "CMP", IZX, 2); initOp(0xD1, "CMP", IZY, 2);
        initOp(0xE0, "CPX", IMM, 2); initOp(0xE4, "CPX", ZP, 2); initOp(0xEC, "CPX", ABS, 3);
        initOp(0xC0, "CPY", IMM, 2); initOp(0xC4, "CPY", ZP, 2); initOp(0xCC, "CPY", ABS, 3);
        initOp(0x69, "ADC", IMM, 2); initOp(0x65, "ADC", ZP, 2); initOp(0x75, "ADC", ZPX, 2); initOp(0x6D, "ADC", ABS, 3); initOp(0x7D, "ADC", ABX, 3); initOp(0x79, "ADC", ABY, 3); initOp(0x61, "ADC", IZX, 2); initOp(0x71, "ADC", IZY, 2);
        initOp(0xE9, "SBC", IMM, 2); initOp(0xE5, "SBC", ZP, 2); initOp(0xF5, "SBC", ZPX, 2); initOp(0xED, "SBC", ABS, 3); initOp(0xFD, "SBC", ABX, 3); initOp(0xF9, "SBC", ABY, 3); initOp(0xE1, "SBC", IZX, 2); initOp(0xF1, "SBC", IZY, 2);
        // Inc/Dec
        initOp(0xE6, "INC", ZP, 2);  initOp(0xF6, "INC", ZPX, 2); initOp(0xEE, "INC", ABS, 3); initOp(0xFE, "INC", ABX, 3);
        initOp(0xC6, "DEC", ZP, 2);  initOp(0xD6, "DEC", ZPX, 2); initOp(0xCE, "DEC", ABS, 3); initOp(0xDE, "DEC", ABX, 3);
        initOp(0xE8, "INX", IMP, 1); initOp(0xC8, "INY", IMP, 1); initOp(0xCA, "DEX", IMP, 1); initOp(0x88, "DEY", IMP, 1);
        // Control
        initOp(0x4C, "JMP", ABS, 3); initOp(0x6C, "JMP", IND, 3);
        initOp(0x20, "JSR", ABS, 3); initOp(0x60, "RTS", IMP, 1); initOp(0x40, "RTI", IMP, 1);
        initOp(0x00, "BRK", IMP, 1); initOp(0xEA, "NOP", IMP, 1);
        // Branches
        initOp(0x10, "BPL", REL, 2); initOp(0x30, "BMI", REL, 2); initOp(0x50, "BVC", REL, 2); initOp(0x70, "BVS", REL, 2);
        initOp(0x90, "BCC", REL, 2); initOp(0xB0, "BCS", REL, 2); initOp(0xD0, "BNE", REL, 2); initOp(0xF0, "BEQ", REL, 2);
        // Flags
        initOp(0x18, "CLC", IMP, 1); initOp(0x38, "SEC", IMP, 1); initOp(0x58, "CLI", IMP, 1); initOp(0x78, "SEI", IMP, 1);
        initOp(0xB8, "CLV", IMP, 1); initOp(0xD8, "CLD", IMP, 1); initOp(0xF8, "SED", IMP, 1);
    }

    private static void initOp(int op, String mnem, int mode, int len) {
        MNEMONICS[op] = mnem;
        LENGTHS[op] = len;
        // Mode storage simplified for this display-only purpose
    }

    public Disassembler(EmulatorContext context) {
        super("Disassembler - R2NES");
        this.context = context;
        
        setLayout(new BorderLayout());
        
        listModel = new DefaultListModel<>();
        list = new JList<>(listModel);
        list.setFont(new Font("Monospaced", Font.PLAIN, 12));
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setBackground(Color.BLACK);
        list.setForeground(Color.GREEN);
        
        JScrollPane scroll = new JScrollPane(list);
        add(scroll, BorderLayout.CENTER);
        
        // Refresh timer (15 FPS)
        refreshTimer = new Timer(66, e -> refresh());
        refreshTimer.start();
        
        setSize(400, 500);
        setLocationByPlatform(true);
        
        // Stop timer on close
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                refreshTimer.stop();
            }
        });
    }

    private void refresh() {
        if (context.emulator == null) return;
        
        // Reflection/Cast to access CPU/Bus if interfaces are generic
        // Assuming standard NesEmulator structure
        NesBus bus = context.emulator.getBus();
        NesCPU cpu = context.emulator.getCpu(); // Cast to concrete if needed for getPC
        
        if (bus == null || cpu == null) return;
        
        int pc = cpu.getPc() & 0xFFFF;
        
        // Heuristic: Try to find a sync point slightly before PC to show context
        // We scan back 20 bytes and try to disassemble forward. If we hit PC exactly, good.
        int startAddr = pc;
        for (int back = 15; back > 0; back--) {
            int candidate = (pc - back) & 0xFFFF;
            int curr = candidate;
            boolean synced = false;
            // Walk forward
            while (curr <= pc) {
                if (curr == pc) {
                    synced = true;
                    break;
                }
                int op = bus.read(curr) & 0xFF;
                curr += LENGTHS[op];
            }
            if (synced) {
                startAddr = candidate;
                break;
            }
        }
        
        List<String> lines = new ArrayList<>();
        int addr = startAddr;
        int selectedIndex = -1;
        
        // Disassemble ~30 instructions
        for (int i = 0; i < 30; i++) {
            if (addr == pc) {
                selectedIndex = i;
            }
            
            int op = bus.read(addr) & 0xFF;
            String mnem = MNEMONICS[op];
            int len = LENGTHS[op];
            
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("%04X  ", addr));
            
            // Bytes
            for (int j = 0; j < 3; j++) {
                if (j < len) {
                    sb.append(String.format("%02X ", bus.read(addr + j) & 0xFF));
                } else {
                    sb.append("   ");
                }
            }
            sb.append("  ").append(mnem);
            
            // Operands (Simplified visualization)
            if (len == 2) {
                int val = bus.read(addr + 1) & 0xFF;
                sb.append(String.format(" $%02X", val));
            } else if (len == 3) {
                int lo = bus.read(addr + 1) & 0xFF;
                int hi = bus.read(addr + 2) & 0xFF;
                int val = (hi << 8) | lo;
                sb.append(String.format(" $%04X", val));
            }
            
            lines.add(sb.toString());
            addr = (addr + len) & 0xFFFF;
        }
        
        // Update UI
        if (!listModel.isEmpty() && listModel.size() == lines.size()) {
            // Try to update in place to avoid flicker
            for (int i = 0; i < lines.size(); i++) {
                if (!listModel.get(i).equals(lines.get(i))) {
                    listModel.set(i, lines.get(i));
                }
            }
        } else {
            listModel.clear();
            for (String s : lines) listModel.addElement(s);
        }
        
        if (selectedIndex >= 0) {
            list.setSelectedIndex(selectedIndex);
            list.ensureIndexIsVisible(selectedIndex);
        }
    }
}
