package scopehighlighter.ui;

import scopehighlighter.model.GroupStore;
import scopehighlighter.model.ScopeGroup;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
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
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * The "Scope Highlighter" suite tab: table of groups + add/edit/delete,
 * auto-highlight toggle, and backfill for existing proxy history.
 */
public final class ScopeHighlighterTab extends JPanel {

    /** Everything the tab needs from the Burp host - keeps the tab headless-testable. */
    public interface Host {
        Component applyTheme(Component component);

        void log(String message);

        void highlightHistory(Runnable onFinished);
    }

    private final GroupStore store;
    private final Host host;
    private final GroupTableModel tableModel;
    private final JTable table;
    private final JLabel statusLabel = new JLabel(" ");

    public ScopeHighlighterTab(GroupStore store, Host host) {
        super(new BorderLayout(8, 8));
        this.store = store;
        this.host = host;
        this.tableModel = new GroupTableModel(store);
        this.table = new JTable(tableModel);

        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        add(buildToolbar(), BorderLayout.NORTH);
        add(buildTable(), BorderLayout.CENTER);
        add(statusLabel, BorderLayout.SOUTH);

        refreshStatus();
        host.applyTheme(this);
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
        JButton backfill = new JButton("Highlight existing history");

        add.setToolTipText("Create a new scope group (title, color, endpoint list)");
        edit.setToolTipText("Edit the selected group");
        delete.setToolTipText("Delete the selected group");
        up.setToolTipText("Move selected group up (higher match priority)");
        down.setToolTipText("Move selected group down (lower match priority)");
        backfill.setToolTipText("Apply the colors to requests already sitting in the Proxy history");

        up.putClientProperty("JButton.buttonType", "toolBarButton");
        down.putClientProperty("JButton.buttonType", "toolBarButton");

        JCheckBox autoHighlight = new JCheckBox("Auto-highlight new traffic", store.isAutoHighlightEnabled());
        JCheckBox writeNotes = new JCheckBox("Write group name to Notes", store.isWriteNotesEnabled());
        autoHighlight.setToolTipText("When enabled, every request flowing through Burp gets highlighted automatically");
        writeNotes.setToolTipText("Also write the group title into the Notes column (only when the note is empty)");

        add.addActionListener(e -> onAdd());
        edit.addActionListener(e -> onEdit());
        delete.addActionListener(e -> onDelete());
        up.addActionListener(e -> onMove(-1));
        down.addActionListener(e -> onMove(1));
        backfill.addActionListener(e -> onBackfill());
        autoHighlight.addActionListener(e -> {
            store.setAutoHighlightEnabled(autoHighlight.isSelected());
            refreshStatus();
        });
        writeNotes.addActionListener(e -> store.setWriteNotesEnabled(writeNotes.isSelected()));

        toolbar.add(add);
        toolbar.add(edit);
        toolbar.add(delete);
        toolbar.add(up);
        toolbar.add(down);
        toolbar.add(new JLabel("   "));
        toolbar.add(autoHighlight);
        toolbar.add(writeNotes);
        toolbar.add(new JLabel("   "));
        toolbar.add(backfill);
        return toolbar;
    }

    // ------------------------------------------------------------------
    // table
    // ------------------------------------------------------------------

    private JScrollPane buildTable() {
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);
        table.setRowHeight(22);
        table.getColumnModel().getColumn(0).setMaxWidth(60);
        table.getColumnModel().getColumn(2).setMinWidth(120);
        table.getColumnModel().getColumn(3).setMaxWidth(110);
        table.getColumnModel().getColumn(4).setMaxWidth(90);

        table.getColumnModel().getColumn(2).setCellRenderer(new ColorCellRenderer());

        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    onEdit();
                }
            }
        });
        return new JScrollPane(table);
    }

    private int selectedRow() {
        int viewRow = table.getSelectedRow();
        return viewRow < 0 ? -1 : table.convertRowIndexToModel(viewRow);
    }

    // ------------------------------------------------------------------
    // actions
    // ------------------------------------------------------------------

    private void onAdd() {
        Window owner = SwingUtilities.getWindowAncestor(this);
        ScopeGroup group = GroupDialog.ask(owner, null);
        if (group != null) {
            store.addGroup(group);
            tableModel.fireTableDataChanged();
            refreshStatus();
            host.log("Scope Highlighter: added group \"" + group.name() + "\" ("
                    + group.patternCount() + " endpoints, " + group.color().displayName() + ")");
        }
    }

    private void onEdit() {
        int index = selectedRow();
        if (index < 0) {
            return;
        }
        ScopeGroup existing = store.groups().get(index);
        Window owner = SwingUtilities.getWindowAncestor(this);
        ScopeGroup updated = GroupDialog.ask(owner, existing);
        if (updated != null) {
            updated.setEnabled(existing.enabled());
            store.updateGroup(index, updated);
            tableModel.fireTableDataChanged();
            table.setRowSelectionInterval(index, index);
            refreshStatus();
            host.log("Scope Highlighter: updated group \"" + updated.name() + "\"");
        }
    }

    private void onDelete() {
        int index = selectedRow();
        if (index < 0) {
            return;
        }
        ScopeGroup group = store.groups().get(index);
        int confirm = JOptionPane.showConfirmDialog(this,
                "Delete group \"" + group.name() + "\"?", "Scope Highlighter",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (confirm == JOptionPane.OK_OPTION) {
            store.removeGroup(index);
            tableModel.fireTableDataChanged();
            refreshStatus();
            host.log("Scope Highlighter: deleted group \"" + group.name() + "\"");
        }
    }

    private void onMove(int delta) {
        int index = selectedRow();
        if (index < 0) {
            return;
        }
        int target = index + delta;
        store.move(index, delta);
        if (target >= 0 && target < tableModel.getRowCount()) {
            tableModel.fireTableDataChanged();
            table.setRowSelectionInterval(target, target);
        }
    }

    private void onBackfill() {
        statusLabel.setText("Highlighting existing proxy history\u2026");
        host.highlightHistory(() -> {
            refreshStatus();
        });
    }

    private void refreshStatus() {
        String state = store.isAutoHighlightEnabled() ? "auto-highlight ON" : "auto-highlight OFF";
        statusLabel.setText(" " + store.groupCount() + " group(s), "
                + store.endpointCount() + " endpoint(s) \u2014 " + state
                + " \u2014 top group wins when several match");
    }

    // ------------------------------------------------------------------
    // table model
    // ------------------------------------------------------------------

    static final class GroupTableModel extends AbstractTableModel {
        private static final String[] COLUMNS = {"On", "Group title", "Color", "Match", "Endpoints"};
        private final GroupStore store;

        GroupTableModel(GroupStore store) {
            this.store = store;
        }

        @Override
        public int getRowCount() {
            return store.groupCount();
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
        public Class<?> getColumnClass(int columnIndex) {
            switch (columnIndex) {
                case 0:
                    return Boolean.class;
                case 4:
                    return Integer.class;
                default:
                    return String.class;
            }
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return columnIndex == 0;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            ScopeGroup group = store.groups().get(rowIndex);
            switch (columnIndex) {
                case 0:
                    return group.enabled();
                case 1:
                    return group.name();
                case 2:
                    return group.color().displayName();
                case 3:
                    return group.mode().displayName();
                case 4:
                    return group.patternCount();
                default:
                    return "";
            }
        }

        @Override
        public void setValueAt(Object aValue, int rowIndex, int columnIndex) {
            if (columnIndex == 0) {
                ScopeGroup group = store.groups().get(rowIndex);
                group.setEnabled(Boolean.TRUE.equals(aValue));
                fireTableCellUpdated(rowIndex, columnIndex);
            }
        }

        ScopeGroup groupAt(int row) {
            return store.groups().get(row);
        }
    }

    static final class ColorCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column) {
            JLabel label = (JLabel) super.getTableCellRendererComponent(
                    table, value, isSelected, hasFocus, row, column);
            GroupTableModel model = (GroupTableModel) table.getModel();
            int modelRow = table.convertRowIndexToModel(row);
            if (modelRow >= 0 && modelRow < model.getRowCount()) {
                ScopeGroup group = model.groupAt(modelRow);
                label.setText("  " + group.color().displayName());
                label.setIcon(new GroupDialog.ColorIcon(GroupDialog.swatchFor(group.color())));
                if (!isSelected) {
                    label.setForeground(table.getForeground());
                    label.setBackground(table.getBackground());
                }
            }
            return label;
        }
    }
}
