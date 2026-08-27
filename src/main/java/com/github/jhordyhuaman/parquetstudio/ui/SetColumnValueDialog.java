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

import com.github.jhordyhuaman.parquetstudio.model.ParquetTableModel;
import com.intellij.openapi.ui.DialogWrapper;
import java.awt.*;
import javax.swing.*;

/**
 * Dialog for bulk-setting the value of every row (or only empty/NULL rows) in one column.
 */
public class SetColumnValueDialog extends DialogWrapper {
  private final ParquetTableModel tableModel;
  private final int columnIndex;
  private final String columnName;
  private final String columnType;

  private JTextField valueField;
  private JCheckBox onlyEmptyCheckBox;

  public SetColumnValueDialog(Component parent, ParquetTableModel tableModel, int columnIndex) {
    super(parent, true);
    this.tableModel = tableModel;
    this.columnIndex = columnIndex;
    this.columnName = tableModel.getColumnNames().get(columnIndex);
    this.columnType = tableModel.getColumnTypes().get(columnIndex);
    setTitle("Set Column Value");
    init();
  }

  @Override
  protected JComponent createCenterPanel() {
    JPanel panel = new JPanel(new GridBagLayout());
    GridBagConstraints gbc = new GridBagConstraints();
    gbc.insets = new Insets(5, 5, 5, 5);
    gbc.anchor = GridBagConstraints.WEST;

    gbc.gridx = 0;
    gbc.gridy = 0;
    panel.add(new JLabel("Column:"), gbc);
    gbc.gridx = 1;
    panel.add(new JLabel(columnName + " (" + columnType + ")"), gbc);

    gbc.gridx = 0;
    gbc.gridy = 1;
    panel.add(new JLabel("Value:"), gbc);

    gbc.gridx = 1;
    gbc.fill = GridBagConstraints.HORIZONTAL;
    gbc.weightx = 1.0;
    valueField = new JTextField(20);
    panel.add(valueField, gbc);

    gbc.gridx = 0;
    gbc.gridy = 2;
    gbc.gridwidth = 2;
    gbc.fill = GridBagConstraints.NONE;
    gbc.weightx = 0;
    onlyEmptyCheckBox = new JCheckBox("Only empty/NULL cells");
    panel.add(onlyEmptyCheckBox, gbc);

    panel.setPreferredSize(new Dimension(380, 130));
    return panel;
  }

  @Override
  protected void doOKAction() {
    String rawValue = valueField.getText();
    boolean onlyEmpty = onlyEmptyCheckBox.isSelected();

    if ((rawValue == null || rawValue.trim().isEmpty()) && !onlyEmpty) {
      int confirm = JOptionPane.showConfirmDialog(
          getContentPanel(),
          "Set all rows to NULL?",
          "Confirm",
          JOptionPane.YES_NO_OPTION);
      if (confirm != JOptionPane.YES_OPTION) {
        return;
      }
    }

    try {
      // Validate the conversion up-front so an invalid value keeps the dialog open with an
      // error, without mutating any data yet.
      tableModel.convertValueForValidation(rawValue, columnType);
    } catch (IllegalArgumentException e) {
      JOptionPane.showMessageDialog(
          getContentPanel(),
          e.getMessage(),
          "Validation Error",
          JOptionPane.ERROR_MESSAGE);
      return;
    }

    super.doOKAction();
  }

  public String getValue() {
    return valueField.getText();
  }

  public boolean isOnlyEmpty() {
    return onlyEmptyCheckBox.isSelected();
  }

  public int getColumnIndex() {
    return columnIndex;
  }

  public String getColumnName() {
    return columnName;
  }
}
