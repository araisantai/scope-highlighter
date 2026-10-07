package scopehighlighter.ui;

import scopehighlighter.log.MessageRecord;
import scopehighlighter.log.Source;
import scopehighlighter.model.ScopeGroup;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.ListSelectionModel;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import javax.swing.JFrame;

/**
 * The "show all" window: every recorded request of one endpoint (or one whole
 * group), with a source chooser (show all / repeater / intruder / target /
 * proxy history), Burp's own request/response message editors, an
 * Inspector-style details pane with the notes, and live refresh so upcoming
 * requests appear automatically.
 */
public final class ResultsFrame extends JFrame {

    private final Host host;
    private final ScopeGroup matcher;

    private final List<MessageRecord> snapshot = new ArrayList<>();
    private final List<MessageRecord> visible = new ArrayList<>();
    private final ResultsTableModel tableModel;
    private final JTable table;
    private final BurpMessageViewer viewer;
    private final JTextField searchField = new JTextField(18);
    private final JLabel summaryLabel = new JLabel(" ");
    private final JLabel countsLabel = new JLabel(" ");
    private final JToggleButton[] filterButtons = new JToggleButton[Source.values().length];
    private final Timer liveTimer;

    // inspector-style details pane
    private final DetailTableModel detailModel = new DetailTableModel();
    private final JTable detailTable = new JTable(detailModel);
    private final JTextArea notesArea = new JTextArea(3, 20);

    private Source currentSource = Source.ALL;

    public ResultsFrame(String title, ScopeGroup matcher, Host host) {
        super("Scope Highlighter \u2014 show all \u2014 " + title);
        this.matcher = matcher;
        this.host = host;
        this.tableModel = new ResultsTableModel(visible, GroupDialog.lightTint(matcher.color()));
        this.table = new JTable(tableModel);
        MessageViewer created = host.createMessageViewer();
        this.viewer = created instanceof BurpMessageViewer
                ? (BurpMessageViewer) created
                : BurpMessageViewer.textOnly();

        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout(6, 6));
        add(buildHeader(title), BorderLayout.NORTH);
        add(buildCenter(), BorderLayout.CENTER);
        add(countsLabel, BorderLayout.SOUTH);

        notesArea.setEditable(false);
        notesArea.setLineWrap(true);
        notesArea.setWrapStyleWord(true);
        notesArea.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        detailTable.setTableHeader(null);
        detailTable.setRowHeight(19);
        detailTable.getColumnModel().getColumn(0).setMaxWidth(120);

        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);
        table.setRowHeight(20);
        table.getColumnModel().getColumn(0).setMaxWidth(50);
        table.getColumnModel().getColumn(1).setMaxWidth(110);
        table.getColumnModel().getColumn(2).setMaxWidth(90);
        table.getColumnModel().getColumn(3).setMaxWidth(80);
        table.getColumnModel().getColumn(4).setPreferredWidth(500);
        table.getColumnModel().getColumn(5).setMaxWidth(70);
        table.getColumnModel().getColumn(6).setMaxWidth(90);

        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                showSelectedMessage();
            }
        });

        setSize(1250, 800);
        host.applyTheme(this);

        // live refresh: upcoming requests of this endpoint show up automatically
        liveTimer = new Timer(2000, e -> refresh(true));
        liveTimer.setRepeats(true);
        liveTimer.start();
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                liveTimer.stop();
            }
        });

        refresh(false);
    }

    // ------------------------------------------------------------------

    private JPanel buildHeader(String title) {
        JPanel header = new JPanel(new BorderLayout(6, 6));
        header.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));

        JLabel query = new JLabel(" " + title + "  ");
        query.setFont(query.getFont().deriveFont(Font.BOLD));

        JPanel center = new JPanel();
        center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        ButtonGroup group = new ButtonGroup();
        for (Source source : Source.values()) {
            JToggleButton button = new JToggleButton(source.buttonLabel());
            button.setToolTipText("Show requests recorded as \"" + source.buttonLabel() + "\" for this endpoint");
            button.addActionListener(e -> {
                currentSource = source;
                refresh(false);
            });
            filterButtons[source.ordinal()] = button;
            group.add(button);
            buttons.add(button);
            if (source == Source.ALL) {
                button.setSelected(true);
            }
        }
        summaryLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        buttons.setAlignmentX(Component.LEFT_ALIGNMENT);
        center.add(buttons);
        center.add(summaryLabel);

        JPanel search = new JPanel(new BorderLayout(4, 0));
        search.add(new JLabel("filter: "), BorderLayout.WEST);
        search.add(searchField, BorderLayout.CENTER);
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                applySearch();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                applySearch();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                applySearch();
            }
        });

        header.add(query, BorderLayout.WEST);
        header.add(center, BorderLayout.CENTER);
        header.add(search, BorderLayout.EAST);
        return header;
    }

    private Component buildCenter() {
        JScrollPane tableScroll = new JScrollPane(table);

        JTabbedPane messages = new JTabbedPane();
        messages.addTab("Request", viewer.requestComponent());
        messages.addTab("Response", viewer.responseComponent());

        JPanel inspector = new JPanel(new BorderLayout(4, 4));
        inspector.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        inspector.add(new JScrollPane(detailTable), BorderLayout.CENTER);
        JPanel notesPanel = new JPanel(new BorderLayout());
        notesPanel.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
        notesPanel.add(new JLabel("Notes"), BorderLayout.NORTH);
        notesPanel.add(new JScrollPane(notesArea), BorderLayout.CENTER);
        inspector.add(notesPanel, BorderLayout.SOUTH);

        JSplitPane lower = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, messages, inspector);
        lower.setResizeWeight(0.72);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tableScroll, lower);
        split.setResizeWeight(0.45);
        return split;
    }

    // ------------------------------------------------------------------

    /** Re-collects from the host; keepSelection preserves the selected message. */
    private void refresh(boolean keepSelection) {
        MessageRecord selected = null;
        int selectedRow = table.getSelectedRow();
        if (selectedRow >= 0 && selectedRow < visible.size()) {
            selected = visible.get(selectedRow);
        }

        snapshot.clear();
        snapshot.addAll(host.collect(currentSource, matcher));
        applySearch();

        if (keepSelection && selected != null) {
            for (int i = 0; i < visible.size(); i++) {
                if (sameRecord(visible.get(i), selected)) {
                    table.setRowSelectionInterval(i, i);
                    return;
                }
            }
        }
    }

    private static boolean sameRecord(MessageRecord a, MessageRecord b) {
        return a.timestamp() == b.timestamp() && a.key().equals(b.key());
    }

    private void applySearch() {
        String query = searchField.getText().trim().toLowerCase();
        visible.clear();
        for (MessageRecord record : snapshot) {
            if (query.isEmpty()
                    || record.url().toLowerCase().contains(query)
                    || record.method().toLowerCase().contains(query)
                    || record.source().toLowerCase().contains(query)) {
                visible.add(record);
            }
        }
        tableModel.fireTableDataChanged();
        summaryLabel.setText(" " + currentSource.buttonLabel() + ": " + visible.size()
                + (query.isEmpty() ? "" : " (filtered from " + snapshot.size() + ")")
                + " request(s) \u2014 live: upcoming requests appear automatically");
        updateCounts();
        if (!visible.isEmpty() && table.getSelectedRow() < 0) {
            table.setRowSelectionInterval(0, 0);
        } else if (visible.isEmpty()) {
            detailModel.setRecord(null);
            notesArea.setText("");
            String label = currentSource == Source.ALL ? null : currentSource.recordLabel();
            int nonMatching = 0;
            List<String> examples = new ArrayList<>();
            for (MessageRecord record : host.allKnownRecords()) {
                if (label == null || record.isFrom(label)) {
                    if (matcher.match(record.url()).isEmpty()) {
                        nonMatching++;
                        if (examples.size() < 2) {
                            examples.add(record.method() + " " + record.url());
                        }
                    }
                }
            }
            StringBuilder msg = new StringBuilder(summaryLabel.getText())
                    .append("  \u2014  no \"").append(currentSource.buttonLabel())
                    .append("\" request matches this endpoint");
            if (nonMatching > 0) {
                msg.append(" (").append(nonMatching).append(" exist but don't match \u2014 e.g. ")
                        .append(String.join(" | ", examples))
                        .append(" \u2014 check the pattern/match mode)");
            } else {
                msg.append(" (nothing recorded in this source yet \u2014 recording starts when the extension loads)");
            }
            summaryLabel.setText(msg.toString());
        }
    }

    private void updateCounts() {
        StringBuilder sb = new StringBuilder(" Recorded \u2014 ");
        for (Source source : new Source[]{Source.REPEATER, Source.INTRUDER, Source.TARGET, Source.HISTORY}) {
            sb.append(source.buttonLabel()).append(": ").append(host.collect(source, matcher).size()).append("   ");
        }
        countsLabel.setText(sb.toString());
    }

    private void showSelectedMessage() {
        int viewRow = table.getSelectedRow();
        if (viewRow < 0 || viewRow >= visible.size()) {
            return;
        }
        MessageRecord record = visible.get(viewRow);
        viewer.showMessage(record);
        viewer.setSearchExpression(searchField.getText().trim());
        detailModel.setRecord(record);
        notesArea.setText(record.notes().isEmpty() ? "(no notes)" : record.notes());
        notesArea.setCaretPosition(0);
    }

    // ------------------------------------------------------------------

    /** Table model over the visible records. Row background = light group tint. */
    public static final class ResultsTableModel extends AbstractTableModel {
        private static final String[] COLUMNS = {"#", "Time", "Source", "Method", "URL", "Status", "Length"};
        private static final ThreadLocal<SimpleDateFormat> TIME =
                ThreadLocal.withInitial(() -> new SimpleDateFormat("HH:mm:ss.SSS"));

        private final List<MessageRecord> records;
        private final Color tint;

        public ResultsTableModel(List<MessageRecord> records, Color tint) {
            this.records = records;
            this.tint = tint;
        }

        public Color tint() {
            return tint;
        }

        @Override
        public int getRowCount() {
            return records.size();
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
        public Object getValueAt(int rowIndex, int columnIndex) {
            MessageRecord record = records.get(rowIndex);
            switch (columnIndex) {
                case 0:
                    return rowIndex + 1;
                case 1:
                    return record.timestamp() <= 0 ? "-" : TIME.get().format(new Date(record.timestamp()));
                case 2:
                    return record.source();
                case 3:
                    return record.method();
                case 4:
                    return record.url();
                case 5:
                    return record.status();
                case 6:
                    return record.responseLength();
                default:
                    return "";
            }
        }
    }

    /**
     * Inspector-style details: summary fields, then request headers and query
     * parameters of the selected message.
     */
    public static final class DetailTableModel extends AbstractTableModel {
        private final List<String[]> rows = new ArrayList<>();

        public void setRecord(MessageRecord record) {
            rows.clear();
            if (record != null) {
                rows.add(new String[]{"Method", record.method()});
                rows.add(new String[]{"URL", record.url()});
                rows.add(new String[]{"Status", String.valueOf(record.status())});
                rows.add(new String[]{"Source", record.source()});
                rows.add(new String[]{"Time", record.timestamp() <= 0 ? "-"
                        : new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date(record.timestamp()))});
                rows.add(new String[]{"Request length", String.valueOf(record.requestLength())});
                rows.add(new String[]{"Response length", String.valueOf(record.responseLength())});
                parseQueryParams(record.url(), rows);
                parseHeaders(record.requestRaw(), rows);
            }
            fireTableDataChanged();
        }

        private static void parseQueryParams(String url, List<String[]> out) {
            int q = url.indexOf('?');
            if (q < 0 || q == url.length() - 1) {
                return;
            }
            for (String pair : url.substring(q + 1).split("&")) {
                int eq = pair.indexOf('=');
                String name = eq < 0 ? pair : pair.substring(0, eq);
                String value = eq < 0 ? "" : pair.substring(eq + 1);
                if (!name.isEmpty()) {
                    out.add(new String[]{"param " + name, value});
                }
            }
        }

        private static void parseHeaders(String requestRaw, List<String[]> out) {
            String[] lines = requestRaw.split("\r?\n");
            for (int i = 1; i < lines.length; i++) {
                String line = lines[i];
                if (line.isEmpty()) {
                    break;
                }
                int colon = line.indexOf(':');
                if (colon > 0) {
                    out.add(new String[]{line.substring(0, colon).trim(), line.substring(colon + 1).trim()});
                }
            }
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return 2;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            return rows.get(rowIndex)[columnIndex];
        }
    }
}
