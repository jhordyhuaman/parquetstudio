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

import com.github.jhordyhuaman.parquetstudio.service.ParquetStudioWindowService;
import com.github.jhordyhuaman.parquetstudio.ui.ParquetToolWindow;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.IconLoader;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.openapi.wm.ToolWindowType;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import java.util.List;
import javax.swing.Icon;

/**
 * Factory for creating the Parquet Studio tool window.
 */
public class ParquetToolWindowFactory implements ToolWindowFactory {

  private static final Icon FLOAT_WINDOW_ICON =
      IconLoader.getIcon("/icons/ui/floatWindow/floatWindow.svg", ParquetToolWindowFactory.class);

  @Override
  public void createToolWindowContent(Project project, ToolWindow toolWindow) {
    ParquetToolWindow parquetToolWindow = new ParquetToolWindow();
    ParquetStudioWindowService.getInstance(project).registerPanel(parquetToolWindow);
    ContentFactory contentFactory = ContentFactory.getInstance();
    Content content = contentFactory.createContent(parquetToolWindow, "", false);
    toolWindow.getContentManager().addContent(content);

    toolWindow.setTitleActions(List.of(new DetachToolWindowAction(toolWindow)));
  }

  /**
   * Toggles the tool window between docked (or whatever type it previously had) and a separate
   * floating OS window ({@link ToolWindowType#WINDOWED}).
   */
  private static final class DetachToolWindowAction extends AnAction {

    private final ToolWindow toolWindow;
    private ToolWindowType previousType = ToolWindowType.DOCKED;

    private DetachToolWindowAction(ToolWindow toolWindow) {
      super(FLOAT_WINDOW_ICON);
      this.toolWindow = toolWindow;
    }

    @Override
    public ActionUpdateThread getActionUpdateThread() {
      return ActionUpdateThread.EDT;
    }

    @Override
    public void update(AnActionEvent e) {
      if (toolWindow.isDisposed()) {
        e.getPresentation().setEnabled(false);
        return;
      }
      e.getPresentation().setEnabled(true);
      boolean windowed = toolWindow.getType() == ToolWindowType.WINDOWED;
      e.getPresentation().setText(windowed ? "Dock Window" : "Open in Separate Window");
      e.getPresentation().setIcon(FLOAT_WINDOW_ICON);
    }

    @Override
    public void actionPerformed(AnActionEvent e) {
      if (toolWindow.isDisposed()) {
        return;
      }
      if (toolWindow.getType() != ToolWindowType.WINDOWED) {
        previousType = toolWindow.getType();
        toolWindow.setType(ToolWindowType.WINDOWED, null);
      } else {
        toolWindow.setType(previousType, null);
      }
    }
  }
}

