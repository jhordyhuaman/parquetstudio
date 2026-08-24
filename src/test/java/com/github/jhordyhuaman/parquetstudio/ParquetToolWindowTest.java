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
package com.github.jhordyhuaman.parquetstudio;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.jhordyhuaman.parquetstudio.ui.ParquetEditorPanel;
import com.github.jhordyhuaman.parquetstudio.ui.ParquetToolWindow;
import java.awt.Component;
import java.io.File;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ParquetToolWindowTest {

  private ParquetToolWindow toolWindow;

  @BeforeEach
  void setUp() {
    toolWindow = new ParquetToolWindow();
  }

  @Test
  @DisplayName("Should initialize with empty tabbed pane")
  void testInitialState() {
    assertThat(toolWindow).isNotNull();
    assertThat(toolWindow.getTabCount()).isEqualTo(0);
    assertThat(toolWindow.getSelectedTabIndex()).isEqualTo(-1);
  }

  @Test
  @DisplayName("Should have toolbar components")
  void testToolbarExists() {
    Component[] components = toolWindow.getComponents();
    assertThat(components.length).isGreaterThan(0);
  }

  @Test
  @DisplayName("Should return null for invalid tab index")
  void testGetEditorPanelInvalidIndex() {
    assertThat(toolWindow.getEditorPanelAt(-1)).isNull();
    assertThat(toolWindow.getEditorPanelAt(0)).isNull();
    assertThat(toolWindow.getEditorPanelAt(100)).isNull();
  }

  @Test
  @DisplayName("Should handle tab operations when no tabs exist")
  void testTabOperationsWithNoTabs() {
    assertThat(toolWindow.getTabCount()).isEqualTo(0);
    assertThat(toolWindow.getSelectedTabIndex()).isEqualTo(-1);
    assertThat(toolWindow.getEditorPanelAt(0)).isNull();
  }

  @Test
  void openingSameFileTwiceCreatesOneTab() throws Exception {
    File fixture = new File("src/test/resources/parquet/logical_date.parquet");
    javax.swing.SwingUtilities.invokeAndWait(() -> {
      toolWindow.openFileInTab(fixture);
      toolWindow.openFileInTab(fixture);
    });
    javax.swing.SwingUtilities.invokeAndWait(() -> {});
    assertThat(toolWindow.getTabCount()).isEqualTo(1);
  }
}

