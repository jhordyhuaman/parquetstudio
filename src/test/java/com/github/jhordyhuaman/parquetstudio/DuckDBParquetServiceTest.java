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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.jhordyhuaman.parquetstudio.model.ParquetData;
import com.github.jhordyhuaman.parquetstudio.service.DuckDBParquetService;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DuckDBParquetServiceTest {

  @TempDir
  Path tempDir;

  private DuckDBParquetService service;
  private File testParquetFile;
  private File outputParquetFile;

  @BeforeEach
  void setUp() {
    service = new DuckDBParquetService();
    testParquetFile = new File(tempDir.toFile(), "test.parquet");
    outputParquetFile = new File(tempDir.toFile(), "output.parquet");
  }

  @AfterEach
  void tearDown() {
    // Cleanup if needed
  }

  @Test
  @DisplayName("Should throw exception when loading non-existent file")
  void testLoadNonExistentFile() {
    File nonExistentFile = new File(tempDir.toFile(), "nonexistent.parquet");

    // The service will throw an exception (could be SQLException or wrapped by IntelliJ Logger)
    assertThatThrownBy(() -> service.loadParquet(nonExistentFile))
        .isNotNull();
  }

  @Test
  @DisplayName("Should throw exception when saving with empty columns")
  void testSaveWithEmptyColumns() {
    List<String> emptyColumns = new ArrayList<>();
    List<String> emptyTypes = new ArrayList<>();
    List<List<Object>> emptyRows = new ArrayList<>();
    ParquetData emptyData = new ParquetData(emptyColumns, emptyTypes, emptyRows);

    assertThatThrownBy(() -> service.saveParquet(outputParquetFile, emptyData))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("No columns to save");
  }

  @Test
  @DisplayName("Should create ParquetData structure correctly")
  void testParquetDataStructure() {
    // This test verifies the service can work with ParquetData
    List<String> columnNames = new ArrayList<>();
    columnNames.add("id");
    columnNames.add("name");

    List<String> columnTypes = new ArrayList<>();
    columnTypes.add("INTEGER");
    columnTypes.add("VARCHAR");

    List<List<Object>> rows = new ArrayList<>();
    List<Object> row1 = new ArrayList<>();
    row1.add(1);
    row1.add("Test");
    rows.add(row1);

    ParquetData data = new ParquetData(columnNames, columnTypes, rows);

    assertThat(data.getColumnNames()).hasSize(2);
    assertThat(data.getColumnTypes()).hasSize(2);
    assertThat(data.getRows()).hasSize(1);
  }

  @Test
  @DisplayName("Should handle various data types in ParquetData")
  void testVariousDataTypes() {
    List<String> columnNames = new ArrayList<>();
    columnNames.add("int_col");
    columnNames.add("double_col");
    columnNames.add("bool_col");
    columnNames.add("string_col");

    List<String> columnTypes = new ArrayList<>();
    columnTypes.add("INTEGER");
    columnTypes.add("DOUBLE");
    columnTypes.add("BOOLEAN");
    columnTypes.add("VARCHAR");

    List<List<Object>> rows = new ArrayList<>();
    List<Object> row = new ArrayList<>();
    row.add(42);
    row.add(3.14);
    row.add(true);
    row.add("test");
    rows.add(row);

    ParquetData data = new ParquetData(columnNames, columnTypes, rows);

    assertThat(data.getRows().get(0).get(0)).isInstanceOf(Integer.class);
    assertThat(data.getRows().get(0).get(1)).isInstanceOf(Double.class);
    assertThat(data.getRows().get(0).get(2)).isInstanceOf(Boolean.class);
    assertThat(data.getRows().get(0).get(3)).isInstanceOf(String.class);
  }

  @Test
  @DisplayName("Should handle null values in rows")
  void testNullValues() {
    List<String> columnNames = new ArrayList<>();
    columnNames.add("nullable_col");

    List<String> columnTypes = new ArrayList<>();
    columnTypes.add("VARCHAR");

    List<List<Object>> rows = new ArrayList<>();
    List<Object> row = new ArrayList<>();
    row.add(null);
    rows.add(row);

    ParquetData data = new ParquetData(columnNames, columnTypes, rows);

    assertThat(data.getRows().get(0).get(0)).isNull();
  }

  @Test
  @DisplayName("Should handle empty rows")
  void testEmptyRows() {
    List<String> columnNames = new ArrayList<>();
    columnNames.add("col1");

    List<String> columnTypes = new ArrayList<>();
    columnTypes.add("INTEGER");

    List<List<Object>> emptyRows = new ArrayList<>();

    ParquetData data = new ParquetData(columnNames, columnTypes, emptyRows);

    assertThat(data.getRows()).isEmpty();
    assertThat(data.getColumnNames()).hasSize(1);
  }

  @TempDir
  java.nio.file.Path safePathTempDir;

  @Test
  void loadsParquetFileWhoseNameContainsGlobCharacters() throws Exception {
    java.io.File source = new java.io.File("src/test/resources/parquet/logical_date.parquet");
    java.io.File globNamed = safePathTempDir.resolve("copia [1].parquet").toFile();
    java.nio.file.Files.copy(source.toPath(), globNamed.toPath());

    ParquetData data = new DuckDBParquetService().loadParquet(globNamed);

    assertThat(data.getRows()).isNotEmpty();
    assertThat(data.getColumnNames()).isNotEmpty();
  }

  @Test
  void savesParquetFileWhoseNameContainsGlobCharacters() throws Exception {
    java.io.File source = new java.io.File("src/test/resources/parquet/logical_date.parquet");
    DuckDBParquetService service = new DuckDBParquetService();
    ParquetData data = service.loadParquet(source);

    java.io.File target = safePathTempDir.resolve("salida [x].parquet").toFile();
    service.saveParquet(target, data);

    assertThat(target).exists();
    ParquetData reloaded = service.loadParquet(target);
    assertThat(reloaded.getRows()).hasSameSizeAs(data.getRows());
  }
}

