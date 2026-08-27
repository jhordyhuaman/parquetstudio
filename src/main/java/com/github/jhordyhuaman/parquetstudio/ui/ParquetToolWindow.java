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
import com.github.jhordyhuaman.parquetstudio.model.SchemaStructure;
import com.github.jhordyhuaman.parquetstudio.service.DuckDBParquetService;
import com.github.jhordyhuaman.parquetstudio.service.ParquetOptimizationService;
import com.github.jhordyhuaman.parquetstudio.service.RemoteSchemaService;
import com.github.jhordyhuaman.parquetstudio.service.SyntheticDataGenerator;
import com.github.jhordyhuaman.parquetstudio.service.SyntheticDataGenerator.GenerationResult;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.IconLoader;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import javax.swing.*;
import javax.swing.filechooser.FileFilter;

/**
 * Main tool window panel for Parquet Studio with tab support for multiple files.
 */
public class ParquetToolWindow extends JPanel {
  private static final Logger LOGGER = Logger.getInstance(ParquetToolWindow.class);

  private final ParquetOptimizationService optimizationService = new ParquetOptimizationService();
  private final RemoteSchemaService remoteSchemaService = new RemoteSchemaService();
  private final SyntheticDataGenerator syntheticDataGenerator = new SyntheticDataGenerator();
  private final DuckDBParquetService duckDBParquetService = new DuckDBParquetService();

  private JTabbedPane tabbedPane;
  private JPanel welcomePanel;
  private JPanel contentPanel;
  private JButton openButton;
  private JButton optimizeButton;
  private JButton generateDataButton;

  public ParquetToolWindow() {
    initializeUI();
  }

  private void initializeUI() {
    setLayout(new BorderLayout());

    // Toolbar
    JPanel toolbarPanel = createToolbar();
    add(toolbarPanel, BorderLayout.NORTH);

    // Content panel with CardLayout to switch between welcome and tabs
    contentPanel = new JPanel(new java.awt.CardLayout());

    // Welcome panel (shown when no files are open)
    welcomePanel = createWelcomePanel();
    contentPanel.add(welcomePanel, "WELCOME");

    // Tabbed pane for multiple files
    tabbedPane = new JTabbedPane();
    tabbedPane.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
    
    // Add mouse listener for right-click to close tabs and click on close area
    tabbedPane.addMouseListener(new MouseAdapter() {
      @Override
      public void mouseClicked(MouseEvent e) {
        if (SwingUtilities.isRightMouseButton(e)) {
          int tabIndex = tabbedPane.indexAtLocation(e.getX(), e.getY());
          if (tabIndex >= 0) {
            closeTab(tabIndex);
          }
        } else if (SwingUtilities.isLeftMouseButton(e)) {
          // Check if click is in the close area (right side of tab)
          int tabIndex = tabbedPane.indexAtLocation(e.getX(), e.getY());
          if (tabIndex >= 0) {
            java.awt.Rectangle tabBounds = tabbedPane.getBoundsAt(tabIndex);
            // Close area is the right 20 pixels of the tab
            int closeAreaStart = tabBounds.x + tabBounds.width - 20;
            if (e.getX() >= closeAreaStart && e.getX() <= tabBounds.x + tabBounds.width) {
              closeTab(tabIndex);
            }
          }
        }
      }
    });
    
    contentPanel.add(tabbedPane, "TABS");
    add(contentPanel, BorderLayout.CENTER);

    // Show welcome panel initially
    showWelcomePanel();
  }

  /**
   * Creates the welcome panel shown when no files are open.
   */
  private JPanel createWelcomePanel() {
    JPanel panel = new JPanel(new java.awt.GridBagLayout());
    panel.setBackground(UIManager.getColor("Panel.background"));

    java.awt.GridBagConstraints gbc = new java.awt.GridBagConstraints();
    gbc.gridx = 0;
    gbc.gridy = 0;
    gbc.insets = new java.awt.Insets(10, 10, 10, 10);
    gbc.anchor = java.awt.GridBagConstraints.CENTER;

    // Icon
    JLabel iconLabel = new JLabel();
    iconLabel.setIcon(IconLoader.getIcon("/icons/parquet_studio.svg", ParquetToolWindow.class));
    panel.add(iconLabel, gbc);

    // Title
    gbc.gridy = 1;
    JLabel titleLabel = new JLabel("Parquet Studio");
    titleLabel.setFont(titleLabel.getFont().deriveFont(java.awt.Font.BOLD, 18f));
    panel.add(titleLabel, gbc);

    // Subtitle
    gbc.gridy = 2;
    JLabel subtitleLabel = new JLabel("Professional CRUD editor for Parquet files");
    subtitleLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
    panel.add(subtitleLabel, gbc);

    // Instructions
    gbc.gridy = 3;
    gbc.insets = new java.awt.Insets(20, 10, 5, 10);
    JLabel instructionLabel = new JLabel("<html><center>Double-click a <b>.parquet</b> file to open it<br>or use the Open button above</center></html>");
    instructionLabel.setHorizontalAlignment(SwingConstants.CENTER);
    panel.add(instructionLabel, gbc);

    // Open button
    gbc.gridy = 4;
    gbc.insets = new java.awt.Insets(15, 10, 10, 10);
    JButton openFileButton = new JButton("Open Parquet File");
    openFileButton.addActionListener(e -> openParquetFile());
    panel.add(openFileButton, gbc);

    // Tips
    gbc.gridy = 5;
    gbc.insets = new java.awt.Insets(20, 10, 10, 10);
    JLabel tipsLabel = new JLabel("<html><center><small>Tip: Right-click on a tab to close it</small></center></html>");
    tipsLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
    panel.add(tipsLabel, gbc);

    return panel;
  }

  /**
   * Shows the welcome panel.
   */
  private void showWelcomePanel() {
    java.awt.CardLayout cl = (java.awt.CardLayout) contentPanel.getLayout();
    cl.show(contentPanel, "WELCOME");
  }

  /**
   * Shows the tabs panel.
   */
  private void showTabsPanel() {
    java.awt.CardLayout cl = (java.awt.CardLayout) contentPanel.getLayout();
    cl.show(contentPanel, "TABS");
  }

  /**
   * Updates the view based on whether there are open tabs.
   */
  private void updateView() {
    if (tabbedPane.getTabCount() == 0) {
      showWelcomePanel();
    } else {
      showTabsPanel();
    }
  }

  private JPanel createToolbar() {
    JPanel toolbar = new JPanel();
    toolbar.setLayout(new BoxLayout(toolbar, BoxLayout.X_AXIS));
    toolbar.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

    // Open button with icon
    openButton = new JButton(IconLoader.getIcon("/icons/ui/sqlFolder/sqlFolder.svg", ParquetEditorPanel.class));
    openButton.setToolTipText("Open Parquet File");
    openButton.addActionListener(e -> openParquetFile());
    toolbar.add(openButton);

    // Optimize button - always available; works with no file open (Consolidate)
    optimizeButton =
        new JButton(IconLoader.getIcon("/icons/ui/optimize/optimize.svg", ParquetEditorPanel.class));
    optimizeButton.setToolTipText("Optimize file…");
    optimizeButton.addActionListener(e -> openOptimizeDialog());
    toolbar.add(optimizeButton);

    // Generate Data button - always available; works with no file open
    generateDataButton =
        new JButton(
            "Generate Data",
            IconLoader.getIcon("/icons/ui/generateData/generateData.svg", ParquetEditorPanel.class));
    generateDataButton.setToolTipText("Generate a new Parquet file from a schema (local file or URL)");
    generateDataButton.addActionListener(e -> openGenerateDataDialog());
    toolbar.add(generateDataButton);

    return toolbar;
  }

  /**
   * Opens the Generate Data dialog and, on confirmation, runs the fetch/parse → generate →
   * save → open pipeline on a background worker.
   */
  private void openGenerateDataDialog() {
    GenerateDataDialog dialog = new GenerateDataDialog(this);
    if (!dialog.showAndGet()) {
      return;
    }

    boolean isLocal = dialog.isLocalFileSource();
    File schemaFile = dialog.getSchemaFile();
    String url = dialog.getUrl();
    String token = dialog.getToken();
    RemoteSchemaService.TokenStyle tokenStyle = dialog.getTokenStyle();
    int rowCount = dialog.getRowCount();
    Long seed = dialog.getSeed();
    double nullRatio = dialog.getNullRatio();
    File targetFile = dialog.getTargetFile();

    String sourceLabel = isLocal ? schemaFile.getName() : urlWithoutQuery(url);

    SwingWorker<GenerationResult, Void> worker = new SwingWorker<GenerationResult, Void>() {
      @Override
      protected GenerationResult doInBackground() throws Exception {
        SchemaStructure schemaStructure;
        if (isLocal) {
          try {
            schemaStructure = SchemaStructure.schemaFromFile(schemaFile.getAbsolutePath());
          } catch (Exception e) {
            throw new IOException("Failed to parse schema from \"" + schemaFile.getName() + "\": " + e.getMessage(), e);
          }
        } else {
          String body;
          try {
            body = remoteSchemaService.fetchSchema(url, token, tokenStyle);
          } catch (Exception e) {
            throw new IOException("Failed to fetch schema from " + sourceLabel + ": " + e.getMessage(), e);
          }
          File tempSchemaFile = Files.createTempFile("parquetstudio-schema-", ".json").toFile();
          tempSchemaFile.deleteOnExit();
          try (FileWriter writer = new FileWriter(tempSchemaFile)) {
            writer.write(body);
          }
          try {
            schemaStructure = SchemaStructure.schemaFromFile(tempSchemaFile.getAbsolutePath());
          } catch (Exception e) {
            throw new IOException("Failed to parse schema from " + sourceLabel + ": " + e.getMessage(), e);
          } finally {
            tempSchemaFile.delete();
          }
        }
        if (schemaStructure == null || schemaStructure.fields == null || schemaStructure.fields.isEmpty()) {
          throw new IOException(
              "The schema source does not contain a valid schema (missing 'fields'): " + sourceLabel);
        }

        schemaStructure.changesTypesFields();

        List<String> columnNames = new ArrayList<>();
        List<String> columnTypes = new ArrayList<>();
        for (var field : schemaStructure.fields) {
          columnNames.add(field.name);
          columnTypes.add(String.valueOf(field.type));
        }

        GenerationResult result =
            syntheticDataGenerator.generate(columnNames, columnTypes, rowCount, seed, nullRatio);
        duckDBParquetService.saveParquet(targetFile, result.getData());
        return result;
      }

      @Override
      protected void done() {
        try {
          GenerationResult result = get();
          ParquetData data = result.getData();
          StringBuilder message = new StringBuilder(
              String.format("Generated %d rows → %s", data.getRows().size(), targetFile.getName()));
          if (!result.getWarnings().isEmpty()) {
            message.append("\n\nWarnings:");
            for (String warning : result.getWarnings()) {
              message.append("\n- ").append(warning);
            }
          }
          Messages.showInfoMessage(message.toString(), "Generate Data Complete");
          reloadOrOpen(targetFile);
        } catch (Exception e) {
          LOGGER.error("Error generating synthetic data", e);
          String errorMessage = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
          Messages.showErrorDialog("Error generating data: " + errorMessage, "Error");
        }
      }
    };
    worker.execute();
  }

  private static String urlWithoutQuery(String url) {
    int idx = url.indexOf('?');
    return idx >= 0 ? url.substring(0, idx) : url;
  }

  /**
   * Opens the Optimize dialog. Compact/Fragment are only selectable when the current tab has a
   * loaded ParquetEditorPanel with a file; Consolidate is always selectable.
   */
  private void openOptimizeDialog() {
    ParquetEditorPanel activePanel = getActiveEditorPanel();
    boolean hasFile = activePanel != null && activePanel.hasFile();
    OptimizeFileDialog dialog = new OptimizeFileDialog(this, hasFile, hasFile);
    if (!dialog.showAndGet()) {
      return;
    }

    switch (dialog.getOperation()) {
      case COMPACT:
      case FRAGMENT:
        // Compact/Fragment are only selectable when an editor panel with a file is active;
        // route execution there so the compact/fragment workers reuse its own state.
        if (activePanel != null) {
          activePanel.runOptimize(dialog);
        }
        break;
      case CONSOLIDATE:
        runConsolidate(dialog.getConsolidateSourceDir(), dialog.getConsolidateOutputFile());
        break;
      default:
        break;
    }
  }

  private ParquetEditorPanel getActiveEditorPanel() {
    if (tabbedPane == null) {
      return null;
    }
    int index = tabbedPane.getSelectedIndex();
    if (index < 0) {
      return null;
    }
    Component component = tabbedPane.getComponentAt(index);
    return component instanceof ParquetEditorPanel ? (ParquetEditorPanel) component : null;
  }

  /**
   * Consolidates the parquet files found in {@code sourceDir} into {@code outputFile}, then
   * opens the result in a new tab. This is the single consolidate implementation used by both
   * this tool window's own Optimize button and any {@link ParquetEditorPanel}'s Optimize dialog
   * (via {@link #runConsolidate}), so the result always ends up opened in a tab.
   */
  public void runConsolidate(File sourceDir, File outputFile) {
    runConsolidate(sourceDir, outputFile, null);
  }

  /**
   * Consolidates the parquet files found in {@code sourceDir} into {@code outputFile}, then
   * opens the result in a new tab (or reloads it if already open). This is the single
   * consolidate implementation used by both this tool window's own Optimize button and any
   * {@link ParquetEditorPanel}'s Optimize dialog.
   *
   * @param sourceDir the directory containing the parquet files to consolidate
   * @param outputFile the destination file for the consolidated output
   * @param onComplete optional callback invoked on the EDT once the operation finishes
   *     (success or failure), useful for resetting an initiating panel's status label
   */
  public void runConsolidate(File sourceDir, File outputFile, Runnable onComplete) {
    List<File> sources = new java.util.ArrayList<>(optimizationService.listParquetFiles(sourceDir));

    String outputCanonicalPath = getNormalizedPath(outputFile);
    sources.removeIf(f -> getNormalizedPath(f).equals(outputCanonicalPath));

    if (sources.size() < 2) {
      Messages.showErrorDialog(
          "The source directory must contain at least 2 parquet files to consolidate.", "Error");
      if (onComplete != null) {
        onComplete.run();
      }
      return;
    }

    if (outputFile.exists()) {
      int choice = JOptionPane.showConfirmDialog(
          this,
          "\"" + outputFile.getName() + "\" already exists. Overwrite it?",
          "Confirm Overwrite",
          JOptionPane.YES_NO_OPTION,
          JOptionPane.WARNING_MESSAGE);
      if (choice != JOptionPane.YES_OPTION) {
        if (onComplete != null) {
          onComplete.run();
        }
        return;
      }
    }

    List<File> finalSources = sources;
    SwingWorker<Long, Void> consolidateWorker =
        new SwingWorker<Long, Void>() {
          @Override
          protected Long doInBackground() throws Exception {
            return optimizationService.consolidate(finalSources, outputFile);
          }

          @Override
          protected void done() {
            try {
              get();
              String message = String.format(
                  "Consolidated %d files → %s (%s)",
                  finalSources.size(), outputFile.getName(),
                  ParquetEditorPanel.formatFileSize(outputFile.length()));
              Messages.showInfoMessage(message, "Consolidate Complete");
              reloadOrOpen(outputFile);
            } catch (Exception e) {
              LOGGER.error("Error consolidating Parquet files", e);
              String errorMessage = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
              Messages.showErrorDialog("Error consolidating files: " + errorMessage, "Error");
            } finally {
              if (onComplete != null) {
                onComplete.run();
              }
            }
          }
        };
    consolidateWorker.execute();
  }

  /**
   * Opens {@code file} in a new tab, or reloads it in place if it is already open in a tab
   * (e.g. after consolidating onto a file that is currently displayed), then selects that tab.
   *
   * @param file the file to open or reload
   */
  void reloadOrOpen(File file) {
    if (!SwingUtilities.isEventDispatchThread()) {
      SwingUtilities.invokeLater(() -> reloadOrOpen(file));
      return;
    }
    String filePath = getNormalizedPath(file);
    int existing = findTabIndexForPath(filePath);
    if (existing >= 0) {
      ParquetEditorPanel panel = getEditorPanelAt(existing);
      if (panel != null) {
        panel.loadParquetFile(file);
      }
      showTabsPanel();
      tabbedPane.setSelectedIndex(existing);
      return;
    }
    openFileInTab(file);
  }



  private void openParquetFile() {
    JFileChooser fileChooser = new JFileChooser();
    fileChooser.setDialogTitle("Open Parquet File");
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

    int result = fileChooser.showOpenDialog(this);
    if (result == JFileChooser.APPROVE_OPTION) {
      File selectedFile = fileChooser.getSelectedFile();
      openParquetFileInTab(selectedFile);
    }
  }

  /**
   * Gets the normalized (canonical) path of a file, falling back to absolute path if needed.
   *
   * @param file the file to get the path for
   * @return the normalized path
   */
  private String getNormalizedPath(File file) {
    try {
      return file.getCanonicalPath();
    } catch (IOException e) {
      return file.getAbsolutePath();
    }
  }

  /**
   * Opens a Parquet file in a new tab. If the file is already open, switches to that tab.
   *
   * @param file the Parquet file to open
   */
  private void openParquetFileInTab(File file) {
    if (!SwingUtilities.isEventDispatchThread()) {
      SwingUtilities.invokeLater(() -> openParquetFileInTab(file));
      return;
    }
    String filePath = getNormalizedPath(file);

    int existing = findTabIndexForPath(filePath);
    if (existing >= 0) {
      showTabsPanel();
      tabbedPane.setSelectedIndex(existing);
      return;
    }

    ParquetEditorPanel editorPanel = new ParquetEditorPanel();
    showTabsPanel();
    tabbedPane.addTab(file.getName() + "  ×", null, editorPanel, file.getAbsolutePath());
    tabbedPane.setSelectedIndex(tabbedPane.getTabCount() - 1);
    // Load AFTER the tab exists so failures render inside the tab, not before it.
    try {
      editorPanel.loadParquetFile(file);
      LOGGER.info("Opened file in new tab: " + file.getName());
    } catch (Exception e) {
      tabbedPane.remove(editorPanel);
      updateView();
      LOGGER.error("Error opening file: " + file.getName(), e);
      Messages.showErrorDialog(
          String.format(Constants.Message.ERROR_LOADING_FILE, e.getMessage()), "Error");
    }
  }

  private int findTabIndexForPath(String normalizedPath) {
    for (int i = 0; i < tabbedPane.getTabCount(); i++) {
      Component component = tabbedPane.getComponentAt(i);
      if (component instanceof ParquetEditorPanel) {
        ParquetEditorPanel panel = (ParquetEditorPanel) component;
        File current = panel.hasFile() ? panel.getCurrentFile()
            : panel.getLoadingOrCurrentFile();
        if (current != null && getNormalizedPath(current).equals(normalizedPath)) {
          return i;
        }
      }
    }
    return -1;
  }

  /**
   * Closes a tab at the specified index.
   *
   * @param tabIndex the index of the tab to close
   */
  private void closeTab(int tabIndex) {
    if (tabIndex < 0 || tabIndex >= tabbedPane.getTabCount()) {
      return;
    }

    Component component = tabbedPane.getComponentAt(tabIndex);
    if (component instanceof ParquetEditorPanel) {
      ParquetEditorPanel panel = (ParquetEditorPanel) component;

      if (panel.isDirty()) {
        int choice = javax.swing.JOptionPane.showConfirmDialog(
            this,
            "\"" + panel.getDisplayName() + "\" has unsaved changes. Close anyway?",
            "Unsaved Changes",
            javax.swing.JOptionPane.YES_NO_OPTION,
            javax.swing.JOptionPane.WARNING_MESSAGE);
        if (choice != javax.swing.JOptionPane.YES_OPTION) {
          return;
        }
      }

      // Remove tab
      tabbedPane.removeTabAt(tabIndex);

      // Update tab components for remaining tabs (indices may have changed)
      updateTabComponents();

      // Update view (show welcome panel if no tabs left)
      updateView();

      LOGGER.info("Closed tab: " + panel.getDisplayName());
    }
  }

  /**
   * Updates tab titles after tab removal to ensure close indicator is present.
   */
  private void updateTabComponents() {
    // Update titles to include close indicator if not already present
    for (int i = 0; i < tabbedPane.getTabCount(); i++) {
      String currentTitle = tabbedPane.getTitleAt(i);
      if (!currentTitle.endsWith("  ×")) {
        // Remove any existing close indicator and add new one
        String baseTitle = currentTitle.replace("  ×", "").trim();
        tabbedPane.setTitleAt(i, baseTitle + "  ×");
      }
    }
  }

  /**
   * Gets the number of open tabs.
   * Useful for testing.
   *
   * @return the number of tabs
   */
  public int getTabCount() {
    return tabbedPane != null ? tabbedPane.getTabCount() : 0;
  }

  /**
   * Gets the currently selected tab index.
   * Useful for testing.
   *
   * @return the selected tab index, or -1 if no tabs
   */
  public int getSelectedTabIndex() {
    return tabbedPane != null ? tabbedPane.getSelectedIndex() : -1;
  }

  /**
   * Gets the editor panel at the specified tab index.
   * Useful for testing.
   *
   * @param tabIndex the tab index
   * @return the ParquetEditorPanel, or null if invalid index
   */
  public ParquetEditorPanel getEditorPanelAt(int tabIndex) {
    if (tabbedPane == null || tabIndex < 0 || tabIndex >= tabbedPane.getTabCount()) {
      return null;
    }
    Component component = tabbedPane.getComponentAt(tabIndex);
    if (component instanceof ParquetEditorPanel) {
      return (ParquetEditorPanel) component;
    }
    return null;
  }

  /**
   * Opens a Parquet file in a tab programmatically.
   * Useful for testing.
   *
   * @param file the file to open
   */
  public void openFileInTab(File file) {
    openParquetFileInTab(file);
  }

}

