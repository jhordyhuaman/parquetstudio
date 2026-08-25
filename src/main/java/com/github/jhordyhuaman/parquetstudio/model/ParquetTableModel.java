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
package com.github.jhordyhuaman.parquetstudio.model;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.ui.Messages;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.swing.table.AbstractTableModel;

/**
 * Table model for Parquet data with type validation.
 */
public class ParquetTableModel extends AbstractTableModel {
  private static final Logger LOGGER = Logger.getInstance(ParquetTableModel.class);

  private final List<String> columnNames;
  private final List<String> columnTypes;
  private final List<List<Object>> rows;

  public ParquetTableModel(List<String> columnNames, List<String> columnTypes, List<List<Object>> rows) {
    this.columnNames = new ArrayList<>(columnNames);
    this.columnTypes = new ArrayList<>(columnTypes);
    this.rows = new ArrayList<>();
    for (List<Object> row : rows) {
      this.rows.add(new ArrayList<>(row));
    }
  }

  /**
   * Gets the list of column names (without type info).
   *
   * @return list of column names
   */
  public List<String> getColumnNames() {
    return new ArrayList<>(columnNames);
  }

  /**
   * Gets the list of column types.
   *
   * @return list of column types
   */
  public List<String> getColumnTypes() {
    return new ArrayList<>(columnTypes);
  }

  @Override
  public int getRowCount() {
    return rows.size();
  }

  @Override
  public int getColumnCount() {
    return columnNames.size();
  }

  @Override
  public String getColumnName(int column) {
    if (column >= 0 && column < columnNames.size()) {
        String templateColumnName = "<html><center><strong>%s</strong><br><span style='font-size:10px;color:gray;'>%s</span></center></html>";
        return templateColumnName.formatted(columnNames.get(column), columnTypes.get(column).toLowerCase());
    }
    return "";
  }

  @Override
  public Class<?> getColumnClass(int columnIndex) {
    if (columnIndex >= 0 && columnIndex < columnTypes.size()) {
      String type = columnTypes.get(columnIndex);
      if (type.contains("BOOLEAN")) return Boolean.class;
      if (type.contains("INTEGER")) return Integer.class;
      if (type.contains("BIGINT")) return Long.class;
      if (type.contains("DOUBLE")) return Double.class;
      if (type.contains("DATE")) return LocalDate.class;
      if (type.contains("TIMESTAMP")) return LocalDateTime.class;
    }
    return String.class;
  }

  @Override
  public Object getValueAt(int rowIndex, int columnIndex) {
    if (rowIndex >= 0 && rowIndex < rows.size() && columnIndex >= 0 && columnIndex < columnNames.size()) {
      List<Object> row = rows.get(rowIndex);
      if (columnIndex < row.size()) {
        return row.get(columnIndex);
      }
    }
    return null;
  }

  @Override
  public boolean isCellEditable(int rowIndex, int columnIndex) {
    return true;
  }

  @Override
  public void setValueAt(Object aValue, int rowIndex, int columnIndex) {
    if (rowIndex < 0 || rowIndex >= rows.size() || columnIndex < 0 || columnIndex >= columnNames.size()) {
      return;
    }

    String stringValue = aValue != null ? aValue.toString() : null;
    String columnType = columnTypes.get(columnIndex);

    try {
      Object convertedValue = convertValue(stringValue, columnType);
      List<Object> row = rows.get(rowIndex);
      
      while (row.size() <= columnIndex) {
        row.add(null);
      }
      
      row.set(columnIndex, convertedValue);
      fireTableCellUpdated(rowIndex, columnIndex);
    } catch (Exception e) {
      LOGGER.error("Error setting value: " + e.getMessage(), e);
      Messages.showErrorDialog(
          "Error converting value to " + columnType + ": " + e.getMessage(),
          "Conversion Error");
    }
  }

  private Object convertValue(String stringValue, String columnType) {
    if (stringValue == null || stringValue.trim().isEmpty()) {
      return null;
    }

    String trimmed = stringValue.trim();

    try {
      if (columnType.contains("BOOLEAN")) {
        return parseBoolean(trimmed);
      } else if (columnType.contains("INTEGER")) {
        return Integer.parseInt(trimmed);
      } else if (columnType.contains("BIGINT")) {
        return Long.parseLong(trimmed);
      } else if (columnType.contains("DOUBLE")) {
        return Double.parseDouble(trimmed);
      } else if (columnType.contains("DATE")) {
        return parseDate(trimmed);
      } else if (columnType.contains("TIMESTAMP")) {
        return parseTimestamp(trimmed);
      } else {
        return trimmed;
      }
    } catch (NumberFormatException | DateTimeParseException e) {
      throw new IllegalArgumentException("Cannot convert '" + trimmed + "' to " + columnType);
    }
  }

  /**
   * Parses a date string, accepting ISO format (YYYY-MM-DD).
   */
  private LocalDate parseDate(String dateString) {
    try {
      return LocalDate.parse(dateString);
    } catch (DateTimeParseException e) {
      throw new IllegalArgumentException("Invalid date format. Expected: YYYY-MM-DD (e.g., 2024-11-12)");
    }
  }

  /**
   * Parses a timestamp string, accepting multiple formats:
   * - ISO format: 2024-11-12T10:30:00
   * - Space format: 2022-07-11 15:53:24
   * - With milliseconds: 2022-07-11 15:53:24.671 or 2022-07-11T15:53:24.671
   * - With microseconds: 2022-07-11 15:53:24.671234
   * - With nanoseconds: 2022-07-11 15:53:24.671234567
   */
  private LocalDateTime parseTimestamp(String timestampString) {
    String normalized = timestampString.trim();
    
    // Normalize: replace space with T for ISO format compatibility
    // But keep track of original separator for fractional seconds handling
    boolean hasSpace = normalized.contains(" ");
    boolean hasT = normalized.contains("T");
    String separator = hasT ? "T" : " ";
    
    // Handle fractional seconds (milliseconds, microseconds, nanoseconds)
    if (normalized.contains(".")) {
      String[] parts = normalized.split("[T ]", 2);
      if (parts.length == 2) {
        String datePart = parts[0];
        String timePart = parts[1];
        
        // Extract fractional seconds if present
        String[] timeParts = timePart.split("\\.");
        if (timeParts.length == 2) {
          String timeWithoutFraction = timeParts[0];
          String fraction = timeParts[1];
          
          // Normalize fractional seconds: pad to 9 digits (nanoseconds) or truncate
          // This allows flexible input: 1-9 digits
          while (fraction.length() < 9) {
            fraction += "0";
          }
          if (fraction.length() > 9) {
            fraction = fraction.substring(0, 9);
          }
          
          // Reconstruct with T separator (ISO format)
          normalized = datePart + "T" + timeWithoutFraction + "." + fraction;
          
          // Try parsing with nanoseconds
          try {
            return LocalDateTime.parse(normalized, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSSSS"));
          } catch (DateTimeParseException e) {
            // Fall through to other formatters
          }
        }
      }
    }
    
    // List of common timestamp formats to try (most specific first)
    DateTimeFormatter[] formatters = {
        // Format with milliseconds and space (most common from files like: 2022-07-11 15:53:24.671)
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"),
        // Format with milliseconds and T
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS"),
        // ISO format with T (standard: 2024-11-12T10:30:00)
        DateTimeFormatter.ISO_LOCAL_DATE_TIME,
        // Format with space instead of T (2024-11-12 10:30:00)
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
        // Format with microseconds and space
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS"),
        // Format with microseconds and T
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS"),
        // Format with nanoseconds and space
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSSSSS"),
        // Format with nanoseconds and T
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSSSS")
    };

    // Try each formatter until one works
    for (DateTimeFormatter formatter : formatters) {
      try {
        return LocalDateTime.parse(normalized, formatter);
      } catch (DateTimeParseException e) {
        // Continue to next formatter
      }
    }

    // If none worked, try to normalize space to T and parse with ISO
    String withT = normalized.replace(' ', 'T');
    if (!withT.equals(normalized)) {
      try {
        return LocalDateTime.parse(withT);
      } catch (DateTimeParseException e) {
        // Fall through
      }
    }
    
    throw new IllegalArgumentException(
        "Invalid timestamp format. Expected formats:\n" +
        "  - YYYY-MM-DDTHH:mm:ss (e.g., 2024-11-12T10:30:00)\n" +
        "  - YYYY-MM-DD HH:mm:ss (e.g., 2024-11-12 10:30:00)\n" +
        "  - YYYY-MM-DD HH:mm:ss.SSS (e.g., 2022-07-11 15:53:24.671)");
  }

  private Boolean parseBoolean(String s) {
    String x = s.toLowerCase(Locale.ROOT);
    return x.equals("true") || x.equals("1") || x.equals("yes") || x.equals("y");
  }

  public void addRow() {
    List<Object> newRow = new ArrayList<>();
    for (int i = 0; i < columnNames.size(); i++) {
      String type = columnTypes.get(i);
      Object defaultValue = getDefaultValue(type);
      newRow.add(defaultValue);
    }
    rows.add(newRow);
    int newRowIndex = rows.size() - 1;
    fireTableRowsInserted(newRowIndex, newRowIndex);
  }

  /**
   * Appends a row with the given values (one per column, in column order) and fires the
   * corresponding table-model event so listeners (e.g. the dirty-flag tracker) are notified.
   *
   * @param values the cell values for the new row, in column order
   */
  public void addRow(List<Object> values) {
    rows.add(new ArrayList<>(values));
    int newRowIndex = rows.size() - 1;
    fireTableRowsInserted(newRowIndex, newRowIndex);
  }

  public void deleteRow(int rowIndex) {
    if (rowIndex >= 0 && rowIndex < rows.size()) {
      rows.remove(rowIndex);
      fireTableRowsDeleted(rowIndex, rowIndex);
    }
  }

  public void deleteRows(int[] rowIndices) {
    if (rowIndices == null || rowIndices.length == 0) {
      return;
    }

    int[] sorted = java.util.Arrays.stream(rowIndices)
        .distinct()
        .sorted()
        .toArray();

    for (int i = sorted.length - 1; i >= 0; i--) {
      deleteRow(sorted[i]);
    }
  }

  private Object getDefaultValue(String type) {
    if (type.contains("BOOLEAN")) return false;
    if (type.contains("INTEGER")) return 0;
    if (type.contains("BIGINT")) return 0L;
    if (type.contains("DOUBLE")) return 0.0;
    if (type.contains("DATE")) return null;
    if (type.contains("TIMESTAMP")) return null;
    return "";
  }

  /**
   * Adds a new column to the table.
   *
   * @param columnName the name of the new column
   * @param columnType the type of the new column (e.g., "VARCHAR", "INTEGER")
   */
  public void addColumn(String columnName, String columnType) {
    if (columnName == null || columnName.trim().isEmpty()) {
      throw new IllegalArgumentException("Column name cannot be empty");
    }
    if (columnType == null || columnType.trim().isEmpty()) {
      throw new IllegalArgumentException("Column type cannot be empty");
    }

    // Check if column name already exists
    String trimmedName = columnName.trim();
    if (columnNames.contains(trimmedName)) {
      throw new IllegalArgumentException("Column name already exists: " + trimmedName);
    }

    // Add column to metadata
    columnNames.add(trimmedName);
    columnTypes.add(columnType.toUpperCase());

    // Add default value to all existing rows
    Object defaultValue = getDefaultValue(columnType);
    for (List<Object> row : rows) {
      row.add(defaultValue);
    }

    // Notify table that a column was added
    fireTableStructureChanged();
  }

  /**
   * Deletes a column from the table.
   *
   * @param columnIndex the index of the column to delete
   * @throws IllegalArgumentException if columnIndex is invalid
   */
  public void deleteColumn(int columnIndex) {
    if (columnIndex < 0 || columnIndex >= columnNames.size()) {
      throw new IllegalArgumentException("Invalid column index: " + columnIndex);
    }

    if (columnNames.size() <= 1) {
      throw new IllegalArgumentException("Cannot delete the last column. A table must have at least one column.");
    }

    // Remove column from metadata
    columnNames.remove(columnIndex);
    columnTypes.remove(columnIndex);

    // Remove column value from all rows
    for (List<Object> row : rows) {
      if (columnIndex < row.size()) {
        row.remove(columnIndex);
      }
    }

    // Notify table that a column was removed
    fireTableStructureChanged();
  }

  public ParquetData toParquetData() {
    return new ParquetData(columnNames, columnTypes, rows);
  }
}

