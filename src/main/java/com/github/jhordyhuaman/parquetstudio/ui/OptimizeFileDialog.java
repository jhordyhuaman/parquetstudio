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

import com.github.jhordyhuaman.parquetstudio.service.ParquetOptimizationService;
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
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JTextField;

/**
 * Dialog for choosing a file-optimization operation: Compact, Fragment, or Consolidate.
 * This dialog only collects the user's choices; it performs no work itself.
 */
public class OptimizeFileDialog extends DialogWrapper {

  /** The operation the user chose. */
  public enum Operation {
    COMPACT,
    FRAGMENT,
    CONSOLIDATE
  }

  private final ParquetOptimizationService optimizationService = new ParquetOptimizationService();
  private final boolean compactAvailable;
  private final boolean fragmentAvailable;

  private JRadioButton compactRadio;
  private JRadioButton fragmentRadio;
  private JRadioButton consolidateRadio;

  // Fragment sub-panel
  private JPanel fragmentPanel;
  private JRadioButton numFilesRadio;
  private JRadioButton rowsPerFileRadio;
  private JRadioButton approxMbRadio;
  private JTextField fragmentValueField;
  private JTextField fragmentDestDirField;

  // Consolidate sub-panel
  private JPanel consolidatePanel;
  private JTextField consolidateSourceDirField;
  private JLabel consolidateFileCountLabel;
  private JTextField consolidateOutputFileField;

  /**
   * @param parent the parent component
   * @param compactAvailable whether Compact is selectable (requires an open file)
   * @param fragmentAvailable whether Fragment is selectable (requires an open file)
   */
  public OptimizeFileDialog(Component parent, boolean compactAvailable, boolean fragmentAvailable) {
    super(parent, true);
    this.compactAvailable = compactAvailable;
    this.fragmentAvailable = fragmentAvailable;
    setTitle("Optimize File");
    init();
  }

  @Override
  protected JComponent createCenterPanel() {
    JPanel panel = new JPanel(new GridBagLayout());
    GridBagConstraints gbc = new GridBagConstraints();
    gbc.insets = new Insets(5, 5, 5, 5);
    gbc.anchor = GridBagConstraints.WEST;
    gbc.fill = GridBagConstraints.HORIZONTAL;
    gbc.weightx = 1.0;

    ButtonGroup operationGroup = new ButtonGroup();

    compactRadio = new JRadioButton("Compact (rewrite current file with ZSTD compression)");
    compactRadio.setEnabled(compactAvailable);
    fragmentRadio = new JRadioButton("Fragment (split current file into part files)");
    fragmentRadio.setEnabled(fragmentAvailable);
    consolidateRadio = new JRadioButton("Consolidate (merge part files from a directory into one)");

    operationGroup.add(compactRadio);
    operationGroup.add(fragmentRadio);
    operationGroup.add(consolidateRadio);

    gbc.gridx = 0;
    gbc.gridy = 0;
    panel.add(compactRadio, gbc);
    gbc.gridy = 1;
    panel.add(fragmentRadio, gbc);
    gbc.gridy = 2;
    panel.add(consolidateRadio, gbc);

    gbc.gridy = 3;
    panel.add(buildFragmentPanel(), gbc);

    gbc.gridy = 4;
    panel.add(buildConsolidatePanel(), gbc);

    if (compactAvailable) {
      compactRadio.setSelected(true);
    } else if (fragmentAvailable) {
      fragmentRadio.setSelected(true);
    } else {
      consolidateRadio.setSelected(true);
    }
    updateEnabledState();

    compactRadio.addActionListener(e -> updateEnabledState());
    fragmentRadio.addActionListener(e -> updateEnabledState());
    consolidateRadio.addActionListener(e -> updateEnabledState());

    panel.setPreferredSize(new Dimension(480, 360));
    return panel;
  }

  private JPanel buildFragmentPanel() {
    fragmentPanel = new JPanel(new GridBagLayout());
    fragmentPanel.setBorder(BorderFactory.createTitledBorder("Fragment options"));
    GridBagConstraints gbc = new GridBagConstraints();
    gbc.insets = new Insets(3, 3, 3, 3);
    gbc.anchor = GridBagConstraints.WEST;
    gbc.fill = GridBagConstraints.HORIZONTAL;
    gbc.weightx = 1.0;

    ButtonGroup criterionGroup = new ButtonGroup();
    numFilesRadio = new JRadioButton("Number of files (N)");
    rowsPerFileRadio = new JRadioButton("Rows per file");
    approxMbRadio = new JRadioButton("Approx. MB per file");
    criterionGroup.add(numFilesRadio);
    criterionGroup.add(rowsPerFileRadio);
    criterionGroup.add(approxMbRadio);
    numFilesRadio.setSelected(true);

    gbc.gridx = 0;
    gbc.gridy = 0;
    fragmentPanel.add(numFilesRadio, gbc);
    gbc.gridy = 1;
    fragmentPanel.add(rowsPerFileRadio, gbc);
    gbc.gridy = 2;
    fragmentPanel.add(approxMbRadio, gbc);

    gbc.gridy = 3;
    fragmentPanel.add(new JLabel("Value:"), gbc);
    gbc.gridy = 4;
    fragmentValueField = new JTextField(10);
    fragmentPanel.add(fragmentValueField, gbc);

    gbc.gridy = 5;
    fragmentPanel.add(new JLabel("Destination directory:"), gbc);
    gbc.gridy = 6;
    JPanel destRow = new JPanel(new java.awt.BorderLayout(5, 0));
    fragmentDestDirField = new JTextField();
    JButton browseDestButton = new JButton("Browse…");
    browseDestButton.addActionListener(e -> chooseDirectory(fragmentDestDirField, "Choose Destination Directory"));
    destRow.add(fragmentDestDirField, java.awt.BorderLayout.CENTER);
    destRow.add(browseDestButton, java.awt.BorderLayout.EAST);
    fragmentPanel.add(destRow, gbc);

    return fragmentPanel;
  }

  private JPanel buildConsolidatePanel() {
    consolidatePanel = new JPanel(new GridBagLayout());
    consolidatePanel.setBorder(BorderFactory.createTitledBorder("Consolidate options"));
    GridBagConstraints gbc = new GridBagConstraints();
    gbc.insets = new Insets(3, 3, 3, 3);
    gbc.anchor = GridBagConstraints.WEST;
    gbc.fill = GridBagConstraints.HORIZONTAL;
    gbc.weightx = 1.0;

    gbc.gridx = 0;
    gbc.gridy = 0;
    consolidatePanel.add(new JLabel("Source directory:"), gbc);
    gbc.gridy = 1;
    JPanel sourceRow = new JPanel(new java.awt.BorderLayout(5, 0));
    consolidateSourceDirField = new JTextField();
    JButton browseSourceButton = new JButton("Browse…");
    browseSourceButton.addActionListener(e -> {
      chooseDirectory(consolidateSourceDirField, "Choose Source Directory");
      updateConsolidateFileCount();
    });
    sourceRow.add(consolidateSourceDirField, java.awt.BorderLayout.CENTER);
    sourceRow.add(browseSourceButton, java.awt.BorderLayout.EAST);
    consolidatePanel.add(sourceRow, gbc);

    gbc.gridy = 2;
    consolidateFileCountLabel = new JLabel(" ");
    consolidatePanel.add(consolidateFileCountLabel, gbc);

    gbc.gridy = 3;
    consolidatePanel.add(new JLabel("Output file:"), gbc);
    gbc.gridy = 4;
    JPanel outputRow = new JPanel(new java.awt.BorderLayout(5, 0));
    consolidateOutputFileField = new JTextField();
    JButton browseOutputButton = new JButton("Browse…");
    browseOutputButton.addActionListener(e -> chooseOutputFile());
    outputRow.add(consolidateOutputFileField, java.awt.BorderLayout.CENTER);
    outputRow.add(browseOutputButton, java.awt.BorderLayout.EAST);
    consolidatePanel.add(outputRow, gbc);

    return consolidatePanel;
  }

  private void chooseDirectory(JTextField target, String title) {
    JFileChooser chooser = new JFileChooser();
    chooser.setDialogTitle(title);
    chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
    if (!target.getText().trim().isEmpty()) {
      chooser.setCurrentDirectory(new File(target.getText().trim()));
    }
    int result = chooser.showOpenDialog(this.getContentPanel());
    if (result == JFileChooser.APPROVE_OPTION) {
      target.setText(chooser.getSelectedFile().getAbsolutePath());
    }
  }

  private void chooseOutputFile() {
    JFileChooser chooser = new JFileChooser();
    chooser.setDialogTitle("Choose Output File");
    if (!consolidateSourceDirField.getText().trim().isEmpty()) {
      chooser.setCurrentDirectory(new File(consolidateSourceDirField.getText().trim()));
    }
    int result = chooser.showSaveDialog(this.getContentPanel());
    if (result == JFileChooser.APPROVE_OPTION) {
      File selected = chooser.getSelectedFile();
      if (!selected.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".parquet")) {
        selected = new File(selected.getPath() + ".parquet");
      }
      consolidateOutputFileField.setText(selected.getAbsolutePath());
    }
  }

  private void updateConsolidateFileCount() {
    String dirPath = consolidateSourceDirField.getText().trim();
    if (dirPath.isEmpty()) {
      consolidateFileCountLabel.setText(" ");
      return;
    }
    File dir = new File(dirPath);
    if (!dir.isDirectory()) {
      consolidateFileCountLabel.setText("Not a directory.");
      return;
    }
    int count = optimizationService.listParquetFiles(dir).size();
    consolidateFileCountLabel.setText(count + " parquet file(s) found.");
  }

  private void updateEnabledState() {
    boolean fragmentSelected = fragmentRadio.isSelected();
    boolean consolidateSelected = consolidateRadio.isSelected();
    setPanelEnabled(fragmentPanel, fragmentSelected);
    setPanelEnabled(consolidatePanel, consolidateSelected);
    if (consolidateSelected) {
      updateConsolidateFileCount();
    }
  }

  private void setPanelEnabled(JComponent component, boolean enabled) {
    component.setEnabled(enabled);
    for (java.awt.Component child : component.getComponents()) {
      if (child instanceof JComponent) {
        setPanelEnabled((JComponent) child, enabled);
      }
    }
  }

  @Override
  protected void doOKAction() {
    if (fragmentRadio.isSelected()) {
      if (!validateFragmentInputs()) {
        return;
      }
    } else if (consolidateRadio.isSelected()) {
      if (!validateConsolidateInputs()) {
        return;
      }
    }
    super.doOKAction();
  }

  private boolean validateFragmentInputs() {
    long value;
    try {
      value = Long.parseLong(fragmentValueField.getText().trim());
    } catch (NumberFormatException e) {
      showValidationError("Please enter a valid whole number for the fragment value.");
      return false;
    }
    if (value <= 0) {
      showValidationError("The fragment value must be a positive number.");
      return false;
    }
    String destPath = fragmentDestDirField.getText().trim();
    if (destPath.isEmpty()) {
      showValidationError("Please choose a destination directory.");
      return false;
    }
    File destDir = new File(destPath);
    if (!destDir.isDirectory()) {
      showValidationError("The destination directory does not exist.");
      return false;
    }
    return true;
  }

  private boolean validateConsolidateInputs() {
    String sourcePath = consolidateSourceDirField.getText().trim();
    if (sourcePath.isEmpty()) {
      showValidationError("Please choose a source directory.");
      return false;
    }
    File sourceDir = new File(sourcePath);
    if (!sourceDir.isDirectory()) {
      showValidationError("The source directory does not exist.");
      return false;
    }
    if (optimizationService.listParquetFiles(sourceDir).size() < 2) {
      showValidationError("The source directory must contain at least 2 parquet files to consolidate.");
      return false;
    }
    String outputPath = consolidateOutputFileField.getText().trim();
    if (outputPath.isEmpty()) {
      showValidationError("Please choose an output file.");
      return false;
    }
    return true;
  }

  private void showValidationError(String message) {
    JOptionPane.showMessageDialog(
        getContentPanel(), message, "Validation Error", JOptionPane.ERROR_MESSAGE);
  }

  public Operation getOperation() {
    if (compactRadio.isSelected()) {
      return Operation.COMPACT;
    }
    if (fragmentRadio.isSelected()) {
      return Operation.FRAGMENT;
    }
    return Operation.CONSOLIDATE;
  }

  public ParquetOptimizationService.FragmentCriterion getFragmentCriterion() {
    if (numFilesRadio.isSelected()) {
      return ParquetOptimizationService.FragmentCriterion.NUM_FILES;
    }
    if (rowsPerFileRadio.isSelected()) {
      return ParquetOptimizationService.FragmentCriterion.ROWS_PER_FILE;
    }
    return ParquetOptimizationService.FragmentCriterion.APPROX_MB_PER_FILE;
  }

  public long getFragmentValue() {
    try {
      return Long.parseLong(fragmentValueField.getText().trim());
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  public File getFragmentDestDir() {
    String text = fragmentDestDirField.getText().trim();
    return text.isEmpty() ? null : new File(text);
  }

  public File getConsolidateSourceDir() {
    String text = consolidateSourceDirField.getText().trim();
    return text.isEmpty() ? null : new File(text);
  }

  public File getConsolidateOutputFile() {
    String text = consolidateOutputFileField.getText().trim();
    return text.isEmpty() ? null : new File(text);
  }
}
