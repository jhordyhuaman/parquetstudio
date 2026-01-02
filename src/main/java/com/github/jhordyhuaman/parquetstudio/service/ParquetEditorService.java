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

import com.github.jhordyhuaman.parquetstudio.model.ParquetData;
import com.github.jhordyhuaman.parquetstudio.model.ParquetTableModel;
import com.github.jhordyhuaman.parquetstudio.model.SchemaStructure;
import com.intellij.openapi.diagnostic.Logger;
import java.io.File;
import java.util.List;

/**
 * Service layer for Parquet editor operations.
 * Contains business logic for CRUD operations, validations, and file management.
 */
public class ParquetEditorService {
  private static final Logger LOGGER = Logger.getInstance(ParquetEditorService.class);
  
  private final DuckDBParquetService duckDBService;
  private final DataSchemaService dataSchemaService;
  private ParquetTableModel tableModel;
  private File currentFile;

  public ParquetEditorService() {
      this.duckDBService = new DuckDBParquetService();
      this.dataSchemaService = new DataSchemaService();
  }

  /**
   * Loads a Parquet file.
   *
   * @param file the Parquet file to load
   * @return the loaded ParquetData
   * @throws Exception if loading fails
   */
  public ParquetData loadParquetFile(File file) throws Exception {
    LOGGER.info("Loading Parquet file: " + file.getAbsolutePath());
    ParquetData data = duckDBService.loadParquet(file);
    this.currentFile = file;
    return data;
  }

    public String generateTransformSchemaString() throws Exception {
      return this.dataSchemaService.generateTransformSchemaString();
    }

    public String generateOriginalSchemaString(List<String> columnNames, List<String> columnTypes) throws Exception{
      return this.dataSchemaService.generateOriginalSchemaString(columnNames, columnTypes);
    }
    public boolean isSameNumberOfColumns(){
      return this.dataSchemaService.isSameNumberOfColumns();
    }
    public SchemaStructure getSchemaStructureOriginal(){ return this.dataSchemaService.getSchemaStructureOriginal();}
    public SchemaStructure getSchemaStructureTransform(){ return this.dataSchemaService.getSchemaStructureTransform();}
    public void setNullSchemaTransform(){ this.dataSchemaService.setNullSchemaTransform(); }
  /**
   * Initializes the table model with loaded data.
   *
   * @param data the ParquetData to initialize the model with
   * @return the initialized ParquetTableModel
   */
  public ParquetTableModel initializeTableModel(ParquetData data) {
    this.tableModel = new ParquetTableModel(
        data.getColumnNames(), 
        data.getColumnTypes(), 
        data.getRows()
    );
    return this.tableModel;
  }

  /**
   * Gets the current table model.
   *
   * @return the current table model, or null if not initialized
   */
  public ParquetTableModel getTableModel() {
    return tableModel;
  }

  /**
   * Gets the currently loaded file.
   *
   * @return the current file, or null if no file is loaded
   */
  public File getCurrentFile() {
    return currentFile;
  }

  /**
   * Checks if a file is currently loaded.
   *
   * @return true if a file is loaded, false otherwise
   */
  public boolean hasFile() {
    return currentFile != null;
  }

  /**
   * Gets the column types from the current table model.
   *
   * @return list of column types, or null if not loaded
   */
  public List<String> getColumnTypes() {
    if (tableModel != null) {
      return tableModel.getColumnTypes();
    }
    return null;
  }

  /**
   * Gets the column names from the current table model.
   *
   * @return list of column names, or null if not loaded
   */
  public List<String> getColumnNames() {
    if (tableModel != null) {
      return tableModel.getColumnNames();
    }
    return null;
  }

  /**
   * Gets the currently loaded schema file.
   *
   * @return the current schema file, or null if no file is loaded
  */
  public File getCurrentSchemaFile() { return this.dataSchemaService.schemaFile; }
  /**
   * Sets the schema file.
   *
  */
  public ParquetEditorService setSchemaFile(File schemaFile){
      this.dataSchemaService.schemaFile = schemaFile;
      return this;
  }
  /**
   * Checks if a schema file is currently loaded.
   *
   * @return true if a schema file is loaded, false otherwise
  */
  public boolean hasSchemaFile() { return this.dataSchemaService.schemaFile != null; }

  /**
   * Validates that data is loaded before performing operations.
   *
   * @throws IllegalStateException if no data is loaded
   */
  public void validateDataLoaded() throws IllegalStateException {
    if (tableModel == null) {
      throw new IllegalStateException("No data loaded. Please open a file first.");
    }
  }

  /**
   * Adds a new row to the table model.
   *
   * @return the index of the newly added row
   * @throws IllegalStateException if no data is loaded
   */
  public int addRow() throws IllegalStateException {
    validateDataLoaded();
    int newRowIndex = tableModel.getRowCount();
    tableModel.addRow();
    LOGGER.info("Added row at index: " + newRowIndex);
    return newRowIndex;
  }

  /**
   * Adds a new column to the table model.
   *
   * @param columnName the name of the new column
   * @param columnType the type of the new column
   * @return the index of the newly added column
   * @throws IllegalStateException if no data is loaded
   * @throws IllegalArgumentException if column name is invalid or duplicate
   */
  public int addColumn(String columnName, String columnType) 
      throws IllegalStateException, IllegalArgumentException {
    validateDataLoaded();
    int newColumnIndex = tableModel.getColumnCount();
    tableModel.addColumn(columnName, columnType);
    LOGGER.info("Added column: " + columnName + " (" + columnType + ") at index: " + newColumnIndex);
    return newColumnIndex;
  }

  /**
   * Deletes a column from the table model.
   *
   * @param columnIndex the index of the column to delete
   * @return the name of the deleted column
   * @throws IllegalStateException if no data is loaded
   * @throws IllegalArgumentException if column index is invalid or it's the last column
   */
  public String deleteColumn(int columnIndex) 
      throws IllegalStateException, IllegalArgumentException {
    validateDataLoaded();
    
    if (columnIndex < 0 || columnIndex >= tableModel.getColumnCount()) {
      throw new IllegalArgumentException("Invalid column index: " + columnIndex);
    }

    if (tableModel.getColumnCount() <= 1) {
      throw new IllegalArgumentException(
          "Cannot delete the last column. A table must have at least one column.");
    }

    String columnName = tableModel.getColumnName(columnIndex);
    tableModel.deleteColumn(columnIndex);
    LOGGER.info("Deleted column: " + columnName);
    return columnName;
  }

  /**
   * Deletes rows from the table model.
   *
   * @param rowIndices the indices of the rows to delete
   * @return the number of rows deleted
   * @throws IllegalStateException if no data is loaded
   */
  public int deleteRows(int[] rowIndices) throws IllegalStateException {
    validateDataLoaded();
    
    if (rowIndices == null || rowIndices.length == 0) {
      return 0;
    }

    tableModel.deleteRows(rowIndices);
    LOGGER.info("Deleted " + rowIndices.length + " row(s)");
    return rowIndices.length;
  }

  /**
   * Saves the current table model to a Parquet file.
   *
   * @param outputFile the file to save to
   * @throws IllegalStateException if no data is loaded
   * @throws Exception if saving fails
   */
  public void saveParquetFile(File outputFile, SchemaStructure schema) throws Exception {
    validateDataLoaded();

    ParquetData dataClone = new ParquetData(tableModel.toParquetData());
    if(schema != null) this.dataSchemaService.applyConvertTypes(dataClone, schema);

    duckDBService.saveParquet(outputFile, dataClone);
    LOGGER.info("Saved Parquet file: " + outputFile.getAbsolutePath());
  }

  /**
   * Gets the row count from the table model.
   *
   * @return the number of rows, or 0 if no data is loaded
   */
  public int getRowCount() {
    return tableModel != null ? tableModel.getRowCount() : 0;
  }

  /**
   * Gets the column count from the table model.
   *
   * @return the number of columns, or 0 if no data is loaded
   */
  public int getColumnCount() {
    return tableModel != null ? tableModel.getColumnCount() : 0;
  }

  /**
   * Gets the column name at the specified index.
   *
   * @param columnIndex the column index
   * @return the column name
   * @throws IllegalStateException if no data is loaded
   */
  public String getColumnName(int columnIndex) throws IllegalStateException {
    validateDataLoaded();
    return tableModel.getColumnName(columnIndex);
  }
}

