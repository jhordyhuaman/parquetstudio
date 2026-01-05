package com.github.jhordyhuaman.parquetstudio.model;

import java.util.List;

public class SchemaItem {
    public String name;
    public Object type;

    public SchemaItem(String name, Object type) {
        this.name = name;
        this.type = type;

        applyStandartType();
    }
    @Override
    public String toString() {
        return "%s (%s)".formatted(name, type.toString());
    }
    public String equivalentType(String type){
        if(type == null) return "string";

        String lowerType = type.toLowerCase().trim();

        // Handle decimal types - preserve precision
        if (lowerType.startsWith("decimal")) {
            return type; // Keep as decimal(23,10) etc.
        }

        return switch (lowerType) {
            case "timestamp_millis", "timestamp_micros" -> "timestamp";
            case "int32", "int", "integer" -> "integer";
            case "int64", "long" -> "bigint";
            case "float32", "float" -> "float";
            case "float64", "double" -> "double";
            case "bool", "boolean" -> "boolean";
            case "utf8", "text" -> "string";
            case "bytes", "binary" -> "binary";
            default -> type;
        };
    }
    public void applyStandartType(){
        boolean isList = type instanceof List;
        if(!isList) {
            type = equivalentType(type.toString());
            return;
        }

        List<String> lisType = (List<String>) type;
        String typeFounded = lisType.stream().filter( x -> !x.equals("null")).findFirst().orElse(null);
        type = equivalentType(typeFounded);
    }
}
