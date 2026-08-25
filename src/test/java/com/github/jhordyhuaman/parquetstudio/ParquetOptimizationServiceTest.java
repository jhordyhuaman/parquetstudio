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
import com.github.jhordyhuaman.parquetstudio.service.ParquetOptimizationService;
import com.github.jhordyhuaman.parquetstudio.service.ParquetOptimizationService.FragmentCriterion;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ParquetOptimizationServiceTest {

  @TempDir Path tempDir;

  private DuckDBParquetService duckDBParquetService;
  private ParquetOptimizationService service;
  private File fixture;
  private static final int FIXTURE_ROWS = 10;

  @BeforeEach
  void setUp() throws Exception {
    duckDBParquetService = new DuckDBParquetService();
    service = new ParquetOptimizationService();
    fixture = new File(tempDir.toFile(), "fixture.parquet");
    duckDBParquetService.saveParquet(fixture, buildFixtureData(FIXTURE_ROWS), "ZSTD");
  }

  private ParquetData buildFixtureData(int rowCount) {
    List<String> columnNames = List.of("id", "name");
    List<String> columnTypes = List.of("INTEGER", "VARCHAR");
    List<List<Object>> rows = new ArrayList<>();
    for (int i = 0; i < rowCount; i++) {
      List<Object> row = new ArrayList<>();
      row.add(i);
      row.add("name-" + i);
      rows.add(row);
    }
    return new ParquetData(columnNames, columnTypes, rows);
  }

  @Test
  @DisplayName("fragmentIntoNFilesPreservesRowsAndSchema")
  void fragmentIntoNFilesPreservesRowsAndSchema() throws Exception {
    File destDir = new File(tempDir.toFile(), "frag-n");
    destDir.mkdirs();

    List<File> parts = service.fragment(fixture, destDir, FragmentCriterion.NUM_FILES, 3);

    assertThat(parts).hasSize(3);
    for (File part : parts) {
      assertThat(part).exists();
    }

    ParquetData original = duckDBParquetService.loadParquet(fixture);
    int totalRows = 0;
    for (File part : parts) {
      ParquetData partData = duckDBParquetService.loadParquet(part);
      totalRows += partData.getRows().size();
      assertThat(partData.getColumnNames()).isEqualTo(original.getColumnNames());
      assertThat(partData.getColumnTypes()).isEqualTo(original.getColumnTypes());
    }
    assertThat(totalRows).isEqualTo(FIXTURE_ROWS);
  }

  @Test
  @DisplayName("fragmentByRowsHonorsChunkSize")
  void fragmentByRowsHonorsChunkSize() throws Exception {
    File destDir = new File(tempDir.toFile(), "frag-rows");
    destDir.mkdirs();

    List<File> parts = service.fragment(fixture, destDir, FragmentCriterion.ROWS_PER_FILE, 2);

    for (int i = 0; i < parts.size() - 1; i++) {
      ParquetData partData = duckDBParquetService.loadParquet(parts.get(i));
      assertThat(partData.getRows()).hasSize(2);
    }
    ParquetData lastPart = duckDBParquetService.loadParquet(parts.get(parts.size() - 1));
    assertThat(lastPart.getRows().size()).isLessThanOrEqualTo(2);
  }

  @Test
  @DisplayName("fragmentCapsPartsAtRowCount")
  void fragmentCapsPartsAtRowCount() throws Exception {
    File destDir = new File(tempDir.toFile(), "frag-cap");
    destDir.mkdirs();

    List<File> parts =
        service.fragment(fixture, destDir, FragmentCriterion.NUM_FILES, FIXTURE_ROWS + 5);

    assertThat(parts).hasSize(FIXTURE_ROWS);
  }

  @Test
  @DisplayName("consolidateRoundTrip")
  void consolidateRoundTrip() throws Exception {
    File destDir = new File(tempDir.toFile(), "frag-for-consolidate");
    destDir.mkdirs();
    List<File> parts = service.fragment(fixture, destDir, FragmentCriterion.NUM_FILES, 3);

    File output = new File(tempDir.toFile(), "consolidated.parquet");
    long totalRows = service.consolidate(parts, output);

    assertThat(totalRows).isEqualTo(FIXTURE_ROWS);

    ParquetData original = duckDBParquetService.loadParquet(fixture);
    ParquetData consolidated = duckDBParquetService.loadParquet(output);
    assertThat(consolidated.getRows()).hasSize(FIXTURE_ROWS);
    assertThat(consolidated.getColumnNames()).isEqualTo(original.getColumnNames());
    assertThat(consolidated.getColumnTypes()).isEqualTo(original.getColumnTypes());
  }

  @Test
  @DisplayName("consolidateRejectsMismatchedSchemas")
  void consolidateRejectsMismatchedSchemas() throws Exception {
    File fixtureA = new File(tempDir.toFile(), "fixtureA.parquet");
    duckDBParquetService.saveParquet(fixtureA, buildFixtureData(5), "ZSTD");

    List<String> mismatchedColumnNames = List.of("id");
    List<String> mismatchedColumnTypes = List.of("INTEGER");
    List<List<Object>> mismatchedRows = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      mismatchedRows.add(new ArrayList<>(List.of((Object) i)));
    }
    File fixtureB = new File(tempDir.toFile(), "fixtureB-dropped-column.parquet");
    duckDBParquetService.saveParquet(
        fixtureB,
        new ParquetData(mismatchedColumnNames, mismatchedColumnTypes, mismatchedRows),
        "ZSTD");

    File output = new File(tempDir.toFile(), "should-not-exist.parquet");

    assertThatThrownBy(() -> service.consolidate(List.of(fixtureA, fixtureB), output))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(fixtureB.getName());
  }

  @Test
  @DisplayName("consolidateRejectsEmptyList")
  void consolidateRejectsEmptyList() {
    File output = new File(tempDir.toFile(), "empty-output.parquet");

    assertThatThrownBy(() -> service.consolidate(new ArrayList<>(), output))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
