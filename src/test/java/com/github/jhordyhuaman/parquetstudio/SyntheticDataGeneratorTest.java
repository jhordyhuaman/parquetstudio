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

import com.github.jhordyhuaman.parquetstudio.model.ParquetData;
import com.github.jhordyhuaman.parquetstudio.model.SchemaStructure;
import com.github.jhordyhuaman.parquetstudio.service.SyntheticDataGenerator;
import com.github.jhordyhuaman.parquetstudio.service.SyntheticDataGenerator.GenerationResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileWriter;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for SyntheticDataGenerator.
 */
public class SyntheticDataGeneratorTest {

  private final SyntheticDataGenerator generator = new SyntheticDataGenerator();

  @Test
  void generatesExactRowCountAndTypedValues() {
    List<String> names = Arrays.asList("amount", "created_at", "quantity");
    List<String> types = Arrays.asList("DECIMAL(10,2)", "DATE", "INTEGER");

    GenerationResult result = generator.generate(names, types, 20, 42L, 0.0);
    ParquetData data = result.getData();

    assertThat(data.getRows()).hasSize(20);

    LocalDate min = LocalDate.of(2020, 1, 1);
    LocalDate max = LocalDate.now();

    for (List<Object> row : data.getRows()) {
      Object amount = row.get(0);
      assertThat(amount).isInstanceOf(BigDecimal.class);
      assertThat(((BigDecimal) amount).scale()).isEqualTo(2);

      Object date = row.get(1);
      assertThat(date).isInstanceOf(LocalDate.class);
      assertThat((LocalDate) date).isBetween(min, max);

      Object quantity = row.get(2);
      assertThat(quantity).isInstanceOf(Integer.class);
      assertThat((Integer) quantity).isBetween(0, 999_999);
    }
  }

  @Test
  void sameSeedIsDeterministic() {
    List<String> names = Arrays.asList("id", "amount", "created_at", "active", "name");
    List<String> types = Arrays.asList("VARCHAR", "DECIMAL(10,2)", "TIMESTAMP", "BOOLEAN", "VARCHAR");

    GenerationResult first = generator.generate(names, types, 30, 12345L, 0.05);
    GenerationResult second = generator.generate(names, types, 30, 12345L, 0.05);

    assertThat(first.getData().getRows()).isEqualTo(second.getData().getRows());
  }

  @Test
  void nullRatioZeroAndOne() {
    List<String> names = Arrays.asList("amount", "flag");
    List<String> types = Arrays.asList("DOUBLE", "BOOLEAN");

    GenerationResult zero = generator.generate(names, types, 50, 1L, 0.0);
    for (List<Object> row : zero.getData().getRows()) {
      assertThat(row).doesNotContainNull();
    }

    GenerationResult all = generator.generate(names, types, 50, 1L, 1.0);
    for (List<Object> row : all.getData().getRows()) {
      assertThat(row).containsOnlyNulls();
    }
  }

  @Test
  void emailHeuristicProducesAtSign() {
    List<String> names = Arrays.asList("customer_email");
    List<String> types = Arrays.asList("VARCHAR");

    GenerationResult result = generator.generate(names, types, 15, 7L, 0.0);
    for (List<Object> row : result.getData().getRows()) {
      assertThat((String) row.get(0)).contains("@");
    }
  }

  @Test
  void codeHeuristicUppercaseAlnum() {
    List<String> names = Arrays.asList("g_entific_id");
    List<String> types = Arrays.asList("VARCHAR");

    GenerationResult result = generator.generate(names, types, 15, 9L, 0.0);
    for (List<Object> row : result.getData().getRows()) {
      String code = (String) row.get(0);
      assertThat(code).matches("[A-Z0-9]+");
    }
  }

  @Test
  void amountHeuristicPositiveTwoDecimals() {
    List<String> namesDouble = Arrays.asList("total_price");
    List<String> typesDouble = Arrays.asList("DOUBLE");

    GenerationResult doubleResult = generator.generate(namesDouble, typesDouble, 30, 21L, 0.0);
    for (List<Object> row : doubleResult.getData().getRows()) {
      double value = (Double) row.get(0);
      assertThat(value).isGreaterThan(0.0);
      BigDecimal asDecimal = BigDecimal.valueOf(value);
      assertThat(asDecimal.scale()).isLessThanOrEqualTo(2);
    }

    List<String> namesDecimal = Arrays.asList("importe_total");
    List<String> typesDecimal = Arrays.asList("DECIMAL(10,4)");

    GenerationResult decimalResult = generator.generate(namesDecimal, typesDecimal, 30, 22L, 0.0);
    for (List<Object> row : decimalResult.getData().getRows()) {
      BigDecimal value = (BigDecimal) row.get(0);
      assertThat(value.signum()).isGreaterThanOrEqualTo(0);
      assertThat(value.scale()).isEqualTo(4);
    }
  }

  @Test
  void decimalNeverExceedsPrecision() {
    List<String> names = Arrays.asList("small_decimal");
    List<String> types = Arrays.asList("DECIMAL(3,2)");

    for (long seed = 0; seed < 50; seed++) {
      GenerationResult result = generator.generate(names, types, 100, seed, 0.0);
      for (List<Object> row : result.getData().getRows()) {
        BigDecimal value = (BigDecimal) row.get(0);
        assertThat(value.precision()).isLessThanOrEqualTo(3);
        assertThat(value.abs().compareTo(BigDecimal.TEN)).isLessThan(0);
      }
    }
  }

  @Test
  void unknownTypeYieldsNullsAndWarning() {
    List<String> names = Arrays.asList("payload");
    List<String> types = Arrays.asList("STRUCT(x INT)");

    GenerationResult result = generator.generate(names, types, 10, 3L, 0.0);

    for (List<Object> row : result.getData().getRows()) {
      assertThat(row.get(0)).isNull();
    }

    assertThat(result.getWarnings())
        .contains("payload: unsupported type STRUCT(x INT), generated NULLs");
  }

  @Test
  void schemaFileTypesGenerateNonNullValues(@TempDir Path tempDir) throws Exception {
    String schemaJson = "{"
        + "\"partitions\": [],"
        + "\"fields\": ["
        + "{\"name\": \"customer_name\", \"type\": \"utf8\"},"
        + "{\"name\": \"quantity\", \"type\": \"int32\"},"
        + "{\"name\": \"amount\", \"type\": \"decimal(10,2)\"}"
        + "]"
        + "}";
    Path schemaFile = tempDir.resolve("schema.json");
    try (FileWriter writer = new FileWriter(schemaFile.toFile())) {
      writer.write(schemaJson);
    }

    SchemaStructure schemaStructure = SchemaStructure.schemaFromFile(schemaFile.toAbsolutePath().toString());
    schemaStructure.changesTypesFields();

    List<String> names = new ArrayList<>();
    List<String> types = new ArrayList<>();
    for (var field : schemaStructure.fields) {
      names.add(field.name);
      types.add(String.valueOf(field.type));
    }

    GenerationResult result = generator.generate(names, types, 20, 7L, 0.0);

    assertThat(result.getWarnings()).isEmpty();
    for (List<Object> row : result.getData().getRows()) {
      assertThat(row).doesNotContainNull();
    }
  }
}
