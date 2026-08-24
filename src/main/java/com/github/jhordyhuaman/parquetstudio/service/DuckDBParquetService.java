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
        LOGGER.info("Column " + colName + ": " + colType + " -> DuckDB type: " + duckDBType);
        ddl.append(escapeIdent(colName)).append(" ").append(duckDBType);
      }
      ddl.append(")");
      LOGGER.info("Creating table with DDL: " + ddl.toString().substring(0, Math.min(500, ddl.length())) + "...");
      try (Statement st = conn.createStatement()) {
        st.execute(ddl.toString());
      }

      // Verify table schema was created correctly
      LOGGER.info("=== VERIFYING TABLE SCHEMA ===");
      try (Statement st = conn.createStatement();
           ResultSet rs = st.executeQuery("DESCRIBE " + tempTable)) {
        while (rs.next()) {
          String colName = rs.getString("column_name");
          String colType = rs.getString("column_type");
          if (colType.contains("DECIMAL") || colType.contains("VARCHAR")) {
            LOGGER.info("Table column: " + colName + " = " + colType);
          }
        }
      }

      // Insert rows
      StringBuilder ins = new StringBuilder("INSERT INTO ").append(tempTable).append(" VALUES (");
      for (int i = 0; i < data.getColumnNames().size(); i++) {
        if (i > 0) ins.append(", ");
        ins.append("?");
      }
      ins.append(")");

      int[] nullCounts = new int[data.getColumnNames().size()];
      int[] valueCounts = new int[data.getColumnNames().size()];

      try (PreparedStatement ps = conn.prepareStatement(ins.toString())) {
        for (List<Object> row : data.getRows()) {
          for (int i = 0; i < data.getColumnNames().size(); i++) {
            Object val = row.size() > i ? row.get(i) : null;
            String expectedType = data.getColumnTypes().get(i);
            boolean wasNull = setParameter(ps, i + 1, val, expectedType);
            if (wasNull) {
              nullCounts[i]++;
            } else {
              valueCounts[i]++;
            }
          }
          ps.addBatch();
        }
        ps.executeBatch();
      }

      // Verify data types after insertion
      LOGGER.info("=== VERIFYING DATA TYPES AFTER INSERT ===");
      if (data.getColumnNames().size() > 10) {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT typeof(" + escapeIdent(data.getColumnNames().get(10)) + ") as type FROM " + tempTable + " LIMIT 1")) {
          if (rs.next()) {
            LOGGER.info("Sample column type after insert: " + data.getColumnNames().get(10) + " = " + rs.getString("type"));
          }
        }
      }

      // Log columns with high NULL ratio
      for (int i = 0; i < data.getColumnNames().size(); i++) {
        String colType = toDuckDBType(data.getColumnTypes().get(i));
        if (colType.startsWith("DECIMAL") || colType.equals("INTEGER") || colType.equals("BIGINT")) {
          LOGGER.info("Column " + data.getColumnNames().get(i) + " (" + colType + "): " +
                     valueCounts[i] + " values, " + nullCounts[i] + " nulls");
        }
      }

      // Check if any numeric columns have ALL NULLs - if so, we need to insert a dummy row
      // to force the correct type in Parquet
      boolean needsDummyRow = false;
      for (int i = 0; i < data.getColumnNames().size(); i++) {
        String colType = toDuckDBType(data.getColumnTypes().get(i));
        if ((colType.startsWith("DECIMAL") || colType.equals("INTEGER") || colType.equals("BIGINT"))
            && valueCounts[i] == 0 && nullCounts[i] > 0) {
          needsDummyRow = true;
          LOGGER.warn("Column " + data.getColumnNames().get(i) + " has ALL NULL values - will insert dummy row");
        }
      }

      // Add a dummy row marker column if needed
      String exportQuery;
      if (needsDummyRow) {
        // First, add a marker column to identify the dummy row
        String alterSql = "ALTER TABLE " + tempTable + " ADD COLUMN _dummy_marker BOOLEAN DEFAULT FALSE";
        try (Statement st = conn.createStatement()) {
          st.execute(alterSql);
        }

        // Insert a dummy row with actual values for all columns
        StringBuilder dummyIns = new StringBuilder("INSERT INTO ").append(tempTable).append(" (");
        StringBuilder dummyVals = new StringBuilder(") VALUES (");
        for (int i = 0; i < data.getColumnNames().size(); i++) {
          if (i > 0) {
            dummyIns.append(", ");
            dummyVals.append(", ");
          }
          dummyIns.append(escapeIdent(data.getColumnNames().get(i)));
          String colType = toDuckDBType(data.getColumnTypes().get(i));
          // Add appropriate dummy value based on type
          if (colType.startsWith("DECIMAL")) {
            dummyVals.append("0.0");
          } else if (colType.equals("INTEGER") || colType.equals("BIGINT")) {
            dummyVals.append("0");
          } else if (colType.equals("DATE")) {
            dummyVals.append("'2000-01-01'");
          } else if (colType.equals("TIMESTAMP")) {
            dummyVals.append("'2000-01-01 00:00:00'");
          } else if (colType.equals("BOOLEAN")) {
            dummyVals.append("FALSE");
          } else {
            dummyVals.append("''");
          }
        }
        dummyIns.append(", _dummy_marker").append(dummyVals).append(", TRUE)");

        try (Statement st = conn.createStatement()) {
          st.execute(dummyIns.toString());
        }
        LOGGER.info("Inserted dummy row to force correct types");

        // Export excluding the dummy row
        exportQuery = "COPY (SELECT " + buildColumnList(data.getColumnNames()) +
                     " FROM " + tempTable + " WHERE _dummy_marker = FALSE OR _dummy_marker IS NULL) TO '" +
                     file.getAbsolutePath().replace("'", "''") + "' (FORMAT PARQUET)";
      } else {
        exportQuery = "COPY (SELECT * FROM " + tempTable + ") TO '" +
                     file.getAbsolutePath().replace("'", "''") + "' (FORMAT PARQUET)";
      }

      LOGGER.info("=== BUILDING SELECT (v4) ===");
      LOGGER.info("Export query: " + exportQuery);

      try (Statement st = conn.createStatement()) {
        st.execute(exportQuery);
      }

      LOGGER.info("Parquet file saved: " + file.getAbsolutePath());
    }
  }

  private String buildColumnList(List<String> columnNames) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < columnNames.size(); i++) {
      if (i > 0) sb.append(", ");
      sb.append(escapeIdent(columnNames.get(i)));
    }
    return sb.toString();
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

