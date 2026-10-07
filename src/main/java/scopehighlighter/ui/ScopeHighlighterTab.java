package scopehighlighter.ui;

import scopehighlighter.log.MessageRecord;
import scopehighlighter.log.Source;
import scopehighlighter.model.GroupStore;
import scopehighlighter.model.ScopeGroup;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * The "Scope Highlighter" suite tab.
 *
 * Excel-like grid: one bold group row per group and below it one row per
 * endpoint, columns: #, Endpoint / Group, Color, Match, Action. The Action
 * column has three colorized buttons: copy endpoint | show all |
 * notes to organizer - with hover and pressed feedback.
 *
 * Mouse behaviour (Burp-like):
 *   left click           -> select row
 *   double-click group   -> edit the scope group
 *   double-click endpoint-> show all for that endpoint
 *   right-click endpoint -> context menu (Send to Repeater / Intruder /
 *                           Organizer, Copy endpoint, Show all)
 */
public final class ScopeHighlighterTab extends JPanel {

    /** The two Action-column buttons. */
    public enum RowAction {
        // Burp's default orange
        SHOW_ALL("Show Requests", new Color(0xFF, 0x66, 0x33), new Color(0xFF, 0x7A, 0x50), true),
        // no color: plain button look
        TO_ORGANIZER("Notes Organizer", new Color(0xED, 0xED, 0xED), new Color(0xF6, 0xF6, 0xF6), false);

        private final String label;
        private final Color base;
        private final Color hover;
        private final boolean colored;

        RowAction(String label, Color base, Color hover, boolean colored) {
            this.label = label;
            this.base = base;
            this.hover = hover;
            this.colored = colored;
        }

        public String label() {
            return label;
        }

        public Color base() {
            return base;
        }

        public Color hover() {
            return hover;
        }

        public Color pressed() {
            return base.darker();
        }

        /** Colored buttons get white text; the plain one gets dark text. */
        public boolean colored() {
            return colored;
        }

        public Color textColor() {
            return colored ? Color.WHITE : new Color(0x3C, 0x3C, 0x3C);
        }

        public Color borderColor() {
            return colored ? new Color(255, 255, 255, 70) : new Color(0xC2, 0xC2, 0xC2);
        }
    }

    public static final RowAction[] ACTIONS = RowAction.values();

    /** Column index of the Action buttons. */
    public static final int ACTION_COLUMN = 5;

    private final GroupStore store;
    private final Host host;
    private final OutlineTableModel model;
    private final JTable table;
    private final JLabel statusLabel = new JLabel(" ");
    private final ActionButtonsPanel actionPanel = new ActionButtonsPanel();

    public ScopeHighlighterTab(GroupStore store, Host host) {
        super(new BorderLayout(8, 8));
        this.store = store;
        this.host = host;
        this.model = new OutlineTableModel(store);
        this.table = new JTable(model);

        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        add(buildToolbar(), BorderLayout.NORTH);
        add(buildTable(), BorderLayout.CENTER);
        add(statusLabel, BorderLayout.SOUTH);

        refreshAll();
        host.applyTheme(this);

        // keep the Sources column fresh as traffic flows
        javax.swing.Timer sourcesTimer = new javax.swing.Timer(15000, e -> recomputeSources());
        sourcesTimer.setRepeats(true);
        sourcesTimer.start();
    }

    /** Rebuilds the grid and status line (also called by the store change listener). */
    public void refreshAll() {
        model.rebuild();
        recomputeSources();
        refreshStatus();
    }

    /** Fills the "Sources" column: which Burp sources hold data per row. */
    private void recomputeSources() {
        try {
            List<MessageRecord> records = host.allKnownRecords();
            List<String> labels = new ArrayList<>();
            for (int i = 0; i < model.getRowCount(); i++) {
                labels.add(describeSources(matcherFor(model.rowAt(i)), records));
            }
            model.setSourceLabels(labels);
        } catch (Exception e) {
            host.log("Scope Highlighter: sources recompute failed: " + e);
        }
    }

    /**
     * Which sources hold at least one request matching this row, in canonical
     * order: Repeater, Intruder, History, Target, then anything else.
     */
    public static String describeSources(ScopeGroup matcher, List<MessageRecord> records) {
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (MessageRecord record : records) {
            if (matcher.match(record.url()).isPresent()) {
                seen.add(record.source());
            }
        }
        if (seen.isEmpty()) {
            return "\u2014";
        }
        List<String> ordered = new ArrayList<>();
        for (String preferred : new String[]{"Repeater", "Intruder", "History", "Target"}) {
            if (seen.remove(preferred)) {
                ordered.add(preferred);
            }
        }
        ordered.addAll(seen);
        String joined = String.join(", ", ordered);
        return joined.length() > 60 ? joined.substring(0, 57) + "\u2026" : joined;
    }

    // ------------------------------------------------------------------
    // toolbar
    // ------------------------------------------------------------------

    private JPanel buildToolbar() {
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));

        JButton add = new JButton("Add group\u2026");
        JButton edit = new JButton("Edit\u2026");
        JButton delete = new JButton("Delete");
        JButton up = new JButton("\u2191");
        JButton down = new JButton("\u2193");

        add.setToolTipText("Create a new scope group (title, color, endpoint list pasted from Excel)");
        edit.setToolTipText("Edit the group of the selected row (title, color, endpoint list)");
        delete.setToolTipText("Delete the selected group, or remove the selected endpoint from its group");
        up.setToolTipText("Move selected group up (higher match priority)");
        down.setToolTipText("Move selected group down (lower match priority)");

        JCheckBox autoHighlight = new JCheckBox("Auto-highlight new traffic", store.isAutoHighlightEnabled());
        JCheckBox writeNotes = new JCheckBox("Write group name to Notes", store.isWriteNotesEnabled());
        JCheckBox autoOrganizer = new JCheckBox("Auto-add to Organizer", store.isAutoOrganizerEnabled());
        autoHighlight.setToolTipText("Every request flowing through Burp gets highlighted automatically");
        writeNotes.setToolTipText("Also write the group title into the Notes column (only when the note is empty)");
        autoOrganizer.setToolTipText(
                "<html>Every request matching a group is automatically sent to Burp's Organizer,<br>"
                        + "noted with the group title and highlighted in the group color.<br>"
                        + "The Montoya API cannot create Organizer collections - use the note to group them.</html>");

        add.addActionListener(e -> onAdd());
        edit.addActionListener(e -> onEdit());
        delete.addActionListener(e -> onDelete());
        up.addActionListener(e -> onMove(-1));
        down.addActionListener(e -> onMove(1));
        autoHighlight.addActionListener(e -> {
            store.setAutoHighlightEnabled(autoHighlight.isSelected());
            refreshAll();
        });
        writeNotes.addActionListener(e -> {
            store.setWriteNotesEnabled(writeNotes.isSelected());
            refreshAll();
        });
        autoOrganizer.addActionListener(e -> {
            store.setAutoOrganizerEnabled(autoOrganizer.isSelected());
            refreshAll();
        });

        toolbar.add(add);
        toolbar.add(edit);
        toolbar.add(delete);
        toolbar.add(up);
        toolbar.add(down);
        toolbar.add(new JLabel("   "));
        toolbar.add(autoHighlight);
        toolbar.add(writeNotes);
        toolbar.add(autoOrganizer);
        return toolbar;
    }

    // ------------------------------------------------------------------
    // table + action buttons
    // ------------------------------------------------------------------

    private JScrollPane buildTable() {
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);
        table.setRowHeight(26);
        table.setRowMargin(2);
        table.getColumnModel().getColumn(0).setMaxWidth(50);
        table.getColumnModel().getColumn(1).setPreferredWidth(420);
        table.getColumnModel().getColumn(2).setPreferredWidth(110);
        table.getColumnModel().getColumn(3).setPreferredWidth(90);
        table.getColumnModel().getColumn(4).setPreferredWidth(180);
        table.getColumnModel().getColumn(5).setPreferredWidth(360);

        table.getColumnModel().getColumn(1).setCellRenderer(new TextCellRenderer());
        table.getColumnModel().getColumn(2).setCellRenderer(new ColorCellRenderer());
        table.getColumnModel().getColumn(5).setCellRenderer((tbl, value, isSelected, hasFocus, row, column) -> actionPanel);

        table.addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                updateHover(e);
            }
        });

        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                int viewRow = table.rowAtPoint(e.getPoint());
                int viewCol = table.columnAtPoint(e.getPoint());
                if (viewRow < 0) {
                    return;
                }
                if (viewCol == ACTION_COLUMN && e.getButton() == MouseEvent.BUTTON1) {
                    int idx = actionIndexFor(e, viewRow);
                    actionPanel.setPressed(idx);
                    repaintActionCell(viewRow);
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                int viewRow = table.rowAtPoint(e.getPoint());
                int viewCol = table.columnAtPoint(e.getPoint());
                int pressed = actionPanel.pressedIndex();
                actionPanel.setPressed(-1);
                if (viewRow >= 0 && viewCol == ACTION_COLUMN && e.getButton() == MouseEvent.BUTTON1) {
                    repaintActionCell(viewRow);
                    if (pressed >= 0 && pressed == actionIndexFor(e, viewRow)) {
                        fireRowAction(viewRow, pressed);
                    }
                }
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                int viewRow = table.rowAtPoint(e.getPoint());
                int viewCol = table.columnAtPoint(e.getPoint());
                if (viewRow < 0 || viewCol == ACTION_COLUMN) {
                    return;
                }
                OutlineRow row = model.rowAt(table.convertRowIndexToModel(viewRow));
                if (row == null) {
                    return;
                }
                if (e.getClickCount() == 2) {
                    cancelPendingPopup();
                    if (row.isGroupRow()) {
                        onEdit();                      // double-click group -> edit scope group
                    } else {
                        openResults(row, Source.ALL);  // double-click endpoint -> show all
                    }
                } else if (e.getClickCount() == 1 && !row.isGroupRow()) {
                    // Burp-style options on left click, deferred slightly so a
                    // double-click still triggers "show all" instead
                    queuePopup(row, e);
                }
            }

            @Override
            public void mouseExited(MouseEvent e) {
                actionPanel.setHover(-1);
                actionPanel.setPressed(-1);
                table.repaint();
            }
        });

        // right-click also opens the menu immediately (standard Burp behaviour)
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                maybePopup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                maybePopup(e);
            }

            private void maybePopup(MouseEvent e) {
                if (!e.isPopupTrigger()) {
                    return;
                }
                int viewRow = table.rowAtPoint(e.getPoint());
                int viewCol = table.columnAtPoint(e.getPoint());
                if (viewRow < 0 || viewCol == ACTION_COLUMN) {
                    return;
                }
                OutlineRow row = model.rowAt(table.convertRowIndexToModel(viewRow));
                if (row != null && !row.isGroupRow()) {
                    cancelPendingPopup();
                    table.setRowSelectionInterval(viewRow, viewRow);
                    showContextMenu(row, e);
                }
            }
        });
        return new JScrollPane(table);
    }

    // single-click / double-click disambiguation for endpoint rows
    private javax.swing.Timer clickTimer;
    private OutlineRow pendingPopupRow;
    private MouseEvent pendingPopupEvent;

    private void queuePopup(OutlineRow row, MouseEvent e) {
        cancelPendingPopup();
        pendingPopupRow = row;
        pendingPopupEvent = e;
        if (clickTimer == null) {
            clickTimer = new javax.swing.Timer(280, ev -> firePendingPopup());
            clickTimer.setRepeats(false);
        }
        clickTimer.restart();
    }

    private void cancelPendingPopup() {
        if (clickTimer != null) {
            clickTimer.stop();
        }
        pendingPopupRow = null;
        pendingPopupEvent = null;
    }

    private void firePendingPopup() {
        if (pendingPopupRow != null && pendingPopupEvent != null) {
            showContextMenu(pendingPopupRow, pendingPopupEvent);
        }
        pendingPopupRow = null;
        pendingPopupEvent = null;
    }

    private void updateHover(MouseEvent e) {
        int viewRow = table.rowAtPoint(e.getPoint());
        int viewCol = table.columnAtPoint(e.getPoint());
        int idx = -1;
        if (viewRow >= 0 && viewCol == 4) {
            idx = actionIndexFor(e, viewRow);
        }
        if (idx != actionPanel.hoverIndex()) {
            actionPanel.setHover(idx);
            if (viewRow >= 0) {
                repaintActionCell(viewRow);
            } else {
                table.repaint();
            }
        }
    }

    private int actionIndexFor(MouseEvent e, int viewRow) {
        Rectangle cell = table.getCellRect(viewRow, ACTION_COLUMN, false);
        if (cell == null || cell.width <= 0) {
            return -1;
        }
        return actionIndexForCellX(e.getX() - cell.x, cell.width);
    }

    private void repaintActionCell(int viewRow) {
        Rectangle cell = table.getCellRect(viewRow, ACTION_COLUMN, false);
        if (cell != null) {
            table.repaint(cell.x, cell.y - 2, cell.width + 2, cell.height + 4);
        }
    }

    private void fireRowAction(int viewRow, int actionIndex) {
        OutlineRow row = model.rowAt(table.convertRowIndexToModel(viewRow));
        if (row == null) {
            return;
        }
        switch (ACTIONS[actionIndex]) {
            case SHOW_ALL:
                openResults(row, Source.ALL);
                break;
            case TO_ORGANIZER:
                onNotesToOrganizer(row);
                break;
            default:
                break;
        }
    }

    /**
     * Mirrors {@link ActionButtonsPanel}'s geometry (2px inset, 3px gaps,
     * equal widths) so clicks map to the painted buttons exactly.
     */
    public static int actionIndexForCellX(int x, int cellWidth) {
        int count = ACTIONS.length;
        int gap = 3;
        int inset = 2;
        int buttonWidth = (cellWidth - 2 * inset - (count - 1) * gap) / count;
        if (buttonWidth <= 0) {
            return 0;
        }
        int best = 0;
        int bestDistance = Integer.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            int x0 = inset + i * (buttonWidth + gap);
            int x1 = x0 + buttonWidth;
            if (x >= x0 && x < x1) {
                return i;
            }
            int distance = x < x0 ? x0 - x : x - x1;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return best;
    }

    /** Painted, colorized action buttons with hover/pressed feedback. */
    static final class ActionButtonsPanel extends JPanel {
        private int hoverIndex = -1;
        private int pressedIndex = -1;

        ActionButtonsPanel() {
            setOpaque(false);
        }

        void setHover(int index) {
            this.hoverIndex = index;
        }

        void setPressed(int index) {
            this.pressedIndex = index;
        }

        int hoverIndex() {
            return hoverIndex;
        }

        int pressedIndex() {
            return pressedIndex;
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int width = getWidth();
            int height = getHeight();
            int count = ACTIONS.length;
            int gap = 3;
            int inset = 2;
            int buttonWidth = (width - 2 * inset - (count - 1) * gap) / count;
            Font font = getFont().deriveFont(Font.BOLD, 10f);
            g2.setFont(font);
            for (int i = 0; i < count; i++) {
                int x = inset + i * (buttonWidth + gap);
                int y = 1;
                int h = height - 2;
                boolean pressed = i == pressedIndex;
                boolean hover = i == hoverIndex && !pressed;
                Color top = pressed ? ACTIONS[i].pressed() : hover ? ACTIONS[i].hover() : ACTIONS[i].base();
                Color bottom = pressed ? ACTIONS[i].base() : ACTIONS[i].base().darker();
                g2.setPaint(new GradientPaint(x, y, top, x, y + h, bottom));
                g2.fillRoundRect(x, y + (pressed ? 1 : 0), buttonWidth, h - (pressed ? 1 : 0), 8, 8);
                g2.setColor(ACTIONS[i].borderColor());
                g2.drawRoundRect(x, y + (pressed ? 1 : 0), buttonWidth, h - (pressed ? 1 : 0), 8, 8);
                String text = ACTIONS[i].label();
                int textWidth = g2.getFontMetrics().stringWidth(text);
                int textX = x + Math.max(2, (buttonWidth - textWidth) / 2);
                int textY = y + (h + g2.getFontMetrics().getAscent() - g2.getFontMetrics().getDescent()) / 2 + (pressed ? 1 : 0);
                g2.setColor(ACTIONS[i].textColor());
                g2.drawString(text, textX, textY);
            }
            g2.dispose();
        }
    }

    // ------------------------------------------------------------------
    // row actions
    // ------------------------------------------------------------------

    private ScopeGroup matcherFor(OutlineRow row) {
        if (row.isGroupRow()) {
            return new ScopeGroup(row.group.name(), row.group.color(), row.group.mode(),
                    true, row.group.patterns());
        }
        return new ScopeGroup(row.group.name(), row.group.color(), row.group.mode(),
                true, List.of(row.endpoint));
    }

    private void openResults(OutlineRow row, Source source) {
        ResultsFrame frame = new ResultsFrame(row.isGroupRow() ? row.group.name() : row.endpoint,
                matcherFor(row), host);
        frame.setVisible(true);
    }

    private void onCopyEndpoint(OutlineRow row) {
        String text = row.isGroupRow()
                ? String.join("\n", row.group.patterns())
                : row.endpoint;
        host.copyToClipboard(text);
        statusLabel.setText(" copied to clipboard: " + text.replace('\n', ' '));
    }

    private void onNotesToOrganizer(OutlineRow row) {
        statusLabel.setText(" sending to Organizer\u2026");
        int sent = host.syncToOrganizer(matcherFor(row));
        statusLabel.setText(" " + sent + " request(s) sent to Organizer, noted with \""
                + row.group.name() + "\"");
        host.log("Scope Highlighter: organizer sync for \"" + row.group.name() + "\": " + sent + " sent");
    }

    private void showContextMenu(OutlineRow row, MouseEvent e) {
        JPopupMenu menu = new JPopupMenu();

        JMenuItem repeater = new JMenuItem("Send to Repeater");
        repeater.addActionListener(ev -> host.sendToRepeater(row.endpoint));
        JMenuItem intruder = new JMenuItem("Send to Intruder");
        intruder.addActionListener(ev -> host.sendToIntruder(row.endpoint));
        JMenuItem comparer = new JMenuItem("Send to Comparer");
        comparer.addActionListener(ev -> host.sendToComparer(row.endpoint));
        JMenuItem organizer = new JMenuItem("Send to Organizer");
        organizer.addActionListener(ev -> onNotesToOrganizer(row));
        JMenuItem copy = new JMenuItem("Copy endpoint");
        copy.addActionListener(ev -> onCopyEndpoint(row));
        JMenuItem showAll = new JMenuItem("Show all");
        showAll.addActionListener(ev -> openResults(row, Source.ALL));

        menu.add(repeater);
        menu.add(intruder);
        menu.add(comparer);
        menu.add(organizer);
        menu.addSeparator();
        menu.add(copy);
        menu.addSeparator();
        menu.add(showAll);
        menu.show(e.getComponent(), e.getX(), e.getY());
    }

    // ------------------------------------------------------------------
    // group management
    // ------------------------------------------------------------------

    private OutlineRow selectedRow() {
        int viewRow = table.getSelectedRow();
        return viewRow < 0 ? null : model.rowAt(table.convertRowIndexToModel(viewRow));
    }

    private void reselect(OutlineRow row) {
        if (row == null) {
            return;
        }
        for (int i = 0; i < model.getRowCount(); i++) {
            OutlineRow candidate = model.rowAt(i);
            if (candidate.group == row.group
                    && candidate.isGroupRow() == row.isGroupRow()
                    && (row.isGroupRow()
                        ? true
                        : candidate.endpoint != null && candidate.endpoint.equals(row.endpoint))) {
                table.setRowSelectionInterval(i, i);
                return;
            }
        }
    }

    private void onAdd() {
        Window owner = SwingUtilities.getWindowAncestor(this);
        ScopeGroup group = GroupDialog.ask(owner, null);
        if (group != null) {
            store.addGroup(group);
            refreshAll();
            reselect(model.lastGroupRow(group));
            host.reapplyHighlights();
            host.log("Scope Highlighter: added group \"" + group.name() + "\" ("
                    + group.patternCount() + " endpoints, " + group.color().displayName() + ")");
        }
    }

    private void onEdit() {
        OutlineRow row = selectedRow();
        if (row == null) {
            return;
        }
        Window owner = SwingUtilities.getWindowAncestor(this);
        ScopeGroup updated = GroupDialog.ask(owner, row.group);
        if (updated != null) {
            updated.setEnabled(row.group.enabled());
            store.updateGroup(row.groupIndex, updated);
            refreshAll();
            reselect(model.firstGroupRow(updated));
            host.reapplyHighlights();
            host.log("Scope Highlighter: updated group \"" + updated.name() + "\"");
        }
    }

    private void onDelete() {
        OutlineRow row = selectedRow();
        if (row == null) {
            return;
        }
        if (row.isGroupRow()) {
            int confirm = JOptionPane.showConfirmDialog(this,
                    "Delete group \"" + row.group.name() + "\" and its "
                            + row.group.patternCount() + " endpoint(s)?", "Scope Highlighter",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
            if (confirm == JOptionPane.OK_OPTION) {
                store.removeGroup(row.groupIndex);
                refreshAll();
                host.reapplyHighlights();
                host.log("Scope Highlighter: deleted group \"" + row.group.name() + "\"");
            }
        } else {
            int confirm = JOptionPane.showConfirmDialog(this,
                    "Remove endpoint \"" + row.endpoint + "\" from \"" + row.group.name() + "\"?",
                    "Scope Highlighter", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
            if (confirm == JOptionPane.OK_OPTION) {
                List<String> patterns = new ArrayList<>(row.group.patterns());
                patterns.remove(row.endpointIndex);
                ScopeGroup updated = new ScopeGroup(row.group.name(), row.group.color(),
                        row.group.mode(), row.group.enabled(), patterns);
                store.updateGroup(row.groupIndex, updated);
                refreshAll();
                reselect(model.firstGroupRow(updated));
                host.reapplyHighlights();
                host.log("Scope Highlighter: removed endpoint " + row.endpoint);
            }
        }
    }

    private void onMove(int delta) {
        OutlineRow row = selectedRow();
        if (row == null) {
            return;
        }
        ScopeGroup group = row.group;
        store.move(row.groupIndex, delta);
        refreshAll();
        reselect(model.firstGroupRow(group));
        host.reapplyHighlights();
    }

    private void refreshStatus() {
        String state = store.isAutoHighlightEnabled() ? "auto-highlight ON" : "auto-highlight OFF";
        String organizer = store.isAutoOrganizerEnabled() ? "organizer ON" : "organizer OFF";
        statusLabel.setText(" " + store.groupCount() + " group(s), "
                + store.endpointCount() + " endpoint(s) \u2014 " + state + ", " + organizer
                + " \u2014 " + host.loggerSummary()
                + " \u2014 top group wins when several match");
    }

    // ------------------------------------------------------------------
    // grid model
    // ------------------------------------------------------------------

    /** One grid row: a group header or a single endpoint of a group. */
    public static final class OutlineRow {
        public final ScopeGroup group;
        public final String endpoint;      // null on group header rows
        public final int groupIndex;
        public final int endpointIndex;    // -1 on group header rows

        OutlineRow(ScopeGroup group, String endpoint, int groupIndex, int endpointIndex) {
            this.group = group;
            this.endpoint = endpoint;
            this.groupIndex = groupIndex;
            this.endpointIndex = endpointIndex;
        }

        public boolean isGroupRow() {
            return endpoint == null;
        }
    }

    /** Flattens the groups into the outline grid: group row, then its endpoints. */
    public static final class OutlineTableModel extends AbstractTableModel {
        private static final String[] COLUMNS = {"#", "Endpoint / Group", "Color", "Match", "Sources", "Action"};
        private final GroupStore store;
        private List<OutlineRow> rows = new ArrayList<>();
        private List<String> sourceLabels = new ArrayList<>();

        public OutlineTableModel(GroupStore store) {
            this.store = store;
        }

        public void rebuild() {
            List<OutlineRow> rebuilt = new ArrayList<>();
            int groupNumber = 0;
            for (ScopeGroup group : store.groups()) {
                rebuilt.add(new OutlineRow(group, null, groupNumber, -1));
                int endpointNumber = 0;
                for (String pattern : group.patterns()) {
                    rebuilt.add(new OutlineRow(group, pattern, groupNumber, endpointNumber));
                    endpointNumber++;
                }
                groupNumber++;
            }
            rows = rebuilt;
            sourceLabels = new ArrayList<>();
            fireTableDataChanged();
        }

        /** Parallel to rows: which Burp sources hold data for each row. */
        public void setSourceLabels(List<String> labels) {
            sourceLabels = labels == null ? new ArrayList<>() : new ArrayList<>(labels);
            if (getRowCount() > 0) {
                fireTableRowsUpdated(0, getRowCount() - 1);
            }
        }

        public OutlineRow rowAt(int row) {
            return row >= 0 && row < rows.size() ? rows.get(row) : null;
        }

        OutlineRow lastGroupRow(ScopeGroup group) {
            OutlineRow found = null;
            for (OutlineRow row : rows) {
                if (row.isGroupRow() && row.group == group) {
                    found = row;
                }
            }
            return found;
        }

        OutlineRow firstGroupRow(ScopeGroup group) {
            for (OutlineRow row : rows) {
                if (row.isGroupRow() && row.group == group) {
                    return row;
                }
            }
            return null;
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return false;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            OutlineRow row = rows.get(rowIndex);
            switch (columnIndex) {
                case 0:
                    return row.isGroupRow() ? "G" + (row.groupIndex + 1) : String.valueOf(row.endpointIndex + 1);
                case 1:
                    return row.isGroupRow()
                            ? row.group.name() + "  (" + row.group.patternCount() + " endpoint"
                              + (row.group.patternCount() == 1 ? "" : "s") + ")"
                            : row.endpoint;
                case 2:
                    return row.group.color().displayName();
                case 3:
                    return row.group.mode().displayName();
                case 4:
                    return rowIndex < sourceLabels.size() ? sourceLabels.get(rowIndex) : "";
                default:
                    return "";
            }
        }
    }

    // ------------------------------------------------------------------
    // renderers
    // ------------------------------------------------------------------

    /** Bold group rows, indented endpoint rows. */
    static final class TextCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column) {
            JLabel label = (JLabel) super.getTableCellRendererComponent(
                    table, value, isSelected, hasFocus, row, column);
            OutlineTableModel model = (OutlineTableModel) table.getModel();
            OutlineRow outlineRow = model.rowAt(table.convertRowIndexToModel(row));
            if (outlineRow != null) {
                if (outlineRow.isGroupRow()) {
                    label.setFont(label.getFont().deriveFont(Font.BOLD));
                } else {
                    label.setFont(label.getFont().deriveFont(Font.PLAIN));
                    label.setText("    " + value);
                }
            }
            return label;
        }
    }

    /** Color swatch + color name. */
    static final class ColorCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column) {
            JLabel label = (JLabel) super.getTableCellRendererComponent(
                    table, value, isSelected, hasFocus, row, column);
            OutlineTableModel model = (OutlineTableModel) table.getModel();
            OutlineRow outlineRow = model.rowAt(table.convertRowIndexToModel(row));
            if (outlineRow != null) {
                label.setText("  " + value);
                label.setIcon(new GroupDialog.ColorIcon(GroupDialog.swatchFor(outlineRow.group.color())));
            }
            return label;
        }
    }
}
