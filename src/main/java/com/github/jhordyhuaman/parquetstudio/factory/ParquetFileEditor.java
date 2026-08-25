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
package com.github.jhordyhuaman.parquetstudio.factory;

import com.github.jhordyhuaman.parquetstudio.Constants;
import com.github.jhordyhuaman.parquetstudio.service.ParquetStudioWindowService;
import com.github.jhordyhuaman.parquetstudio.ui.ParquetToolWindow;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorLocation;
import com.intellij.openapi.fileEditor.FileEditorState;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.UserDataHolderBase;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import java.awt.*;
import java.beans.PropertyChangeListener;
import java.io.File;
import javax.swing.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * File editor for Parquet files.
 * Opens the Parquet Studio tool window and loads the file when a .parquet file is opened.
 */
public class ParquetFileEditor extends UserDataHolderBase implements FileEditor {
  private static final Logger LOGGER = Logger.getInstance(ParquetFileEditor.class);

  private final Project project;
  private final VirtualFile file;
  private final JPanel loadingPanel;

  public ParquetFileEditor(@NotNull Project project, @NotNull VirtualFile file) {
    this.project = project;
    this.file = file;
    this.loadingPanel = createLoadingPanel();

    // Validate file before opening
    if (validateFile()) {
      // Open Parquet Studio tool window and load the file
      openInParquetStudio();
    }
  }

  /**
   * Validates the file before opening.
   * @return true if file is valid, false otherwise
   */
  private boolean validateFile() {
    File physicalFile = new File(file.getPath());

    // Check if file exists
    if (!physicalFile.exists()) {
      showErrorNotification(String.format(Constants.Message.ERROR_FILE_NOT_FOUND, file.getName()));
      return false;
    }

    // Check if file is readable
    if (!physicalFile.canRead()) {
      showErrorNotification(String.format(Constants.Message.ERROR_FILE_NOT_READABLE, file.getName()));
      return false;
    }

    // Check file size
    long fileSize = physicalFile.length();

    if (fileSize > Constants.FILE_SIZE_MAX_THRESHOLD) {
      showErrorNotification(Constants.Message.FILE_TOO_LARGE);
      return false;
    }

    if (fileSize > Constants.FILE_SIZE_LARGE_THRESHOLD) {
      showWarningNotification(String.format(Constants.Message.FILE_LARGE_WARNING, formatFileSize(fileSize)));
    } else if (fileSize > Constants.FILE_SIZE_WARNING_THRESHOLD) {
      showInfoNotification(String.format(Constants.Message.FILE_SIZE_WARNING, formatFileSize(fileSize)));
    }

    return true;
  }

  /**
   * Creates a loading panel to show while the file is being opened.
   */
  private JPanel createLoadingPanel() {
    JPanel panel = new JPanel(new GridBagLayout());
    panel.setBackground(UIManager.getColor("Panel.background"));

    GridBagConstraints gbc = new GridBagConstraints();
    gbc.gridx = 0;
    gbc.gridy = 0;
    gbc.insets = new Insets(10, 10, 10, 10);

    // Loading icon/spinner
    JLabel iconLabel = new JLabel();
    iconLabel.setIcon(UIManager.getIcon("OptionPane.informationIcon"));
    panel.add(iconLabel, gbc);

    gbc.gridy = 1;
    JLabel loadingLabel = new JLabel(Constants.Message.OPENED_IN_TOOL_WINDOW);
    loadingLabel.setFont(loadingLabel.getFont().deriveFont(Font.BOLD, 14f));
    panel.add(loadingLabel, gbc);

    gbc.gridy = 2;
    JLabel fileLabel = new JLabel(file.getName());
    fileLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
    panel.add(fileLabel, gbc);

    gbc.gridy = 3;
    JButton showButton = new JButton("Show in Parquet Studio");
    showButton.addActionListener(e -> openInParquetStudio());
    panel.add(showButton, gbc);

    return panel;
  }

  private void openInParquetStudio() {
    ApplicationManager.getApplication().invokeLater(() -> {
      if (project.isDisposed()) {
        return;
      }
      ToolWindow toolWindow =
          ToolWindowManager.getInstance(project).getToolWindow("Parquet Studio");
      if (toolWindow == null) {
        showErrorNotification(Constants.Message.ERROR_OPENING_TOOL_WINDOW);
        return;
      }
      toolWindow.activate(() -> {
        ParquetToolWindow panel =
            ParquetStudioWindowService.getInstance(project).getPanel();
        if (panel == null) {
          // Factory runs during activate(); content not created yet on this EDT pass.
          ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) {
              return;
            }
            ParquetToolWindow retryPanel =
                ParquetStudioWindowService.getInstance(project).getPanel();
            if (retryPanel != null) {
              retryPanel.openFileInTab(new File(file.getPath()));
            } else {
              showErrorNotification(Constants.Message.ERROR_OPENING_TOOL_WINDOW);
            }
          });
          return;
        }
        panel.openFileInTab(new File(file.getPath()));
      }, true);
    });
  }

  /**
   * Shows an error notification to the user.
   */
  private void showErrorNotification(String message) {
    ApplicationManager.getApplication().invokeLater(() -> {
      try {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Parquet Studio Notifications")
            .createNotification(message, NotificationType.ERROR)
            .notify(project);
      } catch (Exception e) {
        // Fallback: show dialog if notification fails
        LOGGER.error("Failed to show notification, using dialog: " + message, e);
        com.intellij.openapi.ui.Messages.showErrorDialog(project, message, "Parquet Studio");
      }
    });
  }

  /**
   * Shows a warning notification to the user.
   */
  private void showWarningNotification(String message) {
    ApplicationManager.getApplication().invokeLater(() -> {
      try {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Parquet Studio Notifications")
            .createNotification(message, NotificationType.WARNING)
            .notify(project);
      } catch (Exception e) {
        LOGGER.warn("Failed to show notification: " + message, e);
      }
    });
  }

  /**
   * Shows an info notification to the user.
   */
  private void showInfoNotification(String message) {
    ApplicationManager.getApplication().invokeLater(() -> {
      try {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Parquet Studio Notifications")
            .createNotification(message, NotificationType.INFORMATION)
            .notify(project);
      } catch (Exception e) {
        LOGGER.info("Failed to show notification: " + message);
      }
    });
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

  @NotNull
  @Override
  public JComponent getComponent() {
    // Return loading panel while file is being opened
    return loadingPanel;
  }

  @Nullable
  @Override
  public JComponent getPreferredFocusedComponent() {
    return null;
  }

  @NotNull
  @Override
  public String getName() {
    return "Parquet Studio";
  }

  @NotNull
  @Override
  public VirtualFile getFile() {
    return file;
  }

  @Override
  public void setState(@NotNull FileEditorState state) {
    // No state to set
  }

  @Override
  public boolean isModified() {
    return false;
  }

  @Override
  public boolean isValid() {
    return file.isValid();
  }

  @Override
  public void selectNotify() {
    if (validateFile()) {
      openInParquetStudio();
    }
  }

  @Override
  public void deselectNotify() {
    // Nothing to do
  }

  @Override
  public void addPropertyChangeListener(@NotNull PropertyChangeListener listener) {
    // No properties to listen to
  }

  @Override
  public void removePropertyChangeListener(@NotNull PropertyChangeListener listener) {
    // No properties to listen to
  }

  @Nullable
  @Override
  public FileEditorLocation getCurrentLocation() {
    return null;
  }

  @Override
  public void dispose() {
    // Nothing to dispose
  }
}

