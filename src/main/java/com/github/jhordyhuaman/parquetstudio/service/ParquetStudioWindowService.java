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
package com.github.jhordyhuaman.parquetstudio.service;

import com.github.jhordyhuaman.parquetstudio.ui.ParquetToolWindow;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.Nullable;

/**
 * Project-level registry for the ParquetToolWindow panel, so callers reach it
 * via API instead of scanning the tool window's Swing hierarchy (which differs
 * across IntelliJ versions).
 */
@Service(Service.Level.PROJECT)
public final class ParquetStudioWindowService {
  private volatile ParquetToolWindow panel;

  public static ParquetStudioWindowService getInstance(Project project) {
    return project.getService(ParquetStudioWindowService.class);
  }

  public void registerPanel(ParquetToolWindow panel) {
    this.panel = panel;
  }

  @Nullable
  public ParquetToolWindow getPanel() {
    return panel;
  }
}
