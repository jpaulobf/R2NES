package com.nesemu.gui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;

/**
 * Simple modal dialog allowing the user to configure the default ROM directory.
 */
public final class RomDirectoryConfigDialog extends JDialog {

    public static final class Result {
        private final boolean saved;
        private final boolean cleared;
        private final Path directory;

        private Result(boolean saved, boolean cleared, Path directory) {
            this.saved = saved;
            this.cleared = cleared;
            this.directory = directory;
        }

        public boolean isSaved() {
            return saved;
        }

        public boolean isCleared() {
            return cleared;
        }

        public Optional<Path> getDirectory() {
            return Optional.ofNullable(directory);
        }
    }

    private Result result = new Result(false, false, null);
    private final JTextField dirField;

    public RomDirectoryConfigDialog(JFrame owner, Path currentDefault, Path lastUsed) {
        super(owner, "Default ROM Directory", true);
        dirField = new JTextField();
        if (currentDefault != null)
            dirField.setText(currentDefault.toString());
        else if (lastUsed != null)
            dirField.setText(lastUsed.toString());
        buildUi(lastUsed);
        setMinimumSize(new Dimension(500, 150));
        pack();
        setLocationRelativeTo(owner);
    }

    private void buildUi(Path lastUsed) {
        setLayout(new BorderLayout(10, 10));

        JPanel infoPanel = new JPanel(new BorderLayout(5, 5));
        infoPanel.add(new JLabel("Escolha o diretório padrão utilizado ao carregar ROMs."), BorderLayout.NORTH);
        if (lastUsed != null) {
            infoPanel.add(new JLabel("Último diretório utilizado: " + lastUsed), BorderLayout.SOUTH);
        }
        add(infoPanel, BorderLayout.NORTH);

        JPanel fieldPanel = new JPanel(new BorderLayout(5, 5));
        fieldPanel.add(new JLabel("Diretório padrão:"), BorderLayout.WEST);
        fieldPanel.add(dirField, BorderLayout.CENTER);
        JButton browse = new JButton("Procurar...");
        browse.addActionListener(e -> browseForDirectory());
        fieldPanel.add(browse, BorderLayout.EAST);
        add(fieldPanel, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 10));
        JButton clear = new JButton("Limpar");
        clear.addActionListener(e -> onClear());
        JButton cancel = new JButton("Cancelar");
        cancel.addActionListener(e -> onCancel());
        JButton save = new JButton("Salvar");
        save.addActionListener(e -> onSave());
        buttons.add(clear);
        buttons.add(cancel);
        buttons.add(save);
        add(buttons, BorderLayout.SOUTH);
    }

    private void browseForDirectory() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        String current = dirField.getText();
        if (current != null && !current.isBlank()) {
            try {
                chooser.setCurrentDirectory(Path.of(current.trim()).toFile());
            } catch (Exception ignore) {
            }
        }
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            if (chooser.getSelectedFile() != null) {
                dirField.setText(chooser.getSelectedFile().toPath().toString());
            }
        }
    }

    private void onSave() {
        String raw = dirField.getText();
        if (raw == null || raw.isBlank()) {
            JOptionPane.showMessageDialog(this, "Informe um diretório ou utilize 'Limpar' para remover o padrão.",
                    "Diretório inválido", JOptionPane.WARNING_MESSAGE);
            return;
        }
        Path sel;
        try {
            sel = Path.of(raw.trim());
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Caminho inválido: " + ex.getMessage(),
                    "Diretório inválido", JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (!Files.isDirectory(sel)) {
            JOptionPane.showMessageDialog(this, "O caminho selecionado não é um diretório existente.",
                    "Diretório inválido", JOptionPane.ERROR_MESSAGE);
            return;
        }
        result = new Result(true, false, sel.toAbsolutePath().normalize());
        dispose();
    }

    private void onClear() {
        result = new Result(true, true, null);
        dispose();
    }

    private void onCancel() {
        result = new Result(false, false, null);
        dispose();
    }

    public Result showDialog() {
        setVisible(true);
        return result;
    }
}
