package scopemanager.ui;

import scopemanager.log.MessageRecord;
import scopemanager.log.Source;
import scopemanager.model.GroupStore;
import scopemanager.model.ScopeGroup;

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
import javax.swing.SwingWorker;
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
import java.util.ArrayList;
import java.util.List;

/**
 * The "Scope Manager" suite tab.
 *
 * Excel-like grid: one bold group row per group and below it one row per
 * endpoint, columns: #, Endpoint / Group, Color, Match, Sources, Total request, With Notes.
 *
 * Mouse behaviour (Burp-like):
 *   left click           -> select row
 *   double-click group   -> edit the scope group
 *   double-click endpoint-> show all for that endpoint
 *   right-click endpoint -> context menu (Send to Repeater / Intruder /
 *                           Organizer, Copy endpoint, Show all)
 */
public final class ScopeManagerTab extends JPanel {

    private final GroupStore store;
    private final Host host;
    private final OutlineTableModel model;
    private final JTable table;
    private final JLabel statusLabel = new JLabel(" ");
    private final java.util.concurrent.atomic.AtomicBoolean recomputeRunning =
            new java.util.concurrent.atomic.AtomicBoolean();

    public ScopeManagerTab(GroupStore store, Host host) {
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
        recomputeSources();
        refreshStatus();
    }

    /** Fills the "Sources" column: which Burp sources hold data per row. */
    private void recomputeSources() {
        if (!recomputeRunning.compareAndSet(false, true)) {
            return;
        }
        List<ScopeGroup> matchers = new ArrayList<>();
        for (int i = 0; i < model.getRowCount(); i++) {
            matchers.add(matcherFor(model.rowAt(i)));
        }
        Thread worker = new Thread(() -> {
            try {
                List<MessageRecord> records = host.allKnownRecords();
                List<String> labels = new ArrayList<>();
                List<Integer> totalCounts = new ArrayList<>();
                List<Integer> noteCounts = new ArrayList<>();
                for (ScopeGroup matcher : matchers) {
                    labels.add(describeSources(matcher, records));
                    RequestCounts counts = countRequests(matcher, records);
                    totalCounts.add(counts.total());
                    noteCounts.add(counts.noted());
                }
                SwingUtilities.invokeLater(() -> {
                    if (model.getRowCount() == matchers.size()) {
                        model.setSourceLabels(labels);
                        model.setTotalCounts(totalCounts);
                        model.setNoteCounts(noteCounts);
                    }
                });
            } catch (Exception e) {
                host.log("Scope Manager: sources recompute failed: " + e);
            } finally {
                recomputeRunning.set(false);
            }
        }, "scope-manager-metadata");
        worker.setDaemon(true);
        worker.start();
    }

    private record RequestCounts(int total, int noted) {}

    private static RequestCounts countRequests(ScopeGroup matcher, List<MessageRecord> records) {
        java.util.Set<String> allKeys = new java.util.HashSet<>();
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (MessageRecord record : records) {
            if (matcher.match(record.url()).isPresent()) {
                allKeys.add(record.key());
                if (!record.notes().isBlank()) {
                    keys.add(record.key());
                }
            }
        }
        return new RequestCounts(allKeys.size(), keys.size());
    }

    public static int countTotalRequests(ScopeGroup matcher, List<MessageRecord> records) {
        return countRequests(matcher, records).total();
    }

    public static int countNotedRequests(ScopeGroup matcher, List<MessageRecord> records) {
        return countRequests(matcher, records).noted();
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
        JButton refresh = new JButton("Refresh Notes");
        JButton pushNotes = new JButton("Push Organizer");

        add.setToolTipText("Create a new scope group (title, color, endpoint list pasted from Excel)");
        edit.setToolTipText("Edit the group of the selected row (title, color, endpoint list)");
        delete.setToolTipText("Delete the selected group, or remove the selected endpoint from its group");
        up.setToolTipText("Move selected group up (higher match priority)");
        down.setToolTipText("Move selected group down (lower match priority)");
        refresh.setToolTipText("Reload Sources, Total request, and With Notes counts from Burp");
        pushNotes.setToolTipText("Send noted requests in all scope groups to Organizer Inbox");

        JCheckBox autoHighlight = new JCheckBox("Auto-highlight new traffic", store.isAutoHighlightEnabled());
        autoHighlight.setToolTipText("Every request flowing through Burp gets highlighted automatically");

        add.addActionListener(e -> onAdd());
        edit.addActionListener(e -> onEdit());
        delete.addActionListener(e -> onDelete());
        up.addActionListener(e -> onMove(-1));
        down.addActionListener(e -> onMove(1));
        refresh.addActionListener(e -> recomputeSources());
        pushNotes.addActionListener(e -> pushAllNotes(pushNotes));
        autoHighlight.addActionListener(e -> {
            store.setAutoHighlightEnabled(autoHighlight.isSelected());
            refreshAll();
        });

        toolbar.add(add);
        toolbar.add(edit);
        toolbar.add(delete);
        toolbar.add(up);
        toolbar.add(down);
        toolbar.add(refresh);
        toolbar.add(pushNotes);
        toolbar.add(new JLabel("   "));
        toolbar.add(autoHighlight);
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
        table.getColumnModel().getColumn(1).setPreferredWidth(420);
        table.getColumnModel().getColumn(2).setPreferredWidth(110);
        table.getColumnModel().getColumn(3).setPreferredWidth(90);
        table.getColumnModel().getColumn(4).setPreferredWidth(180);
        table.getColumnModel().getColumn(5).setPreferredWidth(100);
        table.getColumnModel().getColumn(6).setPreferredWidth(90);

        table.getColumnModel().getColumn(1).setCellRenderer(new TextCellRenderer());
        table.getColumnModel().getColumn(2).setCellRenderer(new ColorCellRenderer());

        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int viewRow = table.rowAtPoint(e.getPoint());
                if (viewRow < 0 || e.getButton() != MouseEvent.BUTTON1) {
                    return;
                }
                OutlineRow row = model.rowAt(table.convertRowIndexToModel(viewRow));
                if (row == null) {
                    return;
                }
                if (e.getClickCount() == 2) {
                    if (row.isGroupRow()) {
                        onEdit();                      // double-click group -> edit scope group
                    } else {
                        openResults(row, Source.ALL);  // double-click endpoint -> show all
                    }
                }
            }

        });

        // Right-click opens the endpoint menu immediately.
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
                if (!isSecondaryPopup(e)) {
                    return;
                }
                int viewRow = table.rowAtPoint(e.getPoint());
                if (viewRow < 0) {
                    return;
                }
                OutlineRow row = model.rowAt(table.convertRowIndexToModel(viewRow));
                if (row != null && !row.isGroupRow()) {
                    table.setRowSelectionInterval(viewRow, viewRow);
                    showContextMenu(row, e);
                }
            }
        });
        return new JScrollPane(table);
    }

    /** A normal left click must never open the endpoint menu, even if a
     *  platform reports it as a popup trigger. Trackpad secondary taps use
     *  button 3 in Java, just like a mouse right click. */
    public static boolean isSecondaryPopup(MouseEvent event) {
        return event.getButton() == MouseEvent.BUTTON3 && event.isPopupTrigger();
    }

    // ------------------------------------------------------------------
    // request actions
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
        pushNotes(matcherFor(row), null);
    }

    private void pushAllNotes(JButton button) {
        pushNotes(null, button);
    }

    /** Manual push runs off the Swing thread so large Burp histories do not freeze the tab. */
    private void pushNotes(ScopeGroup matcher, JButton button) {
        if (button != null) {
            button.setEnabled(false);
        }
        statusLabel.setText(" Sending noted requests to Organizer Inbox...");
        new SwingWorker<Integer, Void>() {
            @Override
            protected Integer doInBackground() {
                return matcher == null ? host.syncAllToOrganizer() : host.syncToOrganizer(matcher);
            }

            @Override
            protected void done() {
                if (button != null) {
                    button.setEnabled(true);
                }
                try {
                    int sent = get();
                    statusLabel.setText(" " + sent + " new noted request(s) sent to Organizer Inbox.");
                } catch (Exception error) {
                    statusLabel.setText(" Organizer push failed; see extension output.");
                    host.log("Scope Manager: organizer push failed: " + error);
                }
            }
        }.execute();
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
            host.log("Scope Manager: added group \"" + group.name() + "\" ("
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
            host.log("Scope Manager: updated group \"" + updated.name() + "\"");
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
                            + row.group.patternCount() + " endpoint(s)?", "Scope Manager",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
            if (confirm == JOptionPane.OK_OPTION) {
                store.removeGroup(row.groupIndex);
                refreshAll();
                host.reapplyHighlights();
                host.log("Scope Manager: deleted group \"" + row.group.name() + "\"");
            }
        } else {
            int confirm = JOptionPane.showConfirmDialog(this,
                    "Remove endpoint \"" + row.endpoint + "\" from \"" + row.group.name() + "\"?",
                    "Scope Manager", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
            if (confirm == JOptionPane.OK_OPTION) {
                List<String> patterns = new ArrayList<>(row.group.patterns());
                patterns.remove(row.endpointIndex);
                ScopeGroup updated = new ScopeGroup(row.group.name(), row.group.color(),
                        row.group.mode(), row.group.enabled(), patterns);
                store.updateGroup(row.groupIndex, updated);
                refreshAll();
                reselect(model.firstGroupRow(updated));
                host.reapplyHighlights();
                host.log("Scope Manager: removed endpoint " + row.endpoint);
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
        statusLabel.setText(" " + store.groupCount() + " group(s), "
                + store.endpointCount() + " endpoint(s) \u2014 " + state
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
        private static final String[] COLUMNS = {"#", "Endpoint / Group", "Color", "Match", "Sources", "Total request", "With Notes"};
        private final GroupStore store;
        private List<OutlineRow> rows = new ArrayList<>();
        private List<String> sourceLabels = new ArrayList<>();
        private List<Integer> totalCounts = new ArrayList<>();
        private List<Integer> noteCounts = new ArrayList<>();

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
            totalCounts = new ArrayList<>();
            noteCounts = new ArrayList<>();
            fireTableDataChanged();
        }

        public void setTotalCounts(List<Integer> counts) {
            totalCounts = counts == null ? new ArrayList<>() : new ArrayList<>(counts);
            if (getRowCount() > 0) {
                fireTableRowsUpdated(0, getRowCount() - 1);
            }
        }

        public void setNoteCounts(List<Integer> counts) {
            noteCounts = counts == null ? new ArrayList<>() : new ArrayList<>(counts);
            if (getRowCount() > 0) {
                fireTableRowsUpdated(0, getRowCount() - 1);
            }
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
                case 5:
                    return rowIndex < totalCounts.size() ? totalCounts.get(rowIndex) : 0;
                case 6:
                    return rowIndex < noteCounts.size() ? noteCounts.get(rowIndex) : 0;
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
