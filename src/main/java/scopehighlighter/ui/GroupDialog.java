package scopehighlighter.ui;

import burp.api.montoya.core.HighlightColor;
import scopehighlighter.model.MatchMode;
import scopehighlighter.model.ScopeGroup;
import scopehighlighter.util.EndpointParser;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.List;

/**
 * Add/Edit dialog for one scope group: title, color, match mode and the
 * endpoint list pasted from Excel.
 */
public final class GroupDialog extends JDialog {

    private final JTextField nameField = new JTextField(28);
    private final JComboBox<HighlightColor> colorCombo = new JComboBox<>(
            new HighlightColor[]{
                    HighlightColor.RED, HighlightColor.ORANGE, HighlightColor.YELLOW,
                    HighlightColor.GREEN, HighlightColor.CYAN, HighlightColor.BLUE,
                    HighlightColor.PINK, HighlightColor.MAGENTA, HighlightColor.GRAY});
    private final JComboBox<MatchMode> modeCombo = new JComboBox<>(MatchMode.values());
    private final JTextArea endpointArea = new JTextArea(12, 48);
    private final JLabel parsedLabel = new JLabel("0 endpoints parsed");

    private ScopeGroup result;
    private final boolean editMode;

    private GroupDialog(Window owner, ScopeGroup existing) {
        super(owner, existing == null ? "Add scope group" : "Edit scope group", ModalityType.APPLICATION_MODAL);
        this.editMode = existing != null;

        colorCombo.setRenderer(new ColorSwatchRenderer());
        if (existing != null) {
            nameField.setText(existing.name());
            colorCombo.setSelectedItem(existing.color());
            modeCombo.setSelectedItem(existing.mode());
            StringBuilder sb = new StringBuilder();
            for (String pattern : existing.patterns()) {
                sb.append(pattern).append('\n');
            }
            endpointArea.setText(sb.toString());
        } else {
            colorCombo.setSelectedItem(HighlightColor.RED);
            modeCombo.setSelectedItem(MatchMode.PREFIX);
        }

        endpointArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        endpointArea.setLineWrap(false);

        DocumentListener listener = new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                updateParsedCount();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                updateParsedCount();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                updateParsedCount();
            }
        };
        endpointArea.getDocument().addDocumentListener(listener);

        setLayout(new BorderLayout(8, 8));
        add(buildForm(), BorderLayout.CENTER);
        add(buildButtons(), BorderLayout.SOUTH);

        getRootPane().setDefaultButton(null);
        pack();
        setMinimumSize(new Dimension(560, 480));
        setLocationRelativeTo(owner);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                result = null;
            }
        });
        updateParsedCount();
    }

    private JPanel buildForm() {
        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(10, 10, 0, 10));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;

        c.gridx = 0;
        c.gridy = 0;
        form.add(new JLabel("Group title:"), c);
        c.gridx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1.0;
        form.add(nameField, c);

        c.gridx = 0;
        c.gridy = 1;
        c.fill = GridBagConstraints.NONE;
        c.weightx = 0;
        form.add(new JLabel("Highlight color:"), c);
        c.gridx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1.0;
        form.add(colorCombo, c);

        c.gridx = 0;
        c.gridy = 2;
        c.fill = GridBagConstraints.NONE;
        c.weightx = 0;
        form.add(new JLabel("Match mode:"), c);
        c.gridx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1.0;
        form.add(modeCombo, c);

        JLabel help = new JLabel(
                "<html><body style='width:430px'>Endpoints (paste straight from Excel): one endpoint per cell/line. "
                        + "Tabs and newlines are treated as separators, commas too (except in regex patterns). "
                        + "Examples: <b>https://admin.site.com/career</b>, <b>/admin/verify-location</b>, "
                        + "<b>admin.site.com</b>. Lines starting with # are ignored.</body></html>");
        c.gridx = 0;
        c.gridy = 3;
        c.gridwidth = 2;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1.0;
        form.add(help, c);

        JScrollPane scroll = new JScrollPane(endpointArea);
        scroll.setPreferredSize(new Dimension(480, 220));
        c.gridx = 0;
        c.gridy = 4;
        c.gridwidth = 2;
        c.fill = GridBagConstraints.BOTH;
        c.weightx = 1.0;
        c.weighty = 1.0;
        form.add(scroll, c);

        c.gridx = 0;
        c.gridy = 5;
        c.gridwidth = 2;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weighty = 0;
        form.add(parsedLabel, c);

        return form;
    }

    private JPanel buildButtons() {
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.setBorder(BorderFactory.createEmptyBorder(0, 10, 10, 10));
        JButton cancel = new JButton("Cancel");
        JButton ok = new JButton(editMode ? "Save" : "Add");
        cancel.addActionListener(e -> {
            result = null;
            dispose();
        });
        ok.addActionListener(e -> onOk());
        buttons.add(cancel);
        buttons.add(ok);
        return buttons;
    }

    private void onOk() {
        String name = nameField.getText().trim();
        if (name.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Please enter a group title.", "Scope Highlighter",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        List<String> endpoints = EndpointParser.parse(endpointArea.getText());
        if (endpoints.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "No endpoints found. Paste at least one endpoint (one per line).",
                    "Scope Highlighter", JOptionPane.WARNING_MESSAGE);
            return;
        }
        result = new ScopeGroup(
                name,
                (HighlightColor) colorCombo.getSelectedItem(),
                (MatchMode) modeCombo.getSelectedItem(),
                true,
                endpoints);
        dispose();
    }

    private void updateParsedCount() {
        int count = EndpointParser.parse(endpointArea.getText()).size();
        parsedLabel.setText(count + " endpoint" + (count == 1 ? "" : "s") + " parsed");
    }

    /** Returns the built group, or null when cancelled. */
    public ScopeGroup showDialog() {
        setVisible(true);
        return result;
    }

    /** Builds the group synchronously and returns it (null when cancelled). */
    public static ScopeGroup ask(Window owner, ScopeGroup existing) {
        GroupDialog dialog = new GroupDialog(owner, existing);
        return dialog.showDialog();
    }

    // ------------------------------------------------------------------
    // color swatch rendering
    // ------------------------------------------------------------------

    /** Colors used for the in-UI swatches (Burp palette approximation). */
    public static Color swatchFor(HighlightColor color) {
        switch (color) {
            case RED:
                return new Color(229, 57, 53);
            case ORANGE:
                return new Color(255, 152, 0);
            case YELLOW:
                return new Color(251, 192, 45);
            case GREEN:
                return new Color(67, 160, 71);
            case CYAN:
                return new Color(0, 188, 212);
            case BLUE:
                return new Color(30, 136, 229);
            case PINK:
                return new Color(236, 64, 122);
            case MAGENTA:
                return new Color(142, 36, 170);
            case GRAY:
            default:
                return new Color(144, 160, 175);
        }
    }

    /** Light tint of a Burp color, used as row background in results tables. */
    public static Color lightTint(HighlightColor color) {
        Color base = swatchFor(color);
        return new Color(
                (base.getRed() + 255 * 3) / 4,
                (base.getGreen() + 255 * 3) / 4,
                (base.getBlue() + 255 * 3) / 4);
    }

    static final class ColorSwatchRenderer extends javax.swing.DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(javax.swing.JList<?> list, Object value, int index,
                                                      boolean isSelected, boolean cellHasFocus) {
            JLabel label = (JLabel) super.getListCellRendererComponent(
                    list, value, index, isSelected, cellHasFocus);
            if (value instanceof HighlightColor) {
                HighlightColor color = (HighlightColor) value;
                label.setText("  " + color.displayName());
                label.setIcon(new ColorIcon(swatchFor(color)));
            }
            return label;
        }
    }

    /** Small filled square used as a color swatch icon. */
    static final class ColorIcon implements javax.swing.Icon {
        private final Color color;

        ColorIcon(Color color) {
            this.color = color;
        }

        @Override
        public int getIconWidth() {
            return 12;
        }

        @Override
        public int getIconHeight() {
            return 12;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            g.setColor(color);
            g.fillRoundRect(x, y, 12, 12, 4, 4);
            g.setColor(Color.DARK_GRAY);
            g.drawRoundRect(x, y, 11, 11, 4, 4);
        }
    }
}
