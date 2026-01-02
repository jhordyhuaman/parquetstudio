/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.jhordyhuaman.parquetstudio.ui;

import com.github.jhordyhuaman.parquetstudio.model.SchemaValidationResult;
import com.github.jhordyhuaman.parquetstudio.model.SchemaValidationResult.ColumnValidation;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBScrollPane;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.List;

/**
 * Dialog to display schema validation results.
 */
public class SchemaValidationDialog extends DialogWrapper {

    private final SchemaValidationResult result;
    private final String schemaFileName;

    public SchemaValidationDialog(SchemaValidationResult result, String schemaFileName) {
        super(true);
        this.result = result;
        this.schemaFileName = schemaFileName;
        setTitle("Schema Validation Results");
        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel mainPanel = new JPanel(new BorderLayout(10, 10));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        mainPanel.setPreferredSize(new Dimension(700, 500));

        // Summary panel
        JPanel summaryPanel = createSummaryPanel();
        mainPanel.add(summaryPanel, BorderLayout.NORTH);

        // Tabbed pane for different result categories
        JTabbedPane tabbedPane = new JTabbedPane();

        // Valid columns tab
        if (!result.getValidColumns().isEmpty()) {
            JPanel validPanel = createColumnTable(result.getValidColumns(), true);
            tabbedPane.addTab("✓ Valid (" + result.getValidColumns().size() + ")", validPanel);
        }

        // Type mismatch tab
        if (!result.getTypeMismatchColumns().isEmpty()) {
            JPanel mismatchPanel = createColumnTable(result.getTypeMismatchColumns(), false);
            tabbedPane.addTab("⚠ Type Mismatch (" + result.getTypeMismatchColumns().size() + ")", mismatchPanel);
        }

        // Missing columns tab
        if (!result.getMissingInParquet().isEmpty()) {
            JPanel missingPanel = createListPanel(result.getMissingInParquet(), "Columns defined in schema but missing in Parquet:");
            tabbedPane.addTab("✗ Missing (" + result.getMissingInParquet().size() + ")", missingPanel);
        }

        // Extra columns tab
        if (!result.getExtraInParquet().isEmpty()) {
            JPanel extraPanel = createListPanel(result.getExtraInParquet(), "Columns in Parquet but not defined in schema:");
            tabbedPane.addTab("⊕ Extra (" + result.getExtraInParquet().size() + ")", extraPanel);
        }

        mainPanel.add(tabbedPane, BorderLayout.CENTER);

        return mainPanel;
    }

    private JPanel createSummaryPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createTitledBorder("Validation Summary"),
            BorderFactory.createEmptyBorder(5, 5, 5, 5)
        ));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(2, 5, 2, 5);

        // Schema file
        gbc.gridx = 0; gbc.gridy = 0;
        panel.add(new JLabel("Schema File:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        panel.add(new JLabel(schemaFileName), gbc);

        // Status icon and message
        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0;
        panel.add(new JLabel("Status:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;

        JLabel statusLabel = new JLabel();
        if (result.isFullyValid()) {
            statusLabel.setText("✓ All columns validated successfully!");
            statusLabel.setForeground(JBColor.GREEN.darker());
        } else if (result.getTypeMismatchColumns().isEmpty() && result.getMissingInParquet().isEmpty()) {
            statusLabel.setText("✓ Valid (with " + result.getExtraInParquet().size() + " extra columns in Parquet)");
            statusLabel.setForeground(JBColor.ORANGE);
        } else {
            statusLabel.setText("⚠ Validation issues found");
            statusLabel.setForeground(JBColor.RED);
        }
        panel.add(statusLabel, gbc);

        // Statistics
        gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0;
        panel.add(new JLabel("Statistics:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        String stats = String.format("%d valid, %d mismatches, %d missing, %d extra",
            result.getValidColumns().size(),
            result.getTypeMismatchColumns().size(),
            result.getMissingInParquet().size(),
            result.getExtraInParquet().size()
        );
        panel.add(new JLabel(stats), gbc);

        return panel;
    }

    private JPanel createColumnTable(List<ColumnValidation> columns, boolean isValid) {
        JPanel panel = new JPanel(new BorderLayout());

        String[] columnNames = {"Column Name", "Expected Type", "Actual Type", "Status"};
        DefaultTableModel model = new DefaultTableModel(columnNames, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };

        for (ColumnValidation col : columns) {
            model.addRow(new Object[]{
                col.getColumnName(),
                col.getExpectedType(),
                col.getActualType(),
                isValid ? "✓ Valid" : "⚠ Mismatch"
            });
        }

        JTable table = new JTable(model);
        table.setRowHeight(25);
        table.getColumnModel().getColumn(3).setCellRenderer(new StatusCellRenderer());

        JBScrollPane scrollPane = new JBScrollPane(table);
        panel.add(scrollPane, BorderLayout.CENTER);

        return panel;
    }

    private JPanel createListPanel(List<String> items, String description) {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        JLabel descLabel = new JLabel(description);
        panel.add(descLabel, BorderLayout.NORTH);

        DefaultListModel<String> listModel = new DefaultListModel<>();
        for (String item : items) {
            listModel.addElement(item);
        }

        JList<String> list = new JList<>(listModel);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        JBScrollPane scrollPane = new JBScrollPane(list);
        panel.add(scrollPane, BorderLayout.CENTER);

        return panel;
    }

    /**
     * Custom cell renderer for status column.
     */
    private static class StatusCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                boolean isSelected, boolean hasFocus, int row, int column) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);

            if (value != null) {
                String status = value.toString();
                if (status.contains("Valid")) {
                    setForeground(JBColor.GREEN.darker());
                } else if (status.contains("Mismatch")) {
                    setForeground(JBColor.RED);
                } else {
                    setForeground(JBColor.ORANGE);
                }
            }

            return c;
        }
    }
}

