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

import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorPolicy;
import com.intellij.openapi.fileEditor.FileEditorProvider;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

/**
 * Editor provider for Parquet files.
 * Opens .parquet files automatically in Parquet Studio tool window.
 */
public class ParquetEditorProvider implements FileEditorProvider, DumbAware {
  @Override
  public boolean accept(@NotNull Project project, @NotNull VirtualFile file) {
    return file.isInLocalFileSystem()
        && file.getExtension() != null
        && file.getExtension().equalsIgnoreCase("parquet");
  }

  @NotNull
  @Override
  public FileEditor createEditor(@NotNull Project project, @NotNull VirtualFile file) {
    // Return a dummy editor - the actual opening is handled by ParquetFileEditor
    return new ParquetFileEditor(project, file);
  }

  @NotNull
  @Override
  public String getEditorTypeId() {
    return "parquet-studio-editor";
  }

  @NotNull
  @Override
  public FileEditorPolicy getPolicy() {
    // Use HIDE_DEFAULT_EDITOR to prevent opening in text editor
    // The file will only open in Parquet Studio
    return FileEditorPolicy.HIDE_DEFAULT_EDITOR;
  }
}

