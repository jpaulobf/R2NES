package com.nesemu.debbuger;

import com.nesemu.app.EmulatorContext;
import com.nesemu.bus.interfaces.NesBus;
import com.nesemu.ppu.PPU;
import com.nesemu.ppu.interfaces.NesPPU;
import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Clipboard;


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
        list.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        list.setBackground(Color.BLACK);
        list.setForeground(Color.GREEN);

        // Define um protótipo para calcular a largura correta da janela
        list.setPrototypeCellValue("0000: 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00  ................");

        // Adiciona KeyListener para Ctrl+C
        list.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.isControlDown() && e.getKeyCode() == KeyEvent.VK_C) {
                    copySelectedLines();
                }
            }
        });

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

    /**
     * Copia as linhas selecionadas para a clipboard
     */
    private void copySelectedLines() {
        int[] selectedIndices = list.getSelectedIndices();
        if (selectedIndices.length == 0) return;

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < selectedIndices.length; i++) {
            String line = listModel.getElementAt(selectedIndices[i]);
            sb.append(line);
            if (i < selectedIndices.length - 1) {
                sb.append("\n");
            }
        }

        // Copia para clipboard
        StringSelection stringSelection = new StringSelection(sb.toString());
        Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        clipboard.setContents(stringSelection, null);
    }

    // Modelo virtual para evitar criar 4096 strings a cada frame
    private class MemoryListModel extends AbstractListModel<String> {
        // Cache para evitar copiar a cada frame
        private byte[] cachedNameTables = null;
        private byte[] cachedExRam = null;
        private long lastCacheUpdate = 0;
        private static final long CACHE_TTL_MS = 50; // 50ms cache

        @Override
        public int getSize() {
            return 4096; // 64KB total / 16 bytes por linha
        }

        @Override
        public String getElementAt(int index) {
            if (context.emulator == null) return "Emulation not running";
            NesBus bus = context.emulator.getBus();
            NesPPU ppu = context.emulator.getPpu();
            if (bus == null) return "No Bus";

            int startAddr = index * 16;
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("%04X: ", startAddr));

            StringBuilder ascii = new StringBuilder();

            // Update cache periodically
            long now = System.currentTimeMillis();
            if (now - lastCacheUpdate > CACHE_TTL_MS) {
                if (ppu instanceof PPU) {
                    cachedNameTables = ((PPU) ppu).getNameTableCopy();
                    cachedExRam = null; // será preenchido abaixo
                }
                lastCacheUpdate = now;
            }

            for (int i = 0; i < 16; i++) {
                int addr = startAddr + i;
                int val = 0;

                // Nametables ($2000-$2FFF) - read from PPU cache
                if (addr >= 0x2000 && addr <= 0x2FFF && cachedNameTables != null) {
                    int ntIndex = addr - 0x2000;
                    // Nametables are 2KB (0x800 bytes), add bounds checking
                    if (ntIndex >= 0 && ntIndex < cachedNameTables.length && ntIndex < 0x800) {
                        val = cachedNameTables[ntIndex] & 0xFF;
                    } else {
                        sb.append("-- ");
                        ascii.append('.');
                        continue;
                    }
                }
                // ExRAM ($5C00-$5FFF) - read from cache
                else if (addr >= 0x5C00 && addr <= 0x5FFF && cachedExRam != null) {
                    int exramIndex = addr - 0x5C00;
                    // ExRAM is 1KB (0x400 bytes), add bounds checking
                    if (exramIndex >= 0 && exramIndex < cachedExRam.length && exramIndex < 0x400) {
                        val = cachedExRam[exramIndex] & 0xFF;
                    } else {
                        sb.append("-- ");
                        ascii.append('.');
                        continue;
                    }
                }
                // Paleta ($3F00-$3FFF) - read from PPU (with mirroring)
                else if (addr >= 0x3F00 && addr <= 0x3FFF) {
                    byte[] palette = null;
                    if (ppu instanceof PPU) {
                        palette = ((PPU) ppu).getPaletteCopy();
                    }
                    if (palette != null) {
                        // Palette mirrors every 32 bytes within the 256-byte range
                        val = palette[(addr - 0x3F00) & 0x1F] & 0xFF;
                    } else {
                        sb.append("IO ");
                        ascii.append('.');
                        continue;
                    }
                }
                // APU/IO com side-effects ($2000-$20FF, $4000-$401F) - skip
                else if ((addr >= 0x2000 && addr <= 0x20FF) || (addr >= 0x4000 && addr <= 0x401F)) {
                    sb.append("IO ");
                    ascii.append('.');
                    continue;
                }
                // Tudo mais - ler do bus
                else {
                    val = bus.read(addr) & 0xFF;
                }

                sb.append(String.format("%02X ", val));

                // ASCII representation
                char c = (char) val;
                if (val >= 32 && val <= 126) {
                    ascii.append(c);
                } else {
                    ascii.append('.');
                }
            }

            sb.append(" ").append(ascii);
            return sb.toString();
        }
    }
}
