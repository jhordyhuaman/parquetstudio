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

import com.github.jhordyhuaman.parquetstudio.model.ParquetData;
import com.github.jhordyhuaman.parquetstudio.service.DuckDBParquetService;
import com.github.jhordyhuaman.parquetstudio.service.SyntheticDataGenerator;
import com.github.jhordyhuaman.parquetstudio.service.SyntheticDataGenerator.GenerationResult;
import java.io.File;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end tests for synthetic data generation and round-trip preservation.
 */
class SyntheticDataEndToEndTest {

  @TempDir
  Path tempDir;

  @Test
  @DisplayName("Generate synthetic data, save, and reload preserves schema and row count")
  void roundTripSyntheticDataPreservesSchemaAndRowCount() throws Exception {
    // Load existing fixture to extract schema
    File fixtureFile = new File("src/test/resources/parquet/logical_date.parquet");
    DuckDBParquetService service = new DuckDBParquetService();
    ParquetData fixture = service.loadParquet(fixtureFile);

    // Extract column names and types from fixture
    java.util.List<String> columnNames = fixture.getColumnNames();
    java.util.List<String> columnTypes = fixture.getColumnTypes();

    // Generate 50 rows of synthetic data with seed 42L and 5% nullRatio
    SyntheticDataGenerator generator = new SyntheticDataGenerator();
    GenerationResult result = generator.generate(columnNames, columnTypes, 50, 42L, 0.05);
    ParquetData generatedData = result.getData();

    // Verify generation succeeded
    assertThat(generatedData.getRows()).hasSize(50);
    assertThat(generatedData.getColumnNames()).isEqualTo(columnNames);
    assertThat(generatedData.getColumnTypes()).isEqualTo(columnTypes);

    // Save to temp file
    File tempFile = tempDir.resolve("synthetic_roundtrip.parquet").toFile();
    service.saveParquet(tempFile, generatedData);
    assertThat(tempFile).exists();

    // Reload from temp file
    ParquetData reloaded = service.loadParquet(tempFile);

    // Assert row count and schema are preserved
    assertThat(reloaded.getRows()).hasSize(50);
    assertThat(reloaded.getColumnNames()).isEqualTo(columnNames);
    assertThat(reloaded.getColumnTypes()).isEqualTo(columnTypes);
  }
}
