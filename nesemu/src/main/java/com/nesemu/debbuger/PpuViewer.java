package com.nesemu.debbuger;

import com.nesemu.app.EmulatorContext;
import com.nesemu.ppu.PPU;
import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;

public class PpuViewer extends JFrame {

    private final EmulatorContext context;
    private final Timer refreshTimer;
    private final JPanel canvas;
    
    // Duas imagens de 128x128 (16x16 tiles de 8x8 pixels)
    private final BufferedImage patternTable0 = new BufferedImage(128, 128, BufferedImage.TYPE_INT_RGB);
    private final BufferedImage patternTable1 = new BufferedImage(128, 128, BufferedImage.TYPE_INT_RGB);
    
    // Paleta simples (escala de cinza) para visualização
    private static final int[] PALETTE = { 0x000000, 0x555555, 0xAAAAAA, 0xFFFFFF };

    public PpuViewer(EmulatorContext context) {
        super("PPU Viewer - Pattern Tables");
        this.context = context;

        canvas = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                // Desenha as duas tabelas lado a lado com escala 2x
                g.drawImage(patternTable0, 10, 10, 256, 256, null);
                g.drawImage(patternTable1, 276, 10, 256, 256, null);
                
                g.setColor(Color.WHITE);
                g.drawString("Table 0 ($0000)", 10, 280);
                g.drawString("Table 1 ($1000)", 276, 280);
            }
        };
        canvas.setPreferredSize(new Dimension(542, 300));
        canvas.setBackground(Color.DARK_GRAY);
        add(canvas);

        refreshTimer = new Timer(200, e -> updateTables()); // 5 FPS
        refreshTimer.start();

        pack();
        setLocationByPlatform(true);
        
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                refreshTimer.stop();
            }
        });
    }

    private void updateTables() {
        if (context.emulator == null) return;
        // Cast para PPU concreta para acessar debugRead (NesPPU interface não expõe leitura direta)
        if (context.emulator.getPpu() instanceof PPU ppu) {
            // Renderiza Tabela 0 ($0000-$0FFF)
            renderPatternTable(ppu, 0x0000, patternTable0);
            // Renderiza Tabela 1 ($1000-$1FFF)
            renderPatternTable(ppu, 0x1000, patternTable1);
            
            canvas.repaint();
        }
    }

    private void renderPatternTable(PPU ppu, int baseAddr, BufferedImage img) {
        // Itera sobre 256 tiles (16x16 grid)
        for (int tileY = 0; tileY < 16; tileY++) {
            for (int tileX = 0; tileX < 16; tileX++) {
                int tileIndex = tileY * 16 + tileX;
                int tileAddr = baseAddr + tileIndex * 16;
                
                // Desenha o tile 8x8
                for (int row = 0; row < 8; row++) {
                    // Lê os dois planos de bits do tile
                    // Nota: Usamos ppu.debugRead() para acessar a memória de vídeo (CHR)
                    // Isso mostrará o que a PPU vê *agora* (bancos atuais do Mapper).
                    int lo = ppu.debugRead(tileAddr + row);
                    int hi = ppu.debugRead(tileAddr + row + 8);
                    
                    for (int col = 0; col < 8; col++) {
                        int bit = 7 - col;
                        int pixel = ((hi >> bit) & 1) << 1 | ((lo >> bit) & 1);
                        int color = PALETTE[pixel];
                        
                        img.setRGB(tileX * 8 + col, tileY * 8 + row, color);
                    }
                }
            }
        }
    }
}
