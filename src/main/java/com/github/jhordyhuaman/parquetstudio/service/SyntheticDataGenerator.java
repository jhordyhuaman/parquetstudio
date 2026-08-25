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
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generates realistic synthetic rows for a given schema, purely offline and
 * deterministic when a seed is provided. No AI/LLM involved.
 */
public class SyntheticDataGenerator {

  private final Logger LOGGER = Logger.getInstance(SyntheticDataGenerator.class);

  private static final Pattern DECIMAL_PATTERN =
      Pattern.compile("DECIMAL\\((\\d+)\\s*,\\s*(\\d+)\\)", Pattern.CASE_INSENSITIVE);

  private static final LocalDate DATE_MIN = LocalDate.of(2020, 1, 1);

  private static final String[] FIRST_NAMES = {
      "James", "Mary", "John", "Patricia", "Robert", "Jennifer", "Michael", "Linda",
      "William", "Elizabeth", "David", "Barbara", "Richard", "Susan", "Joseph", "Jessica",
      "Thomas", "Sarah", "Charles", "Karen", "Carlos", "Maria", "Juan", "Ana",
      "Luis", "Laura", "Jose", "Carmen", "Miguel", "Rosa"
  };

  private static final String[] LAST_NAMES = {
      "Smith", "Johnson", "Williams", "Brown", "Jones", "Garcia", "Miller", "Davis",
      "Rodriguez", "Martinez", "Hernandez", "Lopez", "Gonzalez", "Perez", "Sanchez",
      "Ramirez", "Torres", "Flores", "Rivera", "Gomez"
  };

  private static final String[] COUNTRY_CODES = {
      "US", "MX", "CO", "AR", "ES", "BR", "CL", "PE", "EC", "UY",
      "CA", "FR", "DE", "IT", "GB", "PT", "PA", "CR", "BO", "PY"
  };

  private static final String ALNUM_UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
  private static final String ALNUM_LOWER = "abcdefghijklmnopqrstuvwxyz0123456789";

  public GenerationResult generate(List<String> columnNames, List<String> columnTypes,
                                    int rowCount, Long seed, double nullRatio) {
    Random random = seed == null ? new Random() : new Random(seed);
    List<String> warnings = new ArrayList<>();
    List<List<Object>> rows = new ArrayList<>(rowCount);

    int columnCount = columnNames.size();
    boolean[] warned = new boolean[columnCount];

    for (int r = 0; r < rowCount; r++) {
      List<Object> row = new ArrayList<>(columnCount);
      for (int c = 0; c < columnCount; c++) {
        String name = columnNames.get(c);
        String type = columnTypes.get(c);

        if (nullRatio > 0.0 && random.nextDouble() < nullRatio) {
          row.add(null);
          continue;
        }

        Object value = generateValue(name, type, random);
        if (value == UNSUPPORTED) {
          if (!warned[c]) {
            warnings.add(name + ": unsupported type " + type + ", generated NULLs");
            warned[c] = true;
          }
          row.add(null);
        } else {
          row.add(value);
        }
      }
      rows.add(row);
    }

    LOGGER.info("Generated %d synthetic rows for %d columns (warnings: %d)"
        .formatted(rowCount, columnCount, warnings.size()));

    ParquetData data = new ParquetData(new ArrayList<>(columnNames), new ArrayList<>(columnTypes), rows);
    return new GenerationResult(data, warnings);
  }

  private static final Object UNSUPPORTED = new Object();

  private Object generateValue(String columnName, String type, Random random) {
    if (type == null) {
      return UNSUPPORTED;
    }
    String normalizedType = type.trim().toUpperCase(Locale.ROOT);
    String lowerName = columnName == null ? "" : columnName.toLowerCase(Locale.ROOT);

    Matcher decimalMatcher = DECIMAL_PATTERN.matcher(normalizedType);
    if (decimalMatcher.find()) {
      int precision = Integer.parseInt(decimalMatcher.group(1));
      int scale = Integer.parseInt(decimalMatcher.group(2));
      return randomDecimal(random, precision, scale, isAmountLike(lowerName));
    }

    switch (normalizedType) {
      case "INTEGER":
      case "INT":
        return random.nextInt(1_000_000);
      case "BIGINT":
        return (long) (random.nextDouble() * 9_999_999_999L);
      case "DOUBLE":
      case "FLOAT":
        return generateDouble(random, lowerName);
      case "DATE":
        return randomDate(random);
      case "TIMESTAMP":
        return generateTimestamp(random);
      case "BOOLEAN":
        return random.nextBoolean();
      case "VARCHAR":
        return generateVarchar(columnName, lowerName, random);
      default:
        return UNSUPPORTED;
    }
  }

  private boolean isAmountLike(String lowerName) {
    return lowerName.contains("amount") || lowerName.contains("monto")
        || lowerName.contains("importe") || lowerName.contains("price")
        || lowerName.contains("precio");
  }

  private BigDecimal randomDecimal(Random random, int precision, int scale, boolean amountLike) {
    int intDigits = Math.min(Math.max(precision - scale, 0), 7);
    double maxMagnitude = Math.pow(10, intDigits);
    double value;
    if (amountLike) {
      int wholePart = random.nextInt((int) maxMagnitude);
      int cents = random.nextInt(100);
      value = wholePart + cents / 100.0;
    } else {
      value = random.nextDouble() * maxMagnitude;
    }
    double scaleUnit = Math.pow(10, -scale);
    double maxAllowed = maxMagnitude - scaleUnit;
    if (value > maxAllowed) {
      value = maxAllowed;
    }
    if (value < 0) {
      value = 0;
    }
    BigDecimal decimal = BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP);
    return decimal.abs();
  }

  private Object generateDouble(Random random, String lowerName) {
    double value = random.nextDouble() * 1_000_000;
    int decimals = isAmountLike(lowerName) ? 2 : 2 + random.nextInt(3);
    BigDecimal rounded = BigDecimal.valueOf(value).setScale(decimals, RoundingMode.HALF_UP);
    return Math.abs(rounded.doubleValue());
  }

  private LocalDate randomDate(Random random) {
    long daysBetween = ChronoUnit.DAYS.between(DATE_MIN, LocalDate.now());
    if (daysBetween <= 0) {
      return DATE_MIN;
    }
    long offset = (long) (random.nextDouble() * (daysBetween + 1));
    return DATE_MIN.plusDays(offset);
  }

  private LocalDateTime generateTimestamp(Random random) {
    LocalDate date = randomDate(random);
    LocalTime time = LocalTime.of(random.nextInt(24), random.nextInt(60), random.nextInt(60));
    return LocalDateTime.of(date, time);
  }

  private String generateVarchar(String columnName, String lowerName, Random random) {
    if (lowerName.contains("email") || lowerName.contains("correo")) {
      String first = FIRST_NAMES[random.nextInt(FIRST_NAMES.length)].toLowerCase(Locale.ROOT);
      int n = random.nextInt(100);
      return first + n + "@example.com";
    }
    if (lowerName.contains("name") || lowerName.contains("nombre")) {
      String first = FIRST_NAMES[random.nextInt(FIRST_NAMES.length)];
      String last = LAST_NAMES[random.nextInt(LAST_NAMES.length)];
      return first + " " + last;
    }
    if (lowerName.contains("phone") || lowerName.contains("telefono")) {
      StringBuilder sb = new StringBuilder(9);
      for (int i = 0; i < 9; i++) {
        sb.append(random.nextInt(10));
      }
      return sb.toString();
    }
    if (lowerName.contains("country") || lowerName.contains("pais")) {
      return COUNTRY_CODES[random.nextInt(COUNTRY_CODES.length)];
    }
    if (lowerName.contains("date") || lowerName.contains("fecha")) {
      return randomDate(random).toString();
    }
    if (lowerName.contains("id") || lowerName.contains("code")
        || lowerName.contains("codigo") || lowerName.contains("cod")) {
      return randomAlnum(random, ALNUM_UPPER, 8);
    }
    return randomAlnum(random, ALNUM_LOWER, 8 + random.nextInt(9));
  }

  private String randomAlnum(Random random, String alphabet, int length) {
    StringBuilder sb = new StringBuilder(length);
    for (int i = 0; i < length; i++) {
      sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
    }
    return sb.toString();
  }

  /**
   * Result of a generation call: the typed ParquetData plus warnings for
   * columns whose type could not be generated (all-NULL columns).
   */
  public static final class GenerationResult {
    private final ParquetData data;
    private final List<String> warnings;

    public GenerationResult(ParquetData data, List<String> warnings) {
      this.data = data;
      this.warnings = warnings;
    }

    public ParquetData getData() {
      return data;
    }

    public List<String> getWarnings() {
      return warnings;
    }
  }
}
