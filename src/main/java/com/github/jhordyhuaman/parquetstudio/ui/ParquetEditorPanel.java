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

import com.github.jhordyhuaman.parquetstudio.Constants;
import com.github.jhordyhuaman.parquetstudio.model.ParquetData;
import com.github.jhordyhuaman.parquetstudio.model.ParquetTableModel;
import com.github.jhordyhuaman.parquetstudio.model.SchemaValidationResult;
import com.github.jhordyhuaman.parquetstudio.service.ParquetEditorService;
import com.github.jhordyhuaman.parquetstudio.service.SchemaValidationService;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.IconLoader;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.table.JBTable;
import java.awt.*;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileFilter;
import javax.swing.table.TableCellEditor;
import javax.swing.table.TableModel;
import javax.swing.table.TableRowSorter;
import javax.swing.RowFilter;
import javax.swing.text.*;
import java.awt.Component;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Panel for editing a single Parquet file.
 * This component can be used in tabs to allow editing multiple Parquet files simultaneously.
 */
public class ParquetEditorPanel extends JPanel {
  private static final Logger LOGGER = Logger.getInstance(ParquetEditorPanel.class);

  private final ParquetEditorService editorService;
  private ParquetTableModel tableModel;
  private JBTable dataTable;
  private JLabel statusLabel;
  private JTextField searchField;
  private JButton searchButton;
  private JButton addRowButton;
  private JButton addColumnButton;
  private JButton deleteRowButton;
  private JButton deleteColumnButton;
  private JButton saveAsButton;
  private JPanel containerPanel;
  private JPanel dataPanel;
  private JPanel schemaPanel;
  private JPanel loadingPanel;
  private JLabel loadingLabel;
  private JLabel loadingFileInfoLabel;
  private JProgressBar loadingProgressBar;
  private JButton goSchemaButton;
  private JButton goDataButton;
  private boolean showingPanelData = true;
  private JCheckBox schemaCheckBox;
  private JCheckBox strictModeCheckBox;
  private JLabel strictModeJLabel;
  private JTextPane jsonTextPane;
  private TableRowSorter<TableModel> rowSorter;

  // Track the file being loaded (before it's fully loaded into the service)
  private File loadingFile;

  private volatile boolean dirty = false;
  private final javax.swing.event.TableModelListener dirtyListener = e -> dirty = true;

  public boolean isDirty() {
    return dirty;
  }

  public void markSaved() {
    dirty = false;
  }

  /** Returns the current table model, for tests and callers needing direct access. */
  public ParquetTableModel getTableModel() {
    return tableModel;
  }

  public ParquetEditorPanel() {
    this(true);
  }

  public ParquetEditorPanel(boolean initUI) {
    this.editorService = new ParquetEditorService();
    if(initUI) initializeUI();
  }

  /**
   * Gets the currently loaded file or the file being loaded.
   *
   * @return the current file, or null if no file is loaded/loading
   */
  public File getCurrentFile() {
    File currentFile = editorService.getCurrentFile();
    // If service doesn't have a file yet, return the file being loaded
    return currentFile != null ? currentFile : loadingFile;
  }

  /**
   * Checks if a file is currently loaded or being loaded.
   *
   * @return true if a file is loaded or loading, false otherwise
   */
  public boolean hasFile() {
    return editorService.hasFile() || loadingFile != null;
  }

  /** Returns the loaded file, or the file currently being loaded, or null. */
  public File getLoadingOrCurrentFile() {
    if (hasFile()) {
      return getCurrentFile();
    }
    return loadingFile;
  }

  /**
   * Gets the display name for this editor (typically the file name).
   *
   * @return the display name
   */
  public String getDisplayName() {
    File file = editorService.getCurrentFile();
    if (file != null) {
      return file.getName();
    }
    return "Untitled";
  }

  private void initializeUI() {
    setLayout(new BorderLayout());
    containerPanel = new JPanel(new CardLayout());

    // SECTION: Loading Panel
    loadingPanel = createLoadingPanel();
    containerPanel.add(loadingPanel, Constants.LOADING_PANEL);

    // SECTION: Data Panel
    dataPanel = new JPanel(new BorderLayout());
    // Toolbar
    JPanel toolbarPanel = createToolbar();
    dataPanel.add(toolbarPanel, BorderLayout.NORTH);

    // Table
    dataTable = new JBTable();
    dataTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
    dataTable.setFillsViewportHeight(true);
    JScrollPane tableScrollPane = new JScrollPane(dataTable);
    dataPanel.add(tableScrollPane, BorderLayout.CENTER);

    containerPanel.add(dataPanel, Constants.DATA_PANEL);

    // SECTION: Schema Panel
    schemaPanel = new JPanel(new BorderLayout());

    JPanel schemaToolbarPanel = createSchemaToolbar();
    schemaPanel.add(schemaToolbarPanel, BorderLayout.NORTH);

    // Json viewer
    JScrollPane jsonScrollPanel = createJsonViewPanel();
    schemaPanel.add(jsonScrollPanel, BorderLayout.CENTER);
    containerPanel.add(schemaPanel, Constants.SCHEMA_PANEL);

    // SECTION: Status Bar
    statusLabel = new JLabel("Ready. Open a Parquet file to begin.");
    statusLabel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

    add(containerPanel, BorderLayout.CENTER);
    add(statusLabel, BorderLayout.SOUTH);
  }

  /**
   * Creates a loading panel with progress indicator.
   */
  private JPanel createLoadingPanel() {
    JPanel panel = new JPanel(new GridBagLayout());
    panel.setBackground(UIManager.getColor("Panel.background"));

    GridBagConstraints gbc = new GridBagConstraints();
    gbc.gridx = 0;
    gbc.gridy = 0;
    gbc.insets = new Insets(10, 10, 10, 10);
    gbc.anchor = GridBagConstraints.CENTER;

    // Loading icon
    JLabel iconLabel = new JLabel();
    iconLabel.setIcon(UIManager.getIcon("OptionPane.informationIcon"));
    panel.add(iconLabel, gbc);

    // Loading message
    gbc.gridy = 1;
    loadingLabel = new JLabel(Constants.Message.LOADING_FILE);
    loadingLabel.setFont(loadingLabel.getFont().deriveFont(Font.BOLD, 14f));
    panel.add(loadingLabel, gbc);

    // File info label
    gbc.gridy = 2;
    loadingFileInfoLabel = new JLabel("");
    loadingFileInfoLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
    panel.add(loadingFileInfoLabel, gbc);

    // Progress bar
    gbc.gridy = 3;
    gbc.fill = GridBagConstraints.HORIZONTAL;
    loadingProgressBar = new JProgressBar();
    loadingProgressBar.setIndeterminate(true);
    loadingProgressBar.setPreferredSize(new Dimension(300, 20));
    loadingProgressBar.setStringPainted(true);
    loadingProgressBar.setString("");
    panel.add(loadingProgressBar, gbc);

    // Additional info
    gbc.gridy = 4;
    gbc.fill = GridBagConstraints.NONE;
    JLabel hintLabel = new JLabel("Large files may take longer to load");
    hintLabel.setFont(hintLabel.getFont().deriveFont(Font.ITALIC, 11f));
    hintLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
    panel.add(hintLabel, gbc);

    return panel;
  }

  /**
   * Shows the loading panel with file information.
   */
  private void showLoadingPanel(File file) {
    loadingLabel.setText(Constants.Message.LOADING_FILE);
    loadingFileInfoLabel.setText(file.getName() + " (" + formatFileSize(file.length()) + ")");
    loadingProgressBar.setString(Constants.Message.LOADING_READING_SCHEMA);
    loadingProgressBar.setIndeterminate(true);

    CardLayout cl = (CardLayout) containerPanel.getLayout();
    cl.show(containerPanel, Constants.LOADING_PANEL);
    showingPanelData = false;
  }

  /**
   * Updates the loading status message.
   */
  private void updateLoadingStatus(String message) {
    SwingUtilities.invokeLater(() -> {
      loadingProgressBar.setString(message);
      statusLabel.setText(message);
    });
  }

  /**
   * Shows the data panel after loading.
   */
  private void showDataPanel() {
    CardLayout cl = (CardLayout) containerPanel.getLayout();
    cl.show(containerPanel, Constants.DATA_PANEL);
    showingPanelData = true;
  }

  /**
   * Formats file size in human-readable format.
   */
  private String formatFileSize(long bytes) {
    if (bytes < 1024) return bytes + " B";
    int exp = (int) (Math.log(bytes) / Math.log(1024));
    String pre = "KMGTPE".charAt(exp - 1) + "";
    return String.format("%.1f %sB", bytes / Math.pow(1024, exp), pre);
  }

  private JPanel createToolbar() {
    JPanel toolbar = new JPanel();
    toolbar.setLayout(new BoxLayout(toolbar, BoxLayout.X_AXIS));
    toolbar.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

    // Search
    toolbar.add(new JLabel("Search:"));
    searchField = new JTextField(20);
    searchField.setToolTipText("Search in all columns");
    searchField.addActionListener(e -> performSearch());
    toolbar.add(searchField);

    // Search - using custom icon with theme support
    searchButton = new JButton(IconLoader.getIcon("/icons/ui/search/search.svg", ParquetEditorPanel.class));
    searchButton.setToolTipText("Search");
    searchButton.addActionListener(e -> performSearch());
    toolbar.add(searchButton);

    toolbar.add(new JSeparator(SwingConstants.VERTICAL));

    // Add Row - using custom icon with theme support
    addRowButton = new JButton(IconLoader.getIcon("/icons/ui/addRowAbove/addRowAbove.svg", ParquetEditorPanel.class));
    addRowButton.setToolTipText("Add Row");
    addRowButton.addActionListener(e -> addRow());
    toolbar.add(addRowButton);

    // Delete Row - using custom dropColumn icon with theme support
    deleteRowButton = new JButton(IconLoader.getIcon("/icons/ui/dropSequence/dropSequence.svg", ParquetEditorPanel.class));
    deleteRowButton.setToolTipText("Delete Row");
    deleteRowButton.addActionListener(e -> deleteSelectedRows());
    toolbar.add(deleteRowButton);

    toolbar.add(new JSeparator(SwingConstants.VERTICAL));

    // Add Column - using custom createColumn icon with theme support
    addColumnButton = new JButton(IconLoader.getIcon("/icons/ui/createColumn/createColumn.svg", ParquetEditorPanel.class));
    addColumnButton.setToolTipText("Add Column");
    addColumnButton.addActionListener(e -> addColumn());
    toolbar.add(addColumnButton);

    // Delete Column - using custom dropColumn icon with theme support
    deleteColumnButton = new JButton(IconLoader.getIcon("/icons/ui/dropColumn/dropColumn.svg", ParquetEditorPanel.class));
    deleteColumnButton.setToolTipText("Delete Column");
    deleteColumnButton.addActionListener(e -> deleteSelectedColumn());
    toolbar.add(deleteColumnButton);

    toolbar.add(new JSeparator(SwingConstants.VERTICAL));

    // Save As - using custom save icon with theme support
    saveAsButton = new JButton(IconLoader.getIcon("/icons/ui/save/save.svg", ParquetEditorPanel.class));
    saveAsButton.setToolTipText("Save As...");
    saveAsButton.addActionListener(e -> saveAsParquet());

    // Validate Schema button
    JButton validateSchemaButton = new JButton("Validate Schema");
    validateSchemaButton.setToolTipText("Validate Parquet types against a schema file");
    validateSchemaButton.addActionListener(e -> validateSchemaAgainstFile());

    goSchemaButton = new JButton("View Schema");
    goSchemaButton.addActionListener(e -> changePanel() );

    toolbar.add(saveAsButton);
    toolbar.add(validateSchemaButton);
    toolbar.add(goSchemaButton);

    updateButtonStates(false);

    return toolbar;
  }

  private JPanel createSchemaToolbar() {
    JPanel schemaToolbar = new JPanel();
    schemaToolbar.setLayout(new BoxLayout(schemaToolbar, BoxLayout.X_AXIS));
    schemaToolbar.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

    // Load schema
    JButton loadSchemaButton = new JButton("Load Schema");
    loadSchemaButton.addActionListener(e -> loadSchemaFile());
    schemaToolbar.add(loadSchemaButton);
    schemaToolbar.add(new JSeparator(SwingConstants.VERTICAL));

    JPanel checkBoxPanel = new JPanel();
    checkBoxPanel.setLayout(new BoxLayout(checkBoxPanel, BoxLayout.Y_AXIS));

    schemaCheckBox = new JCheckBox("Write with this schema");
    schemaCheckBox.setEnabled(false);

    strictModeCheckBox = new JCheckBox("All columns are in parquet");
    strictModeCheckBox.setEnabled(false);
    strictModeCheckBox.addActionListener(e -> {
        if(strictModeCheckBox.isSelected() && !complyStrictMode()){
            strictModeCheckBox.setSelected(false);
            strictModeJLabel.setText(Constants.Message.SCHEMA_AND_PARQUET_NOT_SAME_COLUMNS_2);
            Messages.showWarningDialog(Constants.Message.SCHEMA_AND_PARQUET_NOT_SAME_COLUMNS, "Schema");
        }
    });

    checkBoxPanel.add(schemaCheckBox);
    checkBoxPanel.add(strictModeCheckBox);

    schemaToolbar.add(checkBoxPanel);
    schemaToolbar.add(new JSeparator(SwingConstants.VERTICAL));

    strictModeJLabel = new JLabel("");
    strictModeJLabel.setBorder(new EmptyBorder(0, 10, 0, 10));
    schemaToolbar.add(strictModeJLabel);

    schemaToolbar.add(new JSeparator(SwingConstants.VERTICAL));

    goDataButton = new JButton("Back Data View");
    goDataButton.addActionListener(e -> changePanel() );
    schemaToolbar.add(goDataButton);

    return schemaToolbar;
  }

  private void changePanel() {
      CardLayout cl = (CardLayout) containerPanel.getLayout();
      if(showingPanelData){
          cl.show(containerPanel, Constants.SCHEMA_PANEL);
      }else{
          cl.show(containerPanel, Constants.DATA_PANEL);
      }
      showingPanelData = !showingPanelData;
  }

  private JScrollPane createJsonViewPanel(){
      jsonTextPane = new JTextPane();
      jsonTextPane.setEditable(false);
      jsonTextPane.setText("SCHEMA OF PARQUET");

      return new JScrollPane(jsonTextPane);
  }

  private void writeOriginalSchemaInPanel(java.util.List<String> columnNames, java.util.List<String> columnTypes) throws Exception{
      String schemString = editorService.generateOriginalSchemaString(columnNames, columnTypes);
      applyJsonHighlighting(schemString);
  }

  private void writeTransformationSchemaInPanel(File selectedFile) throws Exception {
      String schemString = editorService.setSchemaFile(selectedFile).generateTransformSchemaString();
      applyJsonHighlighting(schemString);
  }

  private void applyJsonHighlighting(String json) {
      StyledDocument doc = jsonTextPane.getStyledDocument();

      StyleContext sc = StyleContext.getDefaultStyleContext();
      AttributeSet keyColor = sc.addAttribute(SimpleAttributeSet.EMPTY, StyleConstants.Foreground, new Color(230, 162, 60));
      AttributeSet stringColor = sc.addAttribute(SimpleAttributeSet.EMPTY, StyleConstants.Foreground, new Color(40, 170, 60));
      AttributeSet numberColor = sc.addAttribute(SimpleAttributeSet.EMPTY, StyleConstants.Foreground, new Color(190, 60, 190));
      AttributeSet braceColor = sc.addAttribute(SimpleAttributeSet.EMPTY, StyleConstants.Foreground, new Color(60, 120, 200));

      try {
          doc.remove(0, jsonTextPane.getText().length());
          doc.insertString(0, json, null);
      } catch (Exception e) { e.printStackTrace(); }

      applyPattern(json, "\"(.*?)\"\\s*:", keyColor, doc);     // keys
      applyPattern(json, ":\\s*\".*?\"", stringColor, doc);    // strings
      applyPattern(json, ":\\s*(\\d+\\.\\d+|\\d+)", numberColor, doc); // números
      applyPattern(json, "\\b(true|false|null)\\b", numberColor, doc); // boolean / null
      applyPattern(json, "[\\{\\}\\[\\]]", braceColor, doc);   // llaves y corchetes
  }

    private void applyPattern(String text, String regex, AttributeSet style, StyledDocument doc) {
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(text);

        while (matcher.find()) {
            doc.setCharacterAttributes(matcher.start(), matcher.end() - matcher.start(), style, false);
        }
    }

    private boolean isValidSchemaFile(File schemaFile){
      if (schemaFile == null || !schemaFile.exists()) {
          Messages.showErrorDialog("Select a schema file that exists.", "Error Schema");
          return false;
      }
      String[] validFormats = {".schema", ".json"};
      String filePath = schemaFile.getPath();

      boolean isValid = Arrays.stream(validFormats).anyMatch(filePath::endsWith);
      if(!isValid){
          Messages.showErrorDialog("Select a valid format: .schema or .json.", "Error Schema");
          return false;
      }
      return true;
  }

  private void loadSchemaFile() {
      JFileChooser fileChooser = new JFileChooser();
      fileChooser.setDialogTitle("Select schema file");
      if (editorService.hasSchemaFile()) {
          fileChooser.setCurrentDirectory(editorService.getCurrentSchemaFile().getParentFile());
      }

      fileChooser.setFileFilter(new FileFilter() {
          @Override
          public boolean accept(File f) {
              String fileName = f.getName().toLowerCase();
              return f.isDirectory() || fileName.endsWith(".schema") || fileName.endsWith(".json");
          }

          @Override
          public String getDescription() {
              return "Schema Files (*.schema)";
          }
      });

      int result = fileChooser.showSaveDialog(this);
      if (result == JFileChooser.APPROVE_OPTION) {
          File selectedFile = fileChooser.getSelectedFile();
          if( !isValidSchemaFile(selectedFile) ) return;

          try {
              writeTransformationSchemaInPanel(selectedFile);
              strictModeCheckBox.setEnabled(true);

              if(complyStrictMode()){
                  strictModeCheckBox.setSelected(true);
              }else{
                  strictModeCheckBox.setSelected(false);
                  strictModeJLabel.setText(Constants.Message.SCHEMA_AND_PARQUET_NOT_SAME_COLUMNS_2);
                  Messages.showWarningDialog(Constants.Message.SCHEMA_AND_PARQUET_NOT_SAME_COLUMNS, "Schema");
              }
          } catch (Exception e) {
              Messages.showWarningDialog("Can't read the schema.", "Schema File");
              LOGGER.error(e.getMessage());
          }

          schemaCheckBox.setSelected(true);
          schemaCheckBox.setEnabled(true);
      }
  }

  private boolean complyStrictMode() {
      if(editorService.getSchemaStructureOriginal() == null || editorService.getSchemaStructureTransform() == null){
          Messages.showErrorDialog("Should load parquet and schema file", "Schema");
          LOGGER.warn("parquet or schema file are not loaded.");
          return false;
      }
      return editorService.isSameNumberOfColumns();
  }

    private void updateButtonStates(boolean hasData) {
    if (searchButton != null) searchButton.setEnabled(hasData);
    if (addRowButton != null) addRowButton.setEnabled(hasData);
    if (addColumnButton != null) addColumnButton.setEnabled(hasData);
    if (deleteColumnButton != null) deleteColumnButton.setEnabled(hasData);
    if (deleteRowButton != null) deleteRowButton.setEnabled(hasData);
    if (saveAsButton != null) saveAsButton.setEnabled(hasData);
    if (goSchemaButton != null) goSchemaButton.setEnabled(hasData);
    if (searchField != null) searchField.setEnabled(hasData);
  }

  /**
   * Validates file before loading.
   * @return true if file is valid, false otherwise
   */
  private boolean validateFileForLoading(File file) {
    // Check if file exists
    if (!file.exists()) {
      Messages.showErrorDialog(
          String.format(Constants.Message.ERROR_FILE_NOT_FOUND, file.getName()),
          "File Not Found");
      return false;
    }

    // Check if file is readable
    if (!file.canRead()) {
      Messages.showErrorDialog(
          String.format(Constants.Message.ERROR_FILE_NOT_READABLE, file.getName()),
          "Cannot Read File");
      return false;
    }

    // Check file size
    long fileSize = file.length();

    if (fileSize > Constants.FILE_SIZE_MAX_THRESHOLD) {
      Messages.showErrorDialog(Constants.Message.FILE_TOO_LARGE, "File Too Large");
      return false;
    }

    if (fileSize > Constants.FILE_SIZE_LARGE_THRESHOLD) {
      int result = Messages.showYesNoDialog(
          String.format(Constants.Message.FILE_LARGE_WARNING, formatFileSize(fileSize)) +
          "\n\nDo you want to continue loading?",
          "Large File Warning",
          Messages.getWarningIcon());
      return result == Messages.YES;
    }

    return true;
  }

  /**
   * Loads a Parquet file into this editor.
   *
   * @param file the Parquet file to load
   */
  public void loadParquetFile(File file) {
    // Track the file being loaded immediately
    this.loadingFile = file;

    // Validate file first
    if (!validateFileForLoading(file)) {
      statusLabel.setText("File loading cancelled.");
      this.loadingFile = null;
      return;
    }

    // Show loading panel
    showLoadingPanel(file);

    // Calculate estimated load time based on file size
    long fileSize = file.length();
    String sizeInfo = formatFileSize(fileSize);

    try {
      SwingWorker<ParquetData, String> worker =
          new SwingWorker<ParquetData, String>() {
            @Override
            protected ParquetData doInBackground() throws Exception {
              // Step 1: Reading schema
              publish(Constants.Message.LOADING_READING_SCHEMA);
              Thread.sleep(100); // Brief pause for UI update

              // Step 2: Reading data
              publish(Constants.Message.LOADING_READING_DATA);
              ParquetData data = editorService.loadParquetFile(file);

              // Step 3: Preparing table
              publish(Constants.Message.LOADING_PREPARING_TABLE);

              return data;
            }

            @Override
            protected void process(java.util.List<String> chunks) {
              // Update loading status with the latest message
              if (!chunks.isEmpty()) {
                String latestMessage = chunks.get(chunks.size() - 1);
                updateLoadingStatus(latestMessage);
              }
            }

            @Override
            protected void done() {
              try {
                ParquetData data = get();
                tableModel = editorService.initializeTableModel(data);
                dataTable.setModel(tableModel);
                tableModel.addTableModelListener(dirtyListener);
                dirty = false;

                // Configure cell editor for all columns (especially needed for DATE and TIMESTAMP)
                configureCellEditors();

                rowSorter = new TableRowSorter<>(tableModel);
                dataTable.setRowSorter(rowSorter);

                updateButtonStates(true);

                // Switch to data panel
                showDataPanel();
                updateStatusLabel();

                int rowCount = data.getRows().size();
                int colCount = data.getColumnNames().size();
                LOGGER.info("Loaded: " + file.getName() + " (" + rowCount + " rows, " + colCount + " columns)");

                // Show success message in status
                statusLabel.setText(String.format("Loaded: %s | %d rows, %d columns | Size: %s",
                    file.getName(), rowCount, colCount, sizeInfo));

                writeOriginalSchemaInPanel(data.getColumnNames(), data.getColumnTypes());
                resetSchemaComponents();

                // Clear loadingFile since service now has the file
                loadingFile = null;

              } catch (java.util.concurrent.CancellationException e) {
                LOGGER.info("Loading cancelled: " + file.getName());
                showDataPanel();
                statusLabel.setText("Loading cancelled.");
                loadingFile = null;

              } catch (Exception e) {
                LOGGER.error("Error loading Parquet file", e);

                // Show error panel
                showDataPanel();

                // Extract root cause message
                String errorMessage = e.getMessage();
                if (e.getCause() != null) {
                  errorMessage = e.getCause().getMessage();
                }

                Messages.showErrorDialog(
                    String.format(Constants.Message.ERROR_LOADING_FILE, errorMessage),
                    "Error Loading File");
                statusLabel.setText("Error loading file: " + file.getName());
                loadingFile = null;
              }
            }
          };
      worker.execute();

    } catch (Exception e) {
      LOGGER.error("Error loading Parquet file", e);
      showDataPanel();
      Messages.showErrorDialog(
          String.format(Constants.Message.ERROR_LOADING_FILE, e.getMessage()),
          "Error");
      statusLabel.setText("Error loading file.");
      loadingFile = null;
    }
  }

  private void resetSchemaComponents(){
      editorService.setNullSchemaTransform();
      editorService.setSchemaFile(null);

      schemaCheckBox.setSelected(false);
      schemaCheckBox.setEnabled(false);

      strictModeCheckBox.setSelected(false);
      strictModeCheckBox.setEnabled(false);
  }

  private void performSearch() {
    if (rowSorter == null || tableModel == null) {
      return;
    }

    String text = searchField.getText();
    if (text.trim().isEmpty()) {
      rowSorter.setRowFilter(null);
    } else {
      final String searchText = text.toLowerCase();
      rowSorter.setRowFilter(
          new RowFilter<TableModel, Integer>() {
            @Override
            public boolean include(Entry<? extends TableModel, ? extends Integer> entry) {
              for (int i = 0; i < entry.getValueCount(); i++) {
                Object value = entry.getValue(i);
                if (value != null && value.toString().toLowerCase().contains(searchText)) {
                  return true;
                }
              }
              return false;
            }
          });
    }
    updateStatusLabel();
  }

  private void addRow() {
    try {
      int newRowIndex = editorService.addRow();
      tableModel = editorService.getTableModel();
      
      // Update UI to show new row
      if (rowSorter != null && dataTable.getRowSorter() != null) {
        int viewIndex = dataTable.convertRowIndexToView(newRowIndex);
        dataTable.setRowSelectionInterval(viewIndex, viewIndex);
        dataTable.scrollRectToVisible(dataTable.getCellRect(viewIndex, 0, true));
      } else {
        dataTable.setRowSelectionInterval(newRowIndex, newRowIndex);
        dataTable.scrollRectToVisible(dataTable.getCellRect(newRowIndex, 0, true));
      }
      
      updateStatusLabel();
    } catch (IllegalStateException e) {
      Messages.showErrorDialog(e.getMessage(), "Error");
    } catch (Exception e) {
      LOGGER.error("Error adding row", e);
      Messages.showErrorDialog("Error adding row: " + e.getMessage(), "Error");
    }
  }

  private void addColumn() {
    try {
      AddColumnDialog dialog = new AddColumnDialog(this);
      if (dialog.showAndGet()) {
        String columnName = dialog.getColumnName();
        String columnType = dialog.getColumnType();

        int newColumnIndex = editorService.addColumn(columnName, columnType);
        tableModel = editorService.getTableModel();

        // Configure cell editor for the new column
        TableCellEditor textEditor = createTextCellEditor();
        if (newColumnIndex >= 0) {
          dataTable.getColumnModel().getColumn(newColumnIndex).setCellEditor(textEditor);

          // Scroll to the new column
          dataTable.scrollRectToVisible(
              dataTable.getCellRect(0, newColumnIndex, true));
          // Select the new column header
          dataTable.getColumnModel().getSelectionModel()
              .setSelectionInterval(newColumnIndex, newColumnIndex);
        }

        updateStatusLabel();
      }
    } catch (IllegalStateException | IllegalArgumentException e) {
      Messages.showErrorDialog(e.getMessage(), "Error");
    } catch (Exception e) {
      LOGGER.error("Error adding column", e);
      Messages.showErrorDialog("Error adding column: " + e.getMessage(), "Error");
    }
  }

  private void deleteSelectedColumn() {
    // Get selected column
    int selectedColumn = dataTable.getSelectedColumn();
    if (selectedColumn < 0) {
      Messages.showInfoMessage("Please select a column to delete.", "Info");
      return;
    }

    // Convert view column index to model column index
    int modelColumnIndex = dataTable.convertColumnIndexToModel(selectedColumn);

    if (modelColumnIndex < 0 || modelColumnIndex >= tableModel.getColumnCount()) {
      Messages.showErrorDialog("Invalid column selection.", "Error");
      return;
    }

    String columnName = editorService.getColumnName(modelColumnIndex);

    // Confirm deletion
    int confirm = Messages.showYesNoDialog(
        "Are you sure you want to delete column '" + columnName + "'?\n" +
        "This action cannot be undone.",
        "Confirm Column Deletion",
        Messages.getQuestionIcon());

    if (confirm == Messages.YES) {
      try {
        editorService.deleteColumn(modelColumnIndex);
        tableModel = editorService.getTableModel();

        // Reconfigure cell editors after column deletion
        configureCellEditors();

        updateStatusLabel();
      } catch (IllegalStateException | IllegalArgumentException e) {
        Messages.showErrorDialog(e.getMessage(), "Error");
      } catch (Exception e) {
        LOGGER.error("Error deleting column", e);
        Messages.showErrorDialog("Error deleting column: " + e.getMessage(), "Error");
      }
    }
  }

  private void deleteSelectedRows() {
    int[] selectedRows = dataTable.getSelectedRows();
    if (selectedRows.length == 0) {
      Messages.showInfoMessage("Please select at least one row to delete.", "Info");
      return;
    }

    int confirm =
        Messages.showYesNoDialog(
            "Are you sure you want to delete " + selectedRows.length + " row(s)?",
            "Confirm Deletion",
            Messages.getQuestionIcon());

    if (confirm == Messages.YES) {
      try {
        // Convert view indices to model indices
        int[] modelIndices = new int[selectedRows.length];
        for (int i = 0; i < selectedRows.length; i++) {
          modelIndices[i] = dataTable.convertRowIndexToModel(selectedRows[i]);
        }

        // Temporarily disable sorter for deletion
        RowSorter<?> currentSorter = dataTable.getRowSorter();
        boolean sorterWasEnabled = currentSorter != null;
        if (sorterWasEnabled) {
          dataTable.setRowSorter(null);
        }

        editorService.deleteRows(modelIndices);
        tableModel = editorService.getTableModel();

        // Re-enable sorter if it was enabled
        if (sorterWasEnabled && rowSorter != null) {
          dataTable.setRowSorter(rowSorter);
        }

        updateStatusLabel();
      } catch (IllegalStateException e) {
        Messages.showErrorDialog(e.getMessage(), "Error");
      } catch (Exception e) {
        if (rowSorter != null && dataTable.getRowSorter() == null) {
          dataTable.setRowSorter(rowSorter);
        }
        LOGGER.error("Error deleting rows", e);
        Messages.showErrorDialog("Error deleting rows: " + e.getMessage(), "Error");
      }
    }
  }

  /**
   * Validates the current Parquet file's types against a schema file.
   */
  private void validateSchemaAgainstFile() {
    if (tableModel == null) {
      Messages.showWarningDialog("Please load a Parquet file first.", "No File Loaded");
      return;
    }

    // Open file chooser for schema file
    JFileChooser fileChooser = new JFileChooser();
    fileChooser.setDialogTitle("Select Schema File to Validate");

    File currentFile = editorService.getCurrentFile();
    if (currentFile != null) {
      fileChooser.setCurrentDirectory(currentFile.getParentFile());
    }

    fileChooser.setFileFilter(new FileFilter() {
      @Override
      public boolean accept(File f) {
        if (f.isDirectory()) return true;
        String name = f.getName().toLowerCase();
        return name.endsWith(".json") || name.endsWith(".schema");
      }

      @Override
      public String getDescription() {
        return "Schema Files (*.json, *.schema)";
      }
    });

    int result = fileChooser.showOpenDialog(this);
    if (result != JFileChooser.APPROVE_OPTION) {
      return;
    }

    File schemaFile = fileChooser.getSelectedFile();

    try {
      // Get column names and types from the table model (clean names, not HTML formatted)
      List<String> columnNames = tableModel.getColumnNames();
      List<String> columnTypes = tableModel.getColumnTypes();

      LOGGER.info("Validating schema with columns: " + columnNames);
      LOGGER.info("Column types: " + columnTypes);

      // Perform validation
      SchemaValidationService validationService = new SchemaValidationService();
      SchemaValidationResult validationResult = validationService.validate(schemaFile, columnNames, columnTypes);

      // Create callback for fix action
      final File finalSchemaFile = schemaFile;
      Runnable fixCallback = () -> {
        fixTypesWithSchema(finalSchemaFile, validationResult);
      };

      // Show results dialog with fix callback
      SchemaValidationDialog dialog = new SchemaValidationDialog(
          validationResult,
          schemaFile.getName(),
          schemaFile,
          fixCallback
      );
      dialog.show();

      // Log result
      if (validationResult.isFullyValid()) {
        LOGGER.info("Schema validation passed for: " + schemaFile.getName());
      } else {
        LOGGER.warn("Schema validation found issues: " + validationResult.getErrorCount() + " errors, " +
                    validationResult.getWarningCount() + " warnings");
      }

    } catch (Exception e) {
      LOGGER.error("Error validating schema", e);
      Messages.showErrorDialog(
          "Error validating schema: " + e.getMessage(),
          "Validation Error"
      );
    }
  }

  /**
   * Fixes type mismatches by saving the Parquet file with the schema types.
   * Overwrites the original file.
   */
  private void fixTypesWithSchema(File schemaFile, SchemaValidationResult validationResult) {
    LOGGER.info("=== FIX TYPES WITH SCHEMA STARTED ===");
    LOGGER.info("Schema file: " + schemaFile.getAbsolutePath());
    LOGGER.info("Type mismatches count: " + validationResult.getTypeMismatchColumns().size());
    LOGGER.info("Missing columns count: " + validationResult.getMissingInParquet().size());

    try {
      File currentFile = editorService.getCurrentFile();
      if (currentFile == null) {
        LOGGER.error("No current file loaded");
        Messages.showErrorDialog("No Parquet file is currently loaded.", "Error");
        return;
      }

      LOGGER.info("Current Parquet file: " + currentFile.getAbsolutePath());

      // Confirm overwrite
      int confirm = Messages.showYesNoDialog(
          "This will overwrite the original file:\n" + currentFile.getAbsolutePath() +
          "\n\nThe following columns will be converted to match the schema:\n" +
          formatMismatchList(validationResult) +
          "\n\nDo you want to continue?",
          "Confirm Fix Types",
          Messages.getQuestionIcon()
      );

      if (confirm != Messages.YES) {
        LOGGER.info("User cancelled fix operation");
        return;
      }

      LOGGER.info("User confirmed fix operation");

      // Load the schema and save with type conversion
      LOGGER.info("Setting schema file in editor service...");
      editorService.setSchemaFile(schemaFile);

      // Get table data
      List<String> columnNames = tableModel.getColumnNames();
      LOGGER.info("Column names from table model: " + columnNames);
      LOGGER.info("Column count: " + columnNames.size());

      List<List<Object>> rows = new ArrayList<>();
      for (int i = 0; i < tableModel.getRowCount(); i++) {
        List<Object> row = new ArrayList<>();
        for (int j = 0; j < tableModel.getColumnCount(); j++) {
          row.add(tableModel.getValueAt(i, j));
        }
        rows.add(row);
      }
      LOGGER.info("Row count: " + rows.size());

      // Save to original path with schema types
      LOGGER.info("Calling saveParquetFileWithSchema...");
      editorService.saveParquetFileWithSchema(currentFile, columnNames, rows);
      LOGGER.info("saveParquetFileWithSchema completed successfully");

      Messages.showInfoMessage(
          "File saved successfully with corrected types:\n" + currentFile.getAbsolutePath(),
          "Fix Complete"
      );

      LOGGER.info("Fixed types and saved file: " + currentFile.getAbsolutePath());

      // Reload the file to show updated types
      LOGGER.info("Reloading file to show updated types...");
      loadParquetFile(currentFile);
      LOGGER.info("=== FIX TYPES WITH SCHEMA COMPLETED ===");

    } catch (Exception e) {
      LOGGER.error("Error fixing types: " + e.getMessage(), e);
      Messages.showErrorDialog(
          "Error fixing types: " + e.getMessage(),
          "Fix Error"
      );
    }
  }

  /**
   * Formats the list of issues (type mismatches and missing columns) for display.
   */
  private String formatMismatchList(SchemaValidationResult result) {
    StringBuilder sb = new StringBuilder();

    // Type mismatches
    if (!result.getTypeMismatchColumns().isEmpty()) {
      sb.append("Type conversions:\n");
      for (SchemaValidationResult.ColumnValidation col : result.getTypeMismatchColumns()) {
        sb.append("  • ").append(col.getColumnName())
          .append(": ").append(col.getActualType())
          .append(" → ").append(col.getExpectedType())
          .append("\n");
      }
    }

    // Missing columns (will be added with null values)
    if (!result.getMissingInParquet().isEmpty()) {
      if (sb.length() > 0) sb.append("\n");
      sb.append("Columns to add (with null values):\n");
      for (String col : result.getMissingInParquet()) {
        sb.append("  • ").append(col).append("\n");
      }
    }

    return sb.toString();
  }

  private void saveAsParquet() {
    try {
      JFileChooser fileChooser = new JFileChooser();
      fileChooser.setDialogTitle("Save As Parquet");
      File currentFile = editorService.getCurrentFile();
      if (currentFile != null) {
        fileChooser.setCurrentDirectory(currentFile.getParentFile());
      }
      fileChooser.setFileFilter(
          new FileFilter() {
            @Override
            public boolean accept(File f) {
              return f.isDirectory() || f.getName().toLowerCase().endsWith(".parquet");
            }

            @Override
            public String getDescription() {
              return "Parquet Files (*.parquet)";
            }
          });

      int result = fileChooser.showSaveDialog(this);
      if (result == JFileChooser.APPROVE_OPTION) {
        File selectedFile = fileChooser.getSelectedFile();
        File outputFile;
        if (!selectedFile.getName().toLowerCase().endsWith(".parquet")) {
          outputFile = new File(selectedFile.getPath() + ".parquet");
        } else {
          outputFile = selectedFile;
        }

        if (outputFile.exists()) {
          int overwrite =
              Messages.showYesNoDialog(
                  "File already exists. Overwrite?",
                  "Confirm Overwrite",
                  Messages.getQuestionIcon());
          if (overwrite != Messages.YES) {
            return;
          }
        }

        statusLabel.setText("Saving file...");
        SwingWorker<Void, Void> saveWorker =
            new SwingWorker<Void, Void>() {
              @Override
              protected Void doInBackground() throws Exception {
                ParquetData data = tableModel.toParquetData();
                if(schemaCheckBox.isSelected()){
                    if( !isValidSchemaFile(editorService.getCurrentSchemaFile()) ) throw new Exception("The schema is not valid.");

                    if(strictModeCheckBox.isSelected()){
                        if(!complyStrictMode()) throw new Exception(Constants.Message.SCHEMA_AND_PARQUET_NOT_SAME_COLUMNS);
                        LOGGER.info("writing parquet with other schema (strict mode)...");
                    }
                    LOGGER.warn("Saving with other schema....");
                    editorService.saveParquetFile(outputFile, editorService.getSchemaStructureTransform());
                }else{
                    LOGGER.warn("Saving with same schema...");
                    editorService.saveParquetFile(outputFile, null);
                }
                  LOGGER.info("The parquet was written.");

                return null;
              }

              @Override
              protected void done() {
                try {
                  get();
                  markSaved();
                  statusLabel.setText("File saved: " + outputFile.getName());
                  Messages.showInfoMessage(
                      "File saved successfully: " + outputFile.getPath(), "Success");
                } catch (Exception e) {
                  LOGGER.error("Error saving Parquet file", e);
                  Messages.showErrorDialog("Error saving file: " + e.getCause().getMessage(), "Error");
                  statusLabel.setText("Error saving file.");
                }
              }
            };
        saveWorker.execute();
      }
    } catch (IllegalStateException e) {
      Messages.showErrorDialog(e.getMessage(), "Error");
    } catch (Exception e) {
      LOGGER.error("Error saving Parquet file", e);
      Messages.showErrorDialog("Error saving file: " + e.getMessage(), "Error");
    }
  }

  private TableCellEditor createTextCellEditor() {
    // Configure a text field editor for all columns
    // This is especially important for DATE and TIMESTAMP columns
    // which don't have default editors in JTable
    return new DefaultCellEditor(new JTextField()) {
      @Override
      public Component getTableCellEditorComponent(JTable table, Object value,
          boolean isSelected, int row, int column) {
        Component component = super.getTableCellEditorComponent(table, value, isSelected, row, column);
        JTextField textField = (JTextField) component;

        // Convert value to String for display
        if (value == null) {
          textField.setText("");
        } else if (value instanceof LocalDate) {
          textField.setText(value.toString());
        } else if (value instanceof LocalDateTime) {
          textField.setText(value.toString());
        } else {
          textField.setText(value.toString());
        }

        return component;
      }

      @Override
      public Object getCellEditorValue() {
        // Get the value from the text field
        JTextField textField = (JTextField) getComponent();
        String text = textField.getText();
        // Return as String - the model will handle conversion
        return text;
      }
    };
  }

  private void configureCellEditors() {
    TableCellEditor textEditor = createTextCellEditor();

    // Apply the editor to all columns
    for (int i = 0; i < tableModel.getColumnCount(); i++) {
      dataTable.getColumnModel().getColumn(i).setCellEditor(textEditor);
    }
  }

  private void updateStatusLabel() {
    if (tableModel != null && editorService.hasFile()) {
      File currentFile = editorService.getCurrentFile();
      int rowCount = editorService.getRowCount();
      int filteredCount =
          rowSorter != null && rowSorter.getRowFilter() != null
              ? rowSorter.getViewRowCount()
              : rowCount;
      if (filteredCount < rowCount) {
        statusLabel.setText(
            String.format(
                "Rows: %d (filtered: %d) | File: %s",
                rowCount, filteredCount, currentFile.getName()));
      } else {
        statusLabel.setText(
            String.format("Rows: %d | File: %s", rowCount, currentFile.getName()));
      }
    }
  }
}

