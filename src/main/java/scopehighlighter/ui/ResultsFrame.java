package scopehighlighter.ui;

import scopehighlighter.log.MessageRecord;
import scopehighlighter.log.Source;
import scopehighlighter.model.ScopeGroup;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.BoxLayout;
import javax.swing.JFrame;
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
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * The "reveal" window: lists every recorded request of one endpoint (or one
 * whole group) from Repeater / Intruder / History / Target, with raw request
 * and response panes - so the request never needs to be hunted down again.
 */
public final class ResultsFrame extends JFrame {

    private final Host host;
    private final ScopeGroup matcher;

    private final List<MessageRecord> snapshot = new ArrayList<>();
    private final List<MessageRecord> visible = new ArrayList<>();
    private final ResultsTableModel tableModel;
    private final JTable table;
    private final JTextArea requestArea = new JTextArea();
    private final JTextArea responseArea = new JTextArea();
    private final JTextField searchField = new JTextField(18);
    private final JLabel summaryLabel = new JLabel(" ");
    private final JLabel countsLabel = new JLabel(" ");

    private Source currentSource = Source.ALL;

    public ResultsFrame(String title, ScopeGroup matcher, Host host) {
        super("Scope Highlighter \u2014 " + title);
        this.matcher = matcher;
        this.host = host;
        this.tableModel = new ResultsTableModel(visible, GroupDialog.lightTint(matcher.color()));
        this.table = new JTable(tableModel);

        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout(6, 6));
        add(buildHeader(title), BorderLayout.NORTH);
        add(buildCenter(), BorderLayout.CENTER);
        add(countsLabel, BorderLayout.SOUTH);

        requestArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        responseArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        requestArea.setEditable(false);
        responseArea.setEditable(false);

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

        setSize(1100, 750);
        host.applyTheme(this);
        load(Source.ALL);
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
            button.addActionListener(e -> load(source));
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
        messages.addTab("Request", new JScrollPane(requestArea));
        messages.addTab("Response", new JScrollPane(responseArea));

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tableScroll, messages);
        split.setResizeWeight(0.55);
        return split;
    }

    // ------------------------------------------------------------------

    private void load(Source source) {
        currentSource = source;
        snapshot.clear();
        snapshot.addAll(host.collect(source, matcher));
        applySearch();
        updateCounts();
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
                + (query.isEmpty() ? "" : " (filtered from " + snapshot.size() + ")") + " request(s)");
    }

    private void updateCounts() {
        StringBuilder sb = new StringBuilder(" Recorded \u2014 ");
        for (Source source : new Source[]{Source.REPEATER, Source.INTRUDER, Source.HISTORY, Source.TARGET}) {
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
        requestArea.setText(record.requestRaw());
        responseArea.setText(record.responseRaw().isEmpty() ? "(no response recorded)" : record.responseRaw());
        requestArea.setCaretPosition(0);
        responseArea.setCaretPosition(0);
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
}
