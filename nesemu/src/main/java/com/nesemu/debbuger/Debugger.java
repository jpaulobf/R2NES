package com.nesemu.debbuger;

import com.nesemu.app.EmulatorContext;
import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.function.Consumer;

/**
 * Central controller for R2NES Debugging Tools.
 * Manages the lifecycle of debug windows (Disassembler, Memory, Hex, PPU)
 * and provides a configuration dialog.
 */
public class Debugger {

    private final EmulatorContext context;

    // Flags de estado
    private boolean disassemblerEnabled;
    private boolean memoryEnabled;
    private boolean hexEditorEnabled;
    private boolean ppuViewerEnabled;

    // Referências para as janelas (Placeholders por enquanto)
    private JFrame disassemblerWindow;
    private JFrame memoryWindow;
    private JFrame hexEditorWindow;
    private JFrame ppuViewerWindow;

    public Debugger(EmulatorContext context) {
        this.context = context;
    }

    /**
     * Abre a janela de opções do Debugger (Menu -> Options -> Debugger).
     * @param parent Componente pai para centralizar a janela (pode ser null).
     */
    public void openOptionsWindow(Component parent) {
        Window owner = SwingUtilities.getWindowAncestor(parent);
        JDialog dialog = new JDialog(owner, "Debugger Configuration", Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setLayout(new BorderLayout());

        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));

        JCheckBox chkDisasm = new JCheckBox("Disassembly (CPU Trace)", disassemblerEnabled);
        JCheckBox chkMem = new JCheckBox("Real-time Memory Watch", memoryEnabled);
        JCheckBox chkHex = new JCheckBox("Hex Editor", hexEditorEnabled);
        JCheckBox chkPpu = new JCheckBox("PPU Viewer (Pattern/Nametables)", ppuViewerEnabled);

        panel.add(chkDisasm);
        panel.add(Box.createVerticalStrut(5));
        panel.add(chkMem);
        panel.add(Box.createVerticalStrut(5));
        panel.add(chkHex);
        panel.add(Box.createVerticalStrut(5));
        panel.add(chkPpu);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton btnApply = new JButton("Apply & Close");
        JButton btnCancel = new JButton("Cancel");

        btnCancel.addActionListener(e -> dialog.dispose());
        btnApply.addActionListener(e -> {
            setDisassemblerEnabled(chkDisasm.isSelected());
            setMemoryEnabled(chkMem.isSelected());
            setHexEditorEnabled(chkHex.isSelected());
            setPpuViewerEnabled(chkPpu.isSelected());
            dialog.dispose();
        });

        buttonPanel.add(btnCancel);
        buttonPanel.add(btnApply);

        dialog.add(panel, BorderLayout.CENTER);
        dialog.add(buttonPanel, BorderLayout.SOUTH);
        dialog.pack();
        dialog.setResizable(false);
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
    }

    // --- Setters que ativam/desativam as ferramentas ---

    public void setDisassemblerEnabled(boolean enabled) {
        this.disassemblerEnabled = enabled;
        if (enabled && disassemblerWindow == null) {
            disassemblerWindow = new Disassembler(context);
            disassemblerWindow.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent e) {
                    disassemblerWindow = null;
                }
            });
            disassemblerWindow.setVisible(true);
        } else if (!enabled && disassemblerWindow != null) {
            disassemblerWindow.dispose();
            disassemblerWindow = null;
        }
    }

    public void setMemoryEnabled(boolean enabled) {
        this.memoryEnabled = enabled;
        if (enabled && memoryWindow == null) {
            memoryWindow = new Memory(context);
            memoryWindow.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent e) {
                    memoryWindow = null;
                }
            });
            memoryWindow.setVisible(true);
        } else if (!enabled && memoryWindow != null) {
            memoryWindow.dispose();
            memoryWindow = null;
        }
    }

    public void setHexEditorEnabled(boolean enabled) {
        this.hexEditorEnabled = enabled;
        updateToolWindow("Hex Editor", enabled, hexEditorWindow, w -> hexEditorWindow = w);
    }

    public void setPpuViewerEnabled(boolean enabled) {
        this.ppuViewerEnabled = enabled;
        if (enabled && ppuViewerWindow == null) {
            ppuViewerWindow = new PpuViewer(context);
            ppuViewerWindow.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent e) {
                    ppuViewerWindow = null;
                }
            });
            ppuViewerWindow.setVisible(true);
        } else if (!enabled && ppuViewerWindow != null) {
            ppuViewerWindow.dispose();
            ppuViewerWindow = null;
        }
    }

    /**
     * Lógica genérica para abrir/fechar janelas de ferramentas.
     */
    private void updateToolWindow(String title, boolean enabled, JFrame currentWindow, Consumer<JFrame> windowSetter) {
        if (enabled && currentWindow == null) {
            // Criar nova janela
            JFrame frame = new JFrame(title + " - R2NES Debugger");
            frame.setSize(400, 300);
            frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
            
            // Ao fechar pelo 'X', apenas desativa a flag correspondente visualmente (mas mantém estado interno até reconfiguração)
            frame.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent e) {
                    windowSetter.accept(null); // Limpa a referência
                    frame.dispose();
                }
            });

            // Placeholder content
            JLabel label = new JLabel("<html><center><h3>" + title + "</h3><br>Implementação em breve...</center></html>", SwingConstants.CENTER);
            frame.add(label);
            
            frame.setLocationByPlatform(true);
            frame.setVisible(true);
            windowSetter.accept(frame);
        } else if (!enabled && currentWindow != null) {
            // Fechar janela existente
            currentWindow.dispose();
            windowSetter.accept(null);
        }
    }
    
    public void closeAll() {
        if (disassemblerWindow != null) disassemblerWindow.dispose();
        if (memoryWindow != null) memoryWindow.dispose();
        if (hexEditorWindow != null) hexEditorWindow.dispose();
        if (ppuViewerWindow != null) ppuViewerWindow.dispose();
    }
}
