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

import com.github.jhordyhuaman.parquetstudio.model.SchemaValidationResult;
import com.github.jhordyhuaman.parquetstudio.service.SchemaValidationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SchemaValidationService.
 */
public class SchemaValidationServiceTest {

    private SchemaValidationService service;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        service = new SchemaValidationService();
    }

    @Test
    @DisplayName("Should validate matching columns successfully")
    void shouldValidateMatchingColumns() throws IOException {
        // Create a simple schema file
        String schemaContent = """
            {
              "name": "test_schema",
              "type": "record",
              "fields": [
                {"name": "id", "type": "string"},
                {"name": "amount", "type": "decimal(10,2)"},
                {"name": "created_date", "type": "date"}
              ]
            }
            """;

        File schemaFile = tempDir.resolve("test.schema").toFile();
        Files.writeString(schemaFile.toPath(), schemaContent);

        List<String> parquetColumns = Arrays.asList("id", "amount", "created_date");
        List<String> parquetTypes = Arrays.asList("VARCHAR", "DECIMAL(10,2)", "DATE");

        SchemaValidationResult result = service.validate(schemaFile, parquetColumns, parquetTypes);

        assertTrue(result.isFullyValid());
        assertEquals(3, result.getValidColumns().size());
        assertEquals(0, result.getTypeMismatchColumns().size());
        assertEquals(0, result.getMissingInParquet().size());
        assertEquals(0, result.getExtraInParquet().size());
    }

    @Test
    @DisplayName("Should detect type mismatches")
    void shouldDetectTypeMismatches() throws IOException {
        String schemaContent = """
            {
              "name": "test_schema",
              "type": "record",
              "fields": [
                {"name": "id", "type": "string"},
                {"name": "count", "type": "int32"}
              ]
            }
            """;

        File schemaFile = tempDir.resolve("test.schema").toFile();
        Files.writeString(schemaFile.toPath(), schemaContent);

        List<String> parquetColumns = Arrays.asList("id", "count");
        List<String> parquetTypes = Arrays.asList("VARCHAR", "VARCHAR"); // count should be INTEGER

        SchemaValidationResult result = service.validate(schemaFile, parquetColumns, parquetTypes);

        assertFalse(result.isFullyValid());
        assertEquals(1, result.getValidColumns().size()); // id matches
        assertEquals(1, result.getTypeMismatchColumns().size()); // count mismatch
    }

    @Test
    @DisplayName("Should detect missing columns in Parquet")
    void shouldDetectMissingColumns() throws IOException {
        String schemaContent = """
            {
              "name": "test_schema",
              "type": "record",
              "fields": [
                {"name": "id", "type": "string"},
                {"name": "name", "type": "string"},
                {"name": "email", "type": "string"}
              ]
            }
            """;

        File schemaFile = tempDir.resolve("test.schema").toFile();
        Files.writeString(schemaFile.toPath(), schemaContent);

        List<String> parquetColumns = Arrays.asList("id", "name"); // missing email
        List<String> parquetTypes = Arrays.asList("VARCHAR", "VARCHAR");

        SchemaValidationResult result = service.validate(schemaFile, parquetColumns, parquetTypes);

        assertFalse(result.isFullyValid());
        assertEquals(2, result.getValidColumns().size());
        assertEquals(1, result.getMissingInParquet().size());
        assertTrue(result.getMissingInParquet().contains("email"));
    }

    @Test
    @DisplayName("Should detect extra columns in Parquet")
    void shouldDetectExtraColumns() throws IOException {
        String schemaContent = """
            {
              "name": "test_schema",
              "type": "record",
              "fields": [
                {"name": "id", "type": "string"}
              ]
            }
            """;

        File schemaFile = tempDir.resolve("test.schema").toFile();
        Files.writeString(schemaFile.toPath(), schemaContent);

        List<String> parquetColumns = Arrays.asList("id", "extra_column");
        List<String> parquetTypes = Arrays.asList("VARCHAR", "VARCHAR");

        SchemaValidationResult result = service.validate(schemaFile, parquetColumns, parquetTypes);

        // Extra columns don't make it invalid, just a warning
        assertEquals(1, result.getValidColumns().size());
        assertEquals(1, result.getExtraInParquet().size());
        assertTrue(result.getExtraInParquet().contains("extra_column"));
    }

    @Test
    @DisplayName("Should handle nullable types correctly")
    void shouldHandleNullableTypes() throws IOException {
        String schemaContent = """
            {
              "name": "test_schema",
              "type": "record",
              "fields": [
                {"name": "nullable_field", "type": ["string", "null"]}
              ]
            }
            """;

        File schemaFile = tempDir.resolve("test.schema").toFile();
        Files.writeString(schemaFile.toPath(), schemaContent);

        List<String> parquetColumns = Arrays.asList("nullable_field");
        List<String> parquetTypes = Arrays.asList("VARCHAR");

        SchemaValidationResult result = service.validate(schemaFile, parquetColumns, parquetTypes);

        assertTrue(result.isFullyValid());
        assertEquals(1, result.getValidColumns().size());
    }

    @Test
    @DisplayName("Should normalize type names correctly")
    void shouldNormalizeTypeNames() throws IOException {
        String schemaContent = """
            {
              "name": "test_schema",
              "type": "record",
              "fields": [
                {"name": "col1", "type": "string"},
                {"name": "col2", "type": "int32"},
                {"name": "col3", "type": "timestamp"}
              ]
            }
            """;

        File schemaFile = tempDir.resolve("test.schema").toFile();
        Files.writeString(schemaFile.toPath(), schemaContent);

        // Parquet uses different names for same types
        List<String> parquetColumns = Arrays.asList("col1", "col2", "col3");
        List<String> parquetTypes = Arrays.asList("VARCHAR", "INTEGER", "TIMESTAMP");

        SchemaValidationResult result = service.validate(schemaFile, parquetColumns, parquetTypes);

        assertTrue(result.isFullyValid());
        assertEquals(3, result.getValidColumns().size());
    }
}

