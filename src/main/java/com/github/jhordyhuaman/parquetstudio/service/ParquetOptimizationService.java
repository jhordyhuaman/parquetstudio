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

import com.intellij.openapi.diagnostic.Logger;
import java.io.File;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Fragments a Parquet file into multiple part files, and consolidates multiple part files
 * back into one, using DuckDB's LIMIT/OFFSET loop (no version-dependent COPY options).
 */
public class ParquetOptimizationService {
  private static final Logger LOGGER = Logger.getInstance(ParquetOptimizationService.class);
  private static final String DUCKDB_JDBC_URL = "jdbc:duckdb:";
  private static volatile boolean driverLoaded = false;

  /** Criteria for deciding rows-per-part when fragmenting. */
  public enum FragmentCriterion {
    NUM_FILES,
    ROWS_PER_FILE,
    APPROX_MB_PER_FILE
  }

  private static synchronized void ensureDriverLoaded() throws SQLException {
    if (driverLoaded) {
      return;
    }
    try {
      Class<?> driverClass = Class.forName("org.duckdb.DuckDBDriver");
      Driver driver = (Driver) driverClass.getDeclaredConstructor().newInstance();
      DriverManager.registerDriver(driver);
      driverLoaded = true;
      LOGGER.info("DuckDB JDBC driver loaded");
    } catch (Exception e) {
      LOGGER.error("Failed to load DuckDB JDBC driver", e);
      throw new SQLException(
          "DuckDB JDBC driver could not be loaded: " + e.getMessage()
              + ". Try restarting the IDE.", e);
    }
  }

  /**
   * Splits {@code source} into ZSTD-compressed part files inside {@code destDir}, named
   * {@code part-00000.parquet}, {@code part-00001.parquet}, ...
   *
   * @return the created files, in order.
   */
  public List<File> fragment(File source, File destDir, FragmentCriterion criterion, long value)
      throws Exception {
    ensureDriverLoaded();

    File readable = SafeParquetPath.toReadable(source);
    List<File> created = new ArrayList<>();
    try (Connection conn = DriverManager.getConnection(DUCKDB_JDBC_URL)) {
      long totalRows = countRows(conn, readable);
      long rowsPerPart = computeRowsPerPart(criterion, value, totalRows, source.length());
      if (rowsPerPart <= 0) {
        rowsPerPart = 1;
      }

      long partIndex = 0;
      long offset = 0;
      while (offset < totalRows) {
        File part = new File(destDir, String.format(Locale.ROOT, "part-%05d.parquet", partIndex));
        String sql =
            "COPY (SELECT * FROM read_parquet(?) LIMIT " + rowsPerPart + " OFFSET " + offset
                + ") TO '" + part.getAbsolutePath().replace("'", "''")
                + "' (FORMAT PARQUET, COMPRESSION ZSTD)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
          ps.setString(1, readable.getAbsolutePath());
          ps.execute();
        }
        created.add(part);
        offset += rowsPerPart;
        partIndex++;
      }
      return created;
    } finally {
      if (SafeParquetPath.isTempCopy(source, readable)) {
        if (!readable.delete()) {
          LOGGER.warn("Could not delete temp copy: " + readable.getAbsolutePath());
        }
      }
    }
  }

  private long computeRowsPerPart(
      FragmentCriterion criterion, long value, long totalRows, long sourceFileSizeBytes) {
    switch (criterion) {
      case NUM_FILES:
        long n = Math.min(Math.max(value, 1), Math.max(totalRows, 1));
        return (long) Math.ceil((double) totalRows / (double) n);
      case ROWS_PER_FILE:
        return value;
      case APPROX_MB_PER_FILE:
        long bytesPerRow = Math.max(1, sourceFileSizeBytes / Math.max(1, totalRows));
        long targetBytes = value * 1024L * 1024L;
        return Math.max(1, targetBytes / bytesPerRow);
      default:
        throw new IllegalArgumentException("Unknown fragment criterion: " + criterion);
    }
  }

  private long countRows(Connection conn, File readable) throws SQLException {
    String sql = "SELECT COUNT(*) FROM read_parquet(?)";
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, readable.getAbsolutePath());
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  /**
   * Lists {@code *.parquet} files directly inside {@code dir}, sorted by name.
   */
  public List<File> listParquetFiles(File dir) {
    File[] files = dir.listFiles((d, name) -> name.toLowerCase(Locale.ROOT).endsWith(".parquet"));
    if (files == null) {
      return new ArrayList<>();
    }
    List<File> result = new ArrayList<>(Arrays.asList(files));
    result.sort(Comparator.comparing(File::getName));
    return result;
  }

  /**
   * Merges {@code sources} (schema-validated) into {@code output}, ZSTD-compressed.
   *
   * @return the total number of rows written.
   */
  public long consolidate(List<File> sources, File output) throws Exception {
    if (sources == null || sources.isEmpty()) {
      throw new IllegalArgumentException("No source files to consolidate");
    }

    ensureDriverLoaded();

    try (Connection conn = DriverManager.getConnection(DUCKDB_JDBC_URL)) {
      List<String> firstColumnNames = null;
      List<String> firstColumnTypes = null;
      for (File source : sources) {
        List<String> columnNames = new ArrayList<>();
        List<String> columnTypes = new ArrayList<>();
        readSchema(conn, source, columnNames, columnTypes);
        if (firstColumnNames == null) {
          firstColumnNames = columnNames;
          firstColumnTypes = columnTypes;
          continue;
        }
        String mismatch = firstMismatch(firstColumnNames, firstColumnTypes, columnNames, columnTypes);
        if (mismatch != null) {
          throw new IllegalArgumentException(
              "Schema mismatch in file '" + source.getName() + "': " + mismatch);
        }
      }

      StringBuilder fileList = new StringBuilder();
      for (int i = 0; i < sources.size(); i++) {
        if (i > 0) fileList.append(", ");
        fileList.append('\'').append(sources.get(i).getAbsolutePath().replace("'", "''")).append('\'');
      }

      long[] totalRows = new long[1];
      SafeParquetPath.writeThenMove(
          output,
          target -> {
            String copySql =
                "COPY (SELECT * FROM read_parquet([" + fileList + "])) TO '"
                    + target.getAbsolutePath().replace("'", "''")
                    + "' (FORMAT PARQUET, COMPRESSION ZSTD)";
            try (Statement st = conn.createStatement()) {
              st.execute(copySql);
            }
            totalRows[0] = countRows(conn, target);
          });
      return totalRows[0];
    }
  }

  private void readSchema(
      Connection conn, File source, List<String> columnNames, List<String> columnTypes)
      throws Exception {
    File readable = SafeParquetPath.toReadable(source);
    try {
      String sql = "SELECT * FROM read_parquet(?) LIMIT 0";
      try (PreparedStatement ps = conn.prepareStatement(sql)) {
        ps.setString(1, readable.getAbsolutePath());
        try (ResultSet rs = ps.executeQuery()) {
          ResultSetMetaData md = rs.getMetaData();
          int n = md.getColumnCount();
          for (int i = 1; i <= n; i++) {
            columnNames.add(md.getColumnLabel(i));
            columnTypes.add(md.getColumnTypeName(i).toUpperCase(Locale.ROOT));
          }
        }
      }
    } finally {
      if (SafeParquetPath.isTempCopy(source, readable)) {
        if (!readable.delete()) {
          LOGGER.warn("Could not delete temp copy: " + readable.getAbsolutePath());
        }
      }
    }
  }

  private String firstMismatch(
      List<String> namesA, List<String> typesA, List<String> namesB, List<String> typesB) {
    if (namesA.size() != namesB.size()) {
      return "expected " + namesA.size() + " columns, found " + namesB.size();
    }
    for (int i = 0; i < namesA.size(); i++) {
      if (!namesA.get(i).equals(namesB.get(i))) {
        return "column '" + namesB.get(i) + "' does not match expected column '" + namesA.get(i) + "'";
      }
      if (!typesA.get(i).equals(typesB.get(i))) {
        return "column '" + namesA.get(i) + "' type " + typesB.get(i)
            + " does not match expected type " + typesA.get(i);
      }
    }
    return null;
  }
}
