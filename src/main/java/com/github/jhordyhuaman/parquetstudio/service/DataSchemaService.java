package com.github.jhordyhuaman.parquetstudio.service;

import com.github.jhordyhuaman.parquetstudio.Constants;
import com.github.jhordyhuaman.parquetstudio.SchemaItemTransformSerializer;
import com.github.jhordyhuaman.parquetstudio.model.ParquetData;
import com.github.jhordyhuaman.parquetstudio.model.SchemaItem;
import com.github.jhordyhuaman.parquetstudio.model.SchemaItemTransform;
import com.github.jhordyhuaman.parquetstudio.model.SchemaStructure;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.intellij.openapi.diagnostic.Logger;
import java.io.File;
import java.util.List;


public class DataSchemaService {
    private final Logger LOGGER = Logger.getInstance(DataSchemaService.class);
    private SchemaStructure schemaStructureOriginal;
    private SchemaStructure schemaStructureTransform;
    public File schemaFile;

    public String convertToJsonString(Object schema) {
        Gson gson = new GsonBuilder()
                .registerTypeAdapter(SchemaItemTransform.class, new SchemaItemTransformSerializer())
                .setPrettyPrinting()
                .create();
        return gson.toJson(schema);
    }

    public void applyConvertTypes(ParquetData data, SchemaStructure schemaStructure) {
        List<String> columnsName = data.getColumnNames();
        LOGGER.info("=== APPLY CONVERT TYPES START ===");
        LOGGER.info("Columns to process: " + columnsName.size());
        LOGGER.info("Schema structure fields: " + (schemaStructure.fields != null ? schemaStructure.fields.size() : 0));

        for(int index = 0; index < columnsName.size(); index++){
            String columName = columnsName.get(index);
            SchemaItem schemaItemRaw = schemaStructure.getItem(columName);

            String currentType = data.getColumnTypes().get(index);
            String targetType = null;

            // Check if it's a SchemaItemTransform (has typeTransform) or regular SchemaItem
            if (schemaItemRaw instanceof SchemaItemTransform) {
                SchemaItemTransform schemaItem = (SchemaItemTransform) schemaItemRaw;
                targetType = schemaItem.typeTransform != null ? String.valueOf(schemaItem.typeTransform) : null;
                LOGGER.info("Column '%s' (index %d): %s -> %s (from SchemaItemTransform.typeTransform)".formatted(
                        columName, index, currentType, targetType));
            } else if (schemaItemRaw != null) {
                // For regular SchemaItem, use the type field directly
                targetType = schemaItemRaw.type != null ? String.valueOf(schemaItemRaw.type) : null;
                LOGGER.info("Column '%s' (index %d): %s -> %s (from SchemaItem.type)".formatted(
                        columName, index, currentType, targetType));
            } else {
                LOGGER.warn("Column '%s' (index %d): NOT FOUND in schema - keeping type %s".formatted(
                        columName, index, currentType));
                continue;
            }

            if (targetType == null || targetType.equals("null") || targetType.isEmpty()) {
                LOGGER.warn("Column '%s': target type is null/empty - skipping".formatted(columName));
                continue;
            }

            LOGGER.info("Setting column '%s' type from '%s' to '%s'".formatted(columName, currentType, targetType));
            data.getColumnTypes().set(index, targetType);
        }

        LOGGER.info("=== APPLY CONVERT TYPES END ===");
        LOGGER.info("Final column types: " + data.getColumnTypes());
    }

    public SchemaStructure getSchemaStructureOriginal(){ return schemaStructureOriginal;}
    public SchemaStructure getSchemaStructureTransform(){ return schemaStructureTransform;}
    public void setNullSchemaTransform(){ schemaStructureTransform = null; }

    public String generateTransformSchemaString() throws Exception {
        if(schemaFile == null){
            throw new Exception("First load a schema file");
        }

        if(schemaStructureOriginal == null){
            throw new Exception("First load a file parquet");
        }
        SchemaStructure schemaStructure = SchemaStructure.schemaFromFile(schemaFile.getAbsolutePath());
        schemaStructure.changesTypesFields();

        schemaStructureTransform = schemaStructureOriginal.toTransform(schemaStructure);
        String schemaString = convertToJsonString(schemaStructureTransform);
        LOGGER.warn("Write other schema in " + Constants.SCHEMA_PANEL);
        return schemaString;
    }

    public String generateOriginalSchemaString(List<String> columnNames, List<String> columnTypes) throws Exception{
        SchemaStructure schemaStructure = SchemaStructure.schemaFromLists(columnNames, columnTypes);
        String schemString = convertToJsonString(schemaStructure);
        schemaStructureOriginal = schemaStructure;

        LOGGER.info("Write schema of parquet in " + Constants.SCHEMA_PANEL);
        return schemString;
    }

    public boolean isSameNumberOfColumns(){
        return schemaStructureOriginal.fields.size() != schemaStructureTransform.fields.size();
    }

    /**
     * Loads a SchemaStructure from a schema file.
     *
     * @param file the schema file (JSON format)
     * @return the loaded SchemaStructure
     * @throws Exception if loading fails
     */
    public SchemaStructure loadSchemaFromFile(File file) throws Exception {
        LOGGER.info("=== LOAD SCHEMA FROM FILE START ===");
        LOGGER.info("Loading schema from file: " + file.getAbsolutePath());

        SchemaStructure schemaStructure = SchemaStructure.schemaFromFile(file.getAbsolutePath());
        LOGGER.info("Schema loaded. Fields count: " + (schemaStructure.fields != null ? schemaStructure.fields.size() : 0));

        // Log all fields and their types BEFORE changesTypesFields
        if (schemaStructure.fields != null) {
            LOGGER.info("Schema fields BEFORE type normalization:");
            for (SchemaItem field : schemaStructure.fields) {
                LOGGER.info("  - " + field.name + ": " + field.type);
            }
        }

        schemaStructure.changesTypesFields();
        LOGGER.info("Types changed in schema structure");

        // Log all fields and their types AFTER changesTypesFields
        if (schemaStructure.fields != null) {
            LOGGER.info("Schema fields AFTER type normalization:");
            for (SchemaItem field : schemaStructure.fields) {
                LOGGER.info("  - " + field.name + ": " + field.type);
            }
        }

        // If we have original schema, create transform version
        if (schemaStructureOriginal != null) {
            LOGGER.info("schemaStructureOriginal is set with " + schemaStructureOriginal.fields.size() + " fields");
            LOGGER.info("Creating transform version...");
            schemaStructureTransform = schemaStructureOriginal.toTransform(schemaStructure);
            LOGGER.info("Transform created. Fields: " + (schemaStructureTransform.fields != null ? schemaStructureTransform.fields.size() : 0));

            // Log transform fields
            if (schemaStructureTransform.fields != null) {
                LOGGER.info("Transform fields (original -> target):");
                for (SchemaItem field : schemaStructureTransform.fields) {
                    if (field instanceof SchemaItemTransform) {
                        SchemaItemTransform tf = (SchemaItemTransform) field;
                        LOGGER.info("  - " + tf.name + ": " + tf.type + " -> " + tf.typeTransform);
                    }
                }
            }

            LOGGER.info("=== LOAD SCHEMA FROM FILE END (returning transform) ===");
            return schemaStructureTransform;
        }

        LOGGER.warn("schemaStructureOriginal is NULL - returning raw schema structure");
        LOGGER.info("=== LOAD SCHEMA FROM FILE END (returning raw) ===");
        // Return the schema as-is, it will be used directly
        return schemaStructure;
    }

    /**
     * Initializes the original schema structure from the current Parquet data.
     * This must be called before loadSchemaFromFile for proper type transformation.
     */
    public void initializeOriginalSchema(List<String> columnNames, List<String> columnTypes) {
        try {
            schemaStructureOriginal = SchemaStructure.schemaFromLists(columnNames, columnTypes);
            LOGGER.info("Initialized schemaStructureOriginal with " + columnNames.size() + " columns");
        } catch (Exception e) {
            LOGGER.error("Error initializing original schema", e);
        }
    }

}
