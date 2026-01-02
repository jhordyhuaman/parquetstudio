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
package com.github.jhordyhuaman.parquetstudio.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents the result of validating a Parquet file against a schema.
 */
public class SchemaValidationResult {

    private final List<ColumnValidation> validColumns = new ArrayList<>();
    private final List<ColumnValidation> typeMismatchColumns = new ArrayList<>();
    private final List<String> missingInParquet = new ArrayList<>();
    private final List<String> extraInParquet = new ArrayList<>();

    /**
     * Represents a single column validation result.
     */
    public static class ColumnValidation {
        private final String columnName;
        private final String expectedType;
        private final String actualType;
        private final boolean isValid;

        public ColumnValidation(String columnName, String expectedType, String actualType, boolean isValid) {
            this.columnName = columnName;
            this.expectedType = expectedType;
            this.actualType = actualType;
            this.isValid = isValid;
        }

        public String getColumnName() { return columnName; }
        public String getExpectedType() { return expectedType; }
        public String getActualType() { return actualType; }
        public boolean isValid() { return isValid; }
    }

    public void addValidColumn(String name, String expectedType, String actualType) {
        validColumns.add(new ColumnValidation(name, expectedType, actualType, true));
    }

    public void addTypeMismatch(String name, String expectedType, String actualType) {
        typeMismatchColumns.add(new ColumnValidation(name, expectedType, actualType, false));
    }

    public void addMissingInParquet(String name) {
        missingInParquet.add(name);
    }

    public void addExtraInParquet(String name) {
        extraInParquet.add(name);
    }

    public List<ColumnValidation> getValidColumns() { return validColumns; }
    public List<ColumnValidation> getTypeMismatchColumns() { return typeMismatchColumns; }
    public List<String> getMissingInParquet() { return missingInParquet; }
    public List<String> getExtraInParquet() { return extraInParquet; }

    public boolean isFullyValid() {
        return typeMismatchColumns.isEmpty() && missingInParquet.isEmpty() && extraInParquet.isEmpty();
    }

    public int getTotalValidated() {
        return validColumns.size() + typeMismatchColumns.size();
    }

    public int getErrorCount() {
        return typeMismatchColumns.size() + missingInParquet.size();
    }

    public int getWarningCount() {
        return extraInParquet.size();
    }
}

