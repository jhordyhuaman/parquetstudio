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
import com.github.jhordyhuaman.parquetstudio.service.SyntheticDataGenerator;
import com.github.jhordyhuaman.parquetstudio.service.SyntheticDataGenerator.GenerationResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
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
}
