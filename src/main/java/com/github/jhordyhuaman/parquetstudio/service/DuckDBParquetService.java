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
import com.intellij.openapi.diagnostic.Logger;
import java.io.File;
import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

/**
 * Service for reading and writing Parquet files using DuckDB.
 */
public class DuckDBParquetService {
  private static final Logger LOGGER = Logger.getInstance(DuckDBParquetService.class);
  private static final String DUCKDB_JDBC_URL = "jdbc:duckdb:";
  private static boolean driverLoaded = false;

  static {
    try {
      LOGGER.info("Attempting to load DuckDB JDBC driver...");
      Class<?> driverClass = Class.forName("org.duckdb.DuckDBDriver");
      Driver driver = (Driver) driverClass.getDeclaredConstructor().newInstance();
      DriverManager.registerDriver(driver);
      driverLoaded = true;
      LOGGER.info("DuckDB JDBC driver loaded successfully");
    } catch (Exception e) {
      LOGGER.error("Failed to load DuckDB JDBC driver", e);
      driverLoaded = false;
    }
  }

  /**
   * Loads a Parquet file and returns its data.
   */
  public ParquetData loadParquet(File file) throws Exception {
    LOGGER.info("Loading Parquet file: " + file.getAbsolutePath());
    LOGGER.info("Driver loaded status: " + driverLoaded);

    // Log available drivers for debugging
    Enumeration<Driver> drivers = DriverManager.getDrivers();
    LOGGER.info("Available JDBC drivers:");
    while (drivers.hasMoreElements()) {
      Driver d = drivers.nextElement();
      LOGGER.info("  - " + d.getClass().getName());
    }

    if (!driverLoaded) {
      throw new SQLException("DuckDB JDBC driver not loaded. Check classpath for org.duckdb:duckdb_jdbc dependency.");
    }

    File readable = SafeParquetPath.toReadable(file);
    try {
      LOGGER.info("Attempting to create connection to: " + DUCKDB_JDBC_URL);
      try (Connection conn = DriverManager.getConnection(DUCKDB_JDBC_URL)) {
        LOGGER.info("Connection established successfully");
        List<String> columnNames = new ArrayList<>();
        List<String> columnTypes = new ArrayList<>();

        // Detect schema
        String sql = "SELECT * FROM read_parquet(?) LIMIT 0";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
          ps.setString(1, readable.getAbsolutePath());
          try (ResultSet rs = ps.executeQuery()) {
            ResultSetMetaData md = rs.getMetaData();
            int n = md.getColumnCount();
            for (int i = 1; i <= n; i++) {
              columnNames.add(md.getColumnLabel(i));
              String type = md.getColumnTypeName(i).toUpperCase(Locale.ROOT);
              columnTypes.add(normalizeType(type));
            }
          }
        }

        // Load all data
        List<List<Object>> rows = new ArrayList<>();
        String readAll = "SELECT * FROM read_parquet(?)";
        try (PreparedStatement ps = conn.prepareStatement(readAll)) {
          ps.setString(1, readable.getAbsolutePath());
          try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
              List<Object> row = new ArrayList<>();
              for (int i = 1; i <= columnNames.size(); i++) {
                Object val = rs.getObject(i);
                row.add(val);
              }
              rows.add(row);
            }
          }
        }

        LOGGER.info(
            String.format(
                "Loaded: %d columns, %d rows", columnNames.size(), rows.size()));
        return new ParquetData(columnNames, columnTypes, rows);
      } catch (SQLException e) {
        LOGGER.error("SQL Exception while loading Parquet file", e);
        LOGGER.error("SQL State: " + e.getSQLState());
        LOGGER.error("Error Code: " + e.getErrorCode());
        throw e;
      } catch (Exception e) {
        LOGGER.error("Unexpected exception while loading Parquet file", e);
        throw e;
      }
    } finally {
      if (SafeParquetPath.isTempCopy(file, readable)) {
        if (!readable.delete()) {
          LOGGER.warn("Could not delete temp copy: " + readable.getAbsolutePath());
        }
      }
    }
  }

  /**
   * Saves ParquetData to a new Parquet file.
   */
  public void saveParquet(File file, ParquetData data) throws Exception {
    LOGGER.info("Saving Parquet file: " + file.getAbsolutePath());

    if (data.getColumnNames().isEmpty()) {
      throw new IllegalArgumentException("No columns to save");
    }

    if (!driverLoaded) {
      throw new SQLException("DuckDB JDBC driver not loaded. Check classpath for org.duckdb:duckdb_jdbc dependency.");
    }

    SafeParquetPath.writeThenMove(file, actualTarget -> doSaveParquet(actualTarget, data));
  }

  private void doSaveParquet(File file, ParquetData data) throws Exception {
    LOGGER.info("Attempting to create connection to: " + DUCKDB_JDBC_URL);
    try (Connection conn = DriverManager.getConnection(DUCKDB_JDBC_URL)) {
      LOGGER.info("Connection established successfully");
      String tempTable = "temp_table_" + System.currentTimeMillis();

      // Create temporary table
      StringBuilder ddl = new StringBuilder("CREATE TABLE ").append(tempTable).append(" (");
      for (int i = 0; i < data.getColumnNames().size(); i++) {
        if (i > 0) ddl.append(", ");
        String colName = data.getColumnNames().get(i);
        String colType = data.getColumnTypes().get(i);
        // Convert schema types to DuckDB types
        String duckDBType = toDuckDBType(colType);
        LOGGER.debug("Column " + colName + ": " + colType + " -> DuckDB type: " + duckDBType);
        ddl.append(escapeIdent(colName)).append(" ").append(duckDBType);
      }
      ddl.append(")");
      LOGGER.info("Creating table with DDL: " + ddl.toString().substring(0, Math.min(500, ddl.length())) + "...");
      try (Statement st = conn.createStatement()) {
        st.execute(ddl.toString());
      }

      // Insert rows
      StringBuilder ins = new StringBuilder("INSERT INTO ").append(tempTable).append(" VALUES (");
      for (int i = 0; i < data.getColumnNames().size(); i++) {
        if (i > 0) ins.append(", ");
        ins.append("?");
      }
      ins.append(")");

      try (PreparedStatement ps = conn.prepareStatement(ins.toString())) {
        for (List<Object> row : data.getRows()) {
          for (int i = 0; i < data.getColumnNames().size(); i++) {
            Object val = row.size() > i ? row.get(i) : null;
            String expectedType = data.getColumnTypes().get(i);
            setParameter(ps, i + 1, val, expectedType);
          }
          ps.addBatch();
        }
        ps.executeBatch();
      }

      // Export table to Parquet
      String exportQuery = "COPY (SELECT * FROM " + tempTable + ") TO '"
          + file.getAbsolutePath().replace("'", "''") + "' (FORMAT PARQUET)";
      try (Statement st = conn.createStatement()) {
        st.execute(exportQuery);
      }

      LOGGER.info("Parquet file saved: " + file.getAbsolutePath());
    }
  }

  private String normalizeType(String type) {
    if (type == null || type.isEmpty()) return "VARCHAR";

    String upperType = type.toUpperCase();

    // Handle DECIMAL types - preserve the full type with precision
    if (upperType.startsWith("DECIMAL")) {
      return type; // Keep as DECIMAL(23,10) etc.
    }

    if (upperType.contains("BOOL")) return "BOOLEAN";
    if (upperType.contains("INT")) {
      if (upperType.contains("BIG")) return "BIGINT";
      return "INTEGER";
    }
    if (upperType.contains("DOUBLE") || upperType.contains("FLOAT")) return "DOUBLE";
    if (upperType.contains("DATE") && !upperType.contains("TIME")) return "DATE";
    if (upperType.contains("TIMESTAMP")) return "TIMESTAMP";
    return "VARCHAR";
  }

  /**
   * Converts schema types (from Avro/JSON schema) to valid DuckDB types.
   * Examples:
   *   - string -> VARCHAR
   *   - decimal(23,10) -> DECIMAL(23,10)
   *   - date -> DATE
   *   - timestamp -> TIMESTAMP
   *   - int32, integer -> INTEGER
   */
  private String toDuckDBType(String schemaType) {
    if (schemaType == null || schemaType.isEmpty()) {
      return "VARCHAR";
    }

    String lowerType = schemaType.toLowerCase().trim();

    // String types
    if (lowerType.equals("string") || lowerType.equals("utf8") || lowerType.equals("text")) {
      return "VARCHAR";
    }

    // Decimal types - keep the precision
    if (lowerType.startsWith("decimal")) {
      // decimal(23,10) -> DECIMAL(23,10)
      return schemaType.toUpperCase();
    }

    // Integer types
    if (lowerType.equals("int32") || lowerType.equals("int") || lowerType.equals("integer")) {
      return "INTEGER";
    }
    if (lowerType.equals("int64") || lowerType.equals("long") || lowerType.equals("bigint")) {
      return "BIGINT";
    }

    // Float types
    if (lowerType.equals("float") || lowerType.equals("float32")) {
      return "FLOAT";
    }
    if (lowerType.equals("double") || lowerType.equals("float64")) {
      return "DOUBLE";
    }

    // Date/Time types
    if (lowerType.equals("date")) {
      return "DATE";
    }
    if (lowerType.equals("timestamp") || lowerType.equals("timestamp_millis") || lowerType.equals("timestamp_micros")) {
      return "TIMESTAMP";
    }
    if (lowerType.equals("time")) {
      return "TIME";
    }

    // Boolean
    if (lowerType.equals("boolean") || lowerType.equals("bool")) {
      return "BOOLEAN";
    }

    // Binary
    if (lowerType.equals("binary") || lowerType.equals("bytes")) {
      return "BLOB";
    }

    // If already uppercase DuckDB type, return as-is
    if (schemaType.equals("VARCHAR") || schemaType.equals("INTEGER") ||
        schemaType.equals("BIGINT") || schemaType.equals("DATE") ||
        schemaType.equals("TIMESTAMP") || schemaType.equals("BOOLEAN") ||
        schemaType.equals("DOUBLE") || schemaType.equals("FLOAT") ||
        schemaType.startsWith("DECIMAL")) {
      return schemaType;
    }

    // Default to VARCHAR
    return "VARCHAR";
  }

  /**
   * Sets a parameter in a PreparedStatement, converting the value to the expected type if needed.
   * Returns true if the value was set as NULL.
   */
  private boolean setParameter(PreparedStatement ps, int index, Object val, String expectedType) throws SQLException {
    String duckDBType = toDuckDBType(expectedType);

    if (val == null || (val instanceof String && ((String) val).isEmpty())) {
      ps.setNull(index, java.sql.Types.NULL);
      return true;
    }


    // If the value is already the correct type, use it directly
    if (val instanceof Boolean) {
      ps.setBoolean(index, (Boolean) val);
      return false;
    } else if (val instanceof Integer) {
      ps.setInt(index, (Integer) val);
      return false;
    } else if (val instanceof Long) {
      ps.setLong(index, (Long) val);
      return false;
    } else if (val instanceof Double) {
      ps.setDouble(index, (Double) val);
      return false;
    } else if (val instanceof java.math.BigDecimal) {
      ps.setBigDecimal(index, (java.math.BigDecimal) val);
      return false;
    } else if (val instanceof LocalDate) {
      ps.setDate(index, Date.valueOf((LocalDate) val));
      return false;
    } else if (val instanceof LocalDateTime) {
      ps.setTimestamp(index, Timestamp.valueOf((LocalDateTime) val));
      return false;
    } else {
      // Value is a String, need to convert based on expected type
      String strVal = val.toString().trim();

      if (strVal.isEmpty()) {
        ps.setNull(index, java.sql.Types.NULL);
        return true;
      }

      try {
        if (duckDBType.startsWith("DECIMAL")) {
          // Convert string to BigDecimal
          java.math.BigDecimal decimal = new java.math.BigDecimal(strVal);
          ps.setBigDecimal(index, decimal);
          return false;
        } else if (duckDBType.equals("INTEGER")) {
          ps.setInt(index, Integer.parseInt(strVal));
          return false;
        } else if (duckDBType.equals("BIGINT")) {
          ps.setLong(index, Long.parseLong(strVal));
          return false;
        } else if (duckDBType.equals("DOUBLE") || duckDBType.equals("FLOAT")) {
          ps.setDouble(index, Double.parseDouble(strVal));
          return false;
        } else if (duckDBType.equals("BOOLEAN")) {
          ps.setBoolean(index, Boolean.parseBoolean(strVal));
          return false;
        } else if (duckDBType.equals("DATE")) {
          // Try to parse date
          try {
            ps.setDate(index, Date.valueOf(LocalDate.parse(strVal)));
            return false;
          } catch (Exception e) {
            ps.setString(index, strVal);
            return false;
          }
        } else if (duckDBType.equals("TIMESTAMP")) {
          // Try to parse timestamp
          try {
            ps.setTimestamp(index, Timestamp.valueOf(LocalDateTime.parse(strVal)));
            return false;
          } catch (Exception e) {
            ps.setString(index, strVal);
            return false;
          }
        } else {
          // Default: VARCHAR
          ps.setString(index, strVal);
          return false;
        }
      } catch (NumberFormatException e) {
        // If conversion fails, set as null for numeric types
        LOGGER.warn("Failed to convert value '" + strVal.substring(0, Math.min(50, strVal.length())) + "' to " + duckDBType + ", setting as NULL. Column index: " + index);
        ps.setNull(index, java.sql.Types.NULL);
        return true;
      }
    }
  }

  private String escapeIdent(String ident) {
    return '"' + ident.replace("\"", "\"\"") + '"';
  }
}

