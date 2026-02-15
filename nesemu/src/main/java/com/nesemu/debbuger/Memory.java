package com.nesemu.debbuger;

import com.nesemu.app.EmulatorContext;
import com.nesemu.bus.interfaces.NesBus;
import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

public class Memory extends JFrame {

    private final EmulatorContext context;
    private final JList<String> list;
    private final MemoryListModel listModel;
    private final Timer refreshTimer;

    public Memory(EmulatorContext context) {
        super("Memory Viewer - R2NES");
        this.context = context;

        setLayout(new BorderLayout());

        listModel = new MemoryListModel();
        list = new JList<>(listModel);
        list.setFont(new Font("Monospaced", Font.PLAIN, 12));
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setBackground(Color.BLACK);
        list.setForeground(Color.GREEN);
        
        // Define um protótipo para calcular a largura correta da janela
        list.setPrototypeCellValue("0000: 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00  ................");

        JScrollPane scroll = new JScrollPane(list);
        add(scroll, BorderLayout.CENTER);

        // Timer de atualização (10 FPS é suficiente para leitura visual)
        refreshTimer = new Timer(100, e -> {
            if (isVisible()) {
                list.repaint(); 
            }
        });
        refreshTimer.start();

        setSize(600, 400);
        setLocationByPlatform(true);

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                refreshTimer.stop();
            }
        });
    }

    // Modelo virtual para evitar criar 4096 strings a cada frame
    private class MemoryListModel extends AbstractListModel<String> {
        @Override
        public int getSize() {
            return 4096; // 64KB total / 16 bytes por linha
        }

        @Override
        public String getElementAt(int index) {
            if (context.emulator == null) return "Emulation not running";
            NesBus bus = context.emulator.getBus();
            if (bus == null) return "No Bus";

            int startAddr = index * 16;
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("%04X: ", startAddr));
            
            StringBuilder ascii = new StringBuilder();

            for (int i = 0; i < 16; i++) {
                int addr = startAddr + i;
                
                // PROTEÇÃO: Evitar leitura de registradores com side-effects
                // PPU ($2000-$3FFF) e APU/IO ($4000-$401F)
                // Ler $2002 limpa o flag VBlank; ler $2007 avança VRAM; ler $4015 limpa IRQ frame.
                if ((addr >= 0x2000 && addr <= 0x3FFF) || (addr >= 0x4000 && addr <= 0x401F)) {
                    sb.append("IO ");
                    ascii.append('.');
                } else {
                    int val = bus.read(addr) & 0xFF;
                    sb.append(String.format("%02X ", val));
                    
                    // ASCII representation
                    char c = (char) val;
                    if (val >= 32 && val <= 126) {
                        ascii.append(c);
                    } else {
                        ascii.append('.');
                    }
                }
            }
            
            sb.append(" ").append(ascii);
            return sb.toString();
        }
    }
}
