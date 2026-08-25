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

import com.github.jhordyhuaman.parquetstudio.service.RemoteSchemaService.TokenStyle;
import com.intellij.openapi.ui.DialogWrapper;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.File;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JRadioButton;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.filechooser.FileFilter;

/**
 * Dialog for collecting the inputs needed to generate a new synthetic Parquet file from a
 * schema: schema source (local file or URL with optional token), row count, optional seed,
 * null percentage, and the target file to write.
 *
 * <p>This dialog only collects and validates input; it performs no I/O itself.
 */
public class GenerateDataDialog extends DialogWrapper {

  private JRadioButton localFileRadio;
  private JRadioButton urlRadio;

  private JTextField schemaFileField;
  private JButton browseSchemaFileButton;

  private JTextField urlField;
  private JPasswordField tokenField;
  private JComboBox<TokenStyleItem> tokenStyleComboBox;

  private JSpinner rowCountSpinner;
  private JTextField seedField;
  private JSpinner nullPercentSpinner;

  private JTextField targetFileField;
  private JButton browseTargetFileButton;

  private File schemaFile;
  private File targetFile;
  private Long parsedSeed;

  public GenerateDataDialog(Component parent) {
    super(parent, true);
    setTitle("Generate Data");
    init();
  }

  @Override
  protected JComponent createCenterPanel() {
    JPanel panel = new JPanel(new GridBagLayout());
    GridBagConstraints gbc = new GridBagConstraints();
    gbc.insets = new Insets(5, 5, 5, 5);
    gbc.anchor = GridBagConstraints.WEST;
    int row = 0;

    // Schema source radios
    localFileRadio = new JRadioButton("Local file", true);
    urlRadio = new JRadioButton("URL");
    ButtonGroup sourceGroup = new ButtonGroup();
    sourceGroup.add(localFileRadio);
    sourceGroup.add(urlRadio);
    localFileRadio.addActionListener(e -> updateSourceEnablement());
    urlRadio.addActionListener(e -> updateSourceEnablement());

    gbc.gridx = 0;
    gbc.gridy = row;
    panel.add(new JLabel("Schema source:"), gbc);
    gbc.gridx = 1;
    JPanel sourcePanel = new JPanel();
    sourcePanel.add(localFileRadio);
    sourcePanel.add(urlRadio);
    panel.add(sourcePanel, gbc);
    row++;

    // Local file chooser
    gbc.gridx = 0;
    gbc.gridy = row;
    panel.add(new JLabel("Schema file:"), gbc);
    gbc.gridx = 1;
    gbc.fill = GridBagConstraints.HORIZONTAL;
    gbc.weightx = 1.0;
    schemaFileField = new JTextField(24);
    schemaFileField.setEditable(false);
    panel.add(schemaFileField, gbc);
    gbc.gridx = 2;
    gbc.fill = GridBagConstraints.NONE;
    gbc.weightx = 0;
    browseSchemaFileButton = new JButton("Browse…");
    browseSchemaFileButton.addActionListener(e -> browseSchemaFile());
    panel.add(browseSchemaFileButton, gbc);
    row++;

    // URL + token
    gbc.gridx = 0;
    gbc.gridy = row;
    panel.add(new JLabel("Schema URL:"), gbc);
    gbc.gridx = 1;
    gbc.gridwidth = 2;
    gbc.fill = GridBagConstraints.HORIZONTAL;
    gbc.weightx = 1.0;
    urlField = new JTextField(24);
    panel.add(urlField, gbc);
    gbc.gridwidth = 1;
    row++;

    gbc.gridx = 0;
    gbc.gridy = row;
    gbc.fill = GridBagConstraints.NONE;
    gbc.weightx = 0;
    panel.add(new JLabel("Token:"), gbc);
    gbc.gridx = 1;
    gbc.gridwidth = 2;
    gbc.fill = GridBagConstraints.HORIZONTAL;
    gbc.weightx = 1.0;
    tokenField = new JPasswordField(24);
    panel.add(tokenField, gbc);
    gbc.gridwidth = 1;
    row++;

    gbc.gridx = 0;
    gbc.gridy = row;
    gbc.fill = GridBagConstraints.NONE;
    gbc.weightx = 0;
    panel.add(new JLabel("Token style:"), gbc);
    gbc.gridx = 1;
    gbc.fill = GridBagConstraints.HORIZONTAL;
    gbc.weightx = 1.0;
    tokenStyleComboBox = new JComboBox<>(new TokenStyleItem[] {
        new TokenStyleItem("Bearer token", TokenStyle.BEARER),
        new TokenStyleItem("JFrog API key", TokenStyle.JFROG)
    });
    panel.add(tokenStyleComboBox, gbc);
    row++;

    // Row count
    gbc.gridx = 0;
    gbc.gridy = row;
    gbc.fill = GridBagConstraints.NONE;
    gbc.weightx = 0;
    panel.add(new JLabel("Row count:"), gbc);
    gbc.gridx = 1;
    gbc.fill = GridBagConstraints.HORIZONTAL;
    gbc.weightx = 1.0;
    rowCountSpinner = new JSpinner(new SpinnerNumberModel(100, 1, 1_000_000, 1));
    panel.add(rowCountSpinner, gbc);
    row++;

    // Seed
    gbc.gridx = 0;
    gbc.gridy = row;
    gbc.fill = GridBagConstraints.NONE;
    gbc.weightx = 0;
    panel.add(new JLabel("Seed (optional):"), gbc);
    gbc.gridx = 1;
    gbc.fill = GridBagConstraints.HORIZONTAL;
    gbc.weightx = 1.0;
    seedField = new JTextField(24);
    panel.add(seedField, gbc);
    row++;

    // Null percent
    gbc.gridx = 0;
    gbc.gridy = row;
    gbc.fill = GridBagConstraints.NONE;
    gbc.weightx = 0;
    panel.add(new JLabel("Null %:"), gbc);
    gbc.gridx = 1;
    gbc.fill = GridBagConstraints.HORIZONTAL;
    gbc.weightx = 1.0;
    nullPercentSpinner = new JSpinner(new SpinnerNumberModel(5, 0, 100, 1));
    panel.add(nullPercentSpinner, gbc);
    row++;

    // Target file
    gbc.gridx = 0;
    gbc.gridy = row;
    gbc.fill = GridBagConstraints.NONE;
    gbc.weightx = 0;
    panel.add(new JLabel("Target file:"), gbc);
    gbc.gridx = 1;
    gbc.fill = GridBagConstraints.HORIZONTAL;
    gbc.weightx = 1.0;
    targetFileField = new JTextField(24);
    targetFileField.setEditable(false);
    panel.add(targetFileField, gbc);
    gbc.gridx = 2;
    gbc.fill = GridBagConstraints.NONE;
    gbc.weightx = 0;
    browseTargetFileButton = new JButton("Choose…");
    browseTargetFileButton.addActionListener(e -> browseTargetFile());
    panel.add(browseTargetFileButton, gbc);

    panel.setPreferredSize(new Dimension(480, 340));
    panel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

    updateSourceEnablement();
    return panel;
  }

  private void updateSourceEnablement() {
    boolean isLocal = localFileRadio.isSelected();
    schemaFileField.setEnabled(isLocal);
    browseSchemaFileButton.setEnabled(isLocal);
    urlField.setEnabled(!isLocal);
    tokenField.setEnabled(!isLocal);
    tokenStyleComboBox.setEnabled(!isLocal);
  }

  private void browseSchemaFile() {
    JFileChooser fileChooser = new JFileChooser();
    fileChooser.setDialogTitle("Select Schema File");
    fileChooser.setFileFilter(new FileFilter() {
      @Override
      public boolean accept(File f) {
        String name = f.getName().toLowerCase();
        return f.isDirectory() || name.endsWith(".schema") || name.endsWith(".json");
      }

      @Override
      public String getDescription() {
        return "Schema Files (*.schema, *.json)";
      }
    });
    int result = fileChooser.showOpenDialog(getContentPanel());
    if (result == JFileChooser.APPROVE_OPTION) {
      schemaFile = fileChooser.getSelectedFile();
      schemaFileField.setText(schemaFile.getAbsolutePath());
    }
  }

  private void browseTargetFile() {
    JFileChooser fileChooser = new JFileChooser();
    fileChooser.setDialogTitle("Choose Target File");
    fileChooser.setSelectedFile(new File("synthetic-data.parquet"));
    int result = fileChooser.showSaveDialog(getContentPanel());
    if (result == JFileChooser.APPROVE_OPTION) {
      targetFile = fileChooser.getSelectedFile();
      targetFileField.setText(targetFile.getAbsolutePath());
    }
  }

  @Override
  protected void doOKAction() {
    if (localFileRadio.isSelected()) {
      if (schemaFile == null) {
        showError("Please select a schema file.");
        return;
      }
    } else {
      if (urlField.getText().trim().isEmpty()) {
        showError("Please enter a schema URL.");
        return;
      }
    }

    if (targetFile == null) {
      showError("Please choose a target file.");
      return;
    }

    String seedText = seedField.getText().trim();
    if (!seedText.isEmpty()) {
      try {
        parsedSeed = Long.parseLong(seedText);
      } catch (NumberFormatException e) {
        showError("Seed must be a whole number.");
        return;
      }
    } else {
      parsedSeed = null;
    }

    super.doOKAction();
  }

  private void showError(String message) {
    JOptionPane.showMessageDialog(getContentPanel(), message, "Validation Error", JOptionPane.ERROR_MESSAGE);
  }

  public boolean isLocalFileSource() {
    return localFileRadio.isSelected();
  }

  public File getSchemaFile() {
    return schemaFile;
  }

  public String getUrl() {
    return urlField.getText().trim();
  }

  /** Returns the token as a String, built only at request time. Never logged or persisted. */
  public String getToken() {
    char[] chars = tokenField.getPassword();
    return new String(chars);
  }

  public TokenStyle getTokenStyle() {
    TokenStyleItem item = (TokenStyleItem) tokenStyleComboBox.getSelectedItem();
    return item != null ? item.style : TokenStyle.BEARER;
  }

  public int getRowCount() {
    return (Integer) rowCountSpinner.getValue();
  }

  public Long getSeed() {
    return parsedSeed;
  }

  public double getNullRatio() {
    return ((Integer) nullPercentSpinner.getValue()) / 100.0;
  }

  public File getTargetFile() {
    return targetFile;
  }

  private static final class TokenStyleItem {
    private final String label;
    private final TokenStyle style;

    private TokenStyleItem(String label, TokenStyle style) {
      this.label = label;
      this.style = style;
    }

    @Override
    public String toString() {
      return label;
    }
  }
}
