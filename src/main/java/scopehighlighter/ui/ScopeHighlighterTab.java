package scopehighlighter.ui;

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
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * The "Scope Highlighter" suite tab.
 *
 * Excel-like grid: one bold group row per group ("Hiring Stage - 3 endpoints")
 * and below it one row per endpoint, columns: #, Endpoint / Group, Color,
 * Match, Action. The Action column has three buttons:
 *   copy endpoint | show all | notes to organizer
 *
 * Left-click behaviour:
 *   group row    -> just selects (double-click edits the group)
 *   endpoint row -> opens a Burp-style context menu (Send to Repeater /
 *                   Intruder / Organizer, Copy endpoint, Show all)
 */
public final class ScopeHighlighterTab extends JPanel {

    /** The three Action-column buttons. */
    public enum RowAction {
        COPY("copy endpoint"),
        SHOW_ALL("show all"),
        TO_ORGANIZER("notes to organizer");

        private final String label;

        RowAction(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public static final RowAction[] ACTIONS = RowAction.values();

    private final GroupStore store;
    private final Host host;
    private final OutlineTableModel model;
    private final JTable table;
    private final JLabel statusLabel = new JLabel(" ");

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
    }

    /** Rebuilds the grid and status line (also called by the store change listener). */
    public void refreshAll() {
        model.rebuild();
        refreshStatus();
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
    // table
    // ------------------------------------------------------------------

    private JScrollPane buildTable() {
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);
        table.setRowHeight(26);
        table.setRowMargin(2);
        table.getColumnModel().getColumn(0).setMaxWidth(50);
        table.getColumnModel().getColumn(1).setPreferredWidth(520);
        table.getColumnModel().getColumn(2).setPreferredWidth(110);
        table.getColumnModel().getColumn(3).setPreferredWidth(90);
        table.getColumnModel().getColumn(4).setPreferredWidth(360);

        table.getColumnModel().getColumn(1).setCellRenderer(new TextCellRenderer());
        table.getColumnModel().getColumn(2).setCellRenderer(new ColorCellRenderer());
        table.getColumnModel().getColumn(4).setCellRenderer(new ActionCellRenderer());

        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int viewRow = table.rowAtPoint(e.getPoint());
                int viewCol = table.columnAtPoint(e.getPoint());
                if (viewRow < 0) {
                    return;
                }
                if (viewCol == 4) {
                    onActionButton(viewRow, e.getX());
                    return;
                }
                if (e.getClickCount() == 2) {
                    onEdit();
                } else if (e.getClickCount() == 1) {
                    OutlineRow row = model.rowAt(table.convertRowIndexToModel(viewRow));
                    // group rows behave as before (just select); endpoint rows
                    // open the Burp-style context menu
                    if (row != null && !row.isGroupRow()) {
                        showContextMenu(row, e);
                    }
                }
            }
        });
        return new JScrollPane(table);
    }

    /**
     * The Action column renders 3 equal-width buttons; each button's exact
     * pixel range is recomputed here so the click routes to the right action.
     */
    private void onActionButton(int viewRow, int clickX) {
        Rectangle cell = table.getCellRect(viewRow, 4, false);
        if (cell == null || cell.width <= 0) {
            return;
        }
        int actionIndex = actionIndexForCellX(clickX - cell.x, cell.width);
        OutlineRow row = model.rowAt(table.convertRowIndexToModel(viewRow));
        if (row == null) {
            return;
        }
        switch (ACTIONS[actionIndex]) {
            case COPY:
                onCopyEndpoint(row);
                break;
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
     * Mirrors the renderer's GridLayout geometry (border 2px left/right,
     * 3px gaps, equal widths) so clicks map to buttons exactly.
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
        JMenuItem organizer = new JMenuItem("Send to Organizer");
        organizer.addActionListener(ev -> onNotesToOrganizer(row));
        JMenuItem copy = new JMenuItem("Copy endpoint");
        copy.addActionListener(ev -> onCopyEndpoint(row));
        JMenuItem showAll = new JMenuItem("Show all");
        showAll.addActionListener(ev -> openResults(row, Source.ALL));

        menu.add(repeater);
        menu.add(intruder);
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
                + " \u2014 every change is applied automatically"
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
        private static final String[] COLUMNS = {"#", "Endpoint / Group", "Color", "Match", "Action"};
        private final GroupStore store;
        private List<OutlineRow> rows = new ArrayList<>();

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
            fireTableDataChanged();
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

    /**
     * The Action column: 3 equal-width buttons (copy endpoint / show all /
     * notes to organizer). Renderer (non-interactive); clicks are routed by
     * {@link ScopeHighlighterTab#onActionButton} using the same geometry.
     */
    static final class ActionCellRenderer extends DefaultTableCellRenderer {
        private final JPanel panel = actionPanel();

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column) {
            return panel;
        }
    }

    static JPanel actionPanel() {
        JPanel panel = new JPanel(new GridLayout(1, ACTIONS.length, 3, 0));
        panel.setBorder(BorderFactory.createEmptyBorder(1, 2, 1, 2));
        for (RowAction action : ACTIONS) {
            JButton button = new JButton(action.label());
            button.setMargin(new Insets(1, 2, 1, 2));
            button.setFocusable(false);
            button.setFont(button.getFont().deriveFont(10f));
            panel.add(button);
        }
        return panel;
    }
}
