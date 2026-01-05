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

import com.github.jhordyhuaman.parquetstudio.model.SchemaValidationResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;

/**
 * Service for validating Parquet data types against a schema file.
 */
public class SchemaValidationService {
    private static final Logger LOGGER = Logger.getInstance(SchemaValidationService.class);

    // Type normalization mapping: maps various type representations to a normalized form
    private static final Map<String, String> TYPE_NORMALIZATION = new HashMap<>();

    static {
        // String types - all variations map to STRING
        TYPE_NORMALIZATION.put("string", "STRING");
        TYPE_NORMALIZATION.put("varchar", "STRING");
        TYPE_NORMALIZATION.put("utf8", "STRING");
        TYPE_NORMALIZATION.put("text", "STRING");
        TYPE_NORMALIZATION.put("char", "STRING");
        TYPE_NORMALIZATION.put("alphanumeric", "STRING");

        // Integer types
        TYPE_NORMALIZATION.put("int", "INTEGER");
        TYPE_NORMALIZATION.put("int32", "INTEGER");
        TYPE_NORMALIZATION.put("integer", "INTEGER");
        TYPE_NORMALIZATION.put("smallint", "INTEGER");
        TYPE_NORMALIZATION.put("tinyint", "INTEGER");

        // Big Integer types
        TYPE_NORMALIZATION.put("bigint", "BIGINT");
        TYPE_NORMALIZATION.put("int64", "BIGINT");
        TYPE_NORMALIZATION.put("long", "BIGINT");

        // Decimal types
        TYPE_NORMALIZATION.put("decimal", "DECIMAL");
        TYPE_NORMALIZATION.put("numeric", "DECIMAL");
        TYPE_NORMALIZATION.put("number", "DECIMAL");

        // Float types
        TYPE_NORMALIZATION.put("float", "FLOAT");
        TYPE_NORMALIZATION.put("float32", "FLOAT");
        TYPE_NORMALIZATION.put("real", "FLOAT");
        TYPE_NORMALIZATION.put("double", "DOUBLE");
        TYPE_NORMALIZATION.put("float64", "DOUBLE");

        // Date/Time types
        TYPE_NORMALIZATION.put("date", "DATE");
        TYPE_NORMALIZATION.put("timestamp", "TIMESTAMP");
        TYPE_NORMALIZATION.put("timestamp_millis", "TIMESTAMP");
        TYPE_NORMALIZATION.put("timestamp_micros", "TIMESTAMP");
        TYPE_NORMALIZATION.put("datetime", "TIMESTAMP");
        TYPE_NORMALIZATION.put("time", "TIME");

        // Boolean
        TYPE_NORMALIZATION.put("boolean", "BOOLEAN");
        TYPE_NORMALIZATION.put("bool", "BOOLEAN");

        // Binary
        TYPE_NORMALIZATION.put("binary", "BINARY");
        TYPE_NORMALIZATION.put("bytes", "BINARY");
        TYPE_NORMALIZATION.put("blob", "BINARY");
    }

    /**
     * Validates the Parquet columns and types against a schema file.
     *
     * @param schemaFile The schema file (JSON format)
     * @param parquetColumns Column names from the Parquet file
     * @param parquetTypes Column types from the Parquet file
     * @return ValidationResult with details of the comparison
     * @throws IOException if the schema file cannot be read
     */
    public SchemaValidationResult validate(File schemaFile, List<String> parquetColumns, List<String> parquetTypes)
            throws IOException {

        LOGGER.info("Starting schema validation with file: " + schemaFile.getName());

        // Read and parse schema file
        String schemaContent = Files.readString(schemaFile.toPath());
        JsonObject schema = JsonParser.parseString(schemaContent).getAsJsonObject();

        // Extract fields from schema
        JsonArray fields = schema.getAsJsonArray("fields");
        if (fields == null) {
            throw new IOException("Schema file does not contain 'fields' array");
        }

        // Build schema map: columnName -> expectedType
        Map<String, String> schemaMap = new LinkedHashMap<>();
        for (JsonElement element : fields) {
            JsonObject field = element.getAsJsonObject();
            String name = field.get("name").getAsString();
            String type = extractType(field.get("type"));
            schemaMap.put(name, type);
        }

        // Build parquet map: columnName -> actualType
        Map<String, String> parquetMap = new LinkedHashMap<>();
        for (int i = 0; i < parquetColumns.size(); i++) {
            String colName = parquetColumns.get(i);
            // Remove type suffix from column name if present (e.g., "column_name (VARCHAR)" -> "column_name")
            if (colName.contains(" (")) {
                colName = colName.substring(0, colName.indexOf(" ("));
            }
            String colType = i < parquetTypes.size() ? parquetTypes.get(i) : "UNKNOWN";
            parquetMap.put(colName, colType);
        }

        // Perform validation
        SchemaValidationResult result = new SchemaValidationResult();

        // Check each schema column against parquet
        for (Map.Entry<String, String> entry : schemaMap.entrySet()) {
            String columnName = entry.getKey();
            String expectedType = entry.getValue();

            if (parquetMap.containsKey(columnName)) {
                String actualType = parquetMap.get(columnName);
                if (typesMatch(expectedType, actualType)) {
                    result.addValidColumn(columnName, expectedType, actualType);
                    LOGGER.info("Column '" + columnName + "' is valid: " + expectedType + " matches " + actualType);
                } else {
                    result.addTypeMismatch(columnName, expectedType, actualType);
                    LOGGER.warn("Column '" + columnName + "' type mismatch: expected " + expectedType + ", got " + actualType);
                }
            } else {
                result.addMissingInParquet(columnName);
                LOGGER.warn("Column '" + columnName + "' is missing in Parquet file");
            }
        }

        // Check for extra columns in parquet (not in schema)
        for (String parquetCol : parquetMap.keySet()) {
            if (!schemaMap.containsKey(parquetCol)) {
                result.addExtraInParquet(parquetCol);
                LOGGER.info("Column '" + parquetCol + "' is extra (not in schema)");
            }
        }

        LOGGER.info("Validation complete: " + result.getValidColumns().size() + " valid, " +
                result.getTypeMismatchColumns().size() + " mismatches, " +
                result.getMissingInParquet().size() + " missing, " +
                result.getExtraInParquet().size() + " extra");

        return result;
    }

    /**
     * Extracts the type from a schema field type definition.
     * Handles both simple types ("string") and nullable types (["string", "null"]).
     */
    private String extractType(JsonElement typeElement) {
        if (typeElement.isJsonPrimitive()) {
            return typeElement.getAsString();
        } else if (typeElement.isJsonArray()) {
            JsonArray typeArray = typeElement.getAsJsonArray();
            // Find the non-null type
            for (JsonElement elem : typeArray) {
                if (elem.isJsonPrimitive()) {
                    String type = elem.getAsString();
                    if (!"null".equalsIgnoreCase(type)) {
                        return type;
                    }
                }
            }
            // If only null found, return the first element
            if (typeArray.size() > 0) {
                return typeArray.get(0).getAsString();
            }
        }
        return "unknown";
    }

    /**
     * Compares two types considering normalization.
     * E.g., "string" matches "VARCHAR", "int32" matches "INTEGER", etc.
     */
    private boolean typesMatch(String schemaType, String parquetType) {
        String normalizedSchema = normalizeType(schemaType);
        String normalizedParquet = normalizeType(parquetType);

        LOGGER.info("Comparing types: schema='" + schemaType + "' (normalized=" + normalizedSchema +
                   ") vs parquet='" + parquetType + "' (normalized=" + normalizedParquet + ")");

        // Direct match
        if (normalizedSchema.equals(normalizedParquet)) {
            LOGGER.info("  -> MATCH (direct)");
            return true;
        }

        // Special case: DECIMAL with precision/scale
        if (normalizedSchema.equals("DECIMAL") && normalizedParquet.startsWith("DECIMAL")) {
            LOGGER.info("  -> MATCH (decimal compatible)");
            return true;
        }
        if (normalizedParquet.equals("DECIMAL") && normalizedSchema.startsWith("DECIMAL")) {
            LOGGER.info("  -> MATCH (decimal compatible)");
            return true;
        }

        // Special case: both are decimal with different precision
        if (normalizedSchema.startsWith("DECIMAL") && normalizedParquet.startsWith("DECIMAL")) {
            LOGGER.info("  -> MATCH (both decimal)");
            return true; // Consider all decimals compatible
        }

        LOGGER.info("  -> NO MATCH");
        return false;
    }

    /**
     * Normalizes a type string to a standard representation.
     */
    private String normalizeType(String type) {
        if (type == null) return "UNKNOWN";

        String lowerType = type.toLowerCase().trim();

        // Handle decimal with precision: "decimal(23,10)" -> "DECIMAL"
        if (lowerType.startsWith("decimal")) {
            return "DECIMAL";
        }

        // Check normalization map
        String normalized = TYPE_NORMALIZATION.get(lowerType);
        if (normalized != null) {
            return normalized;
        }

        // Return uppercase version of original
        return type.toUpperCase();
    }
}

