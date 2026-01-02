package com.github.jhordyhuaman.parquetstudio;

public class Constants {
    public final static String SCHEMA_PANEL = "SCHEMA_PANEL";
    public final static String DATA_PANEL = "DATA_PANEL";
    public final static String LOADING_PANEL = "LOADING_PANEL";

    // File size thresholds (in bytes)
    public final static long FILE_SIZE_WARNING_THRESHOLD = 100 * 1024 * 1024; // 100 MB
    public final static long FILE_SIZE_LARGE_THRESHOLD = 500 * 1024 * 1024; // 500 MB
    public final static long FILE_SIZE_MAX_THRESHOLD = 1024 * 1024 * 1024L; // 1 GB

    // Retry configuration
    public final static int MAX_OPEN_RETRIES = 3;
    public final static int RETRY_DELAY_MS = 500;

    public static class Message {
        public final static String SCHEMA_AND_PARQUET_NOT_SAME_COLUMNS = "The schema no haven't the same number of fields that the parquet.";
        public final static String SCHEMA_AND_PARQUET_NOT_SAME_COLUMNS_2 = "<html><span style='color:yellow;'>⚠</span> " + SCHEMA_AND_PARQUET_NOT_SAME_COLUMNS + "</html>";

        // Loading messages
        public final static String LOADING_FILE = "Loading file...";
        public final static String LOADING_INITIALIZING = "Initializing Parquet Studio...";
        public final static String LOADING_READING_SCHEMA = "Reading schema...";
        public final static String LOADING_READING_DATA = "Reading data...";
        public final static String LOADING_PREPARING_TABLE = "Preparing table...";

        // File size messages
        public final static String FILE_TOO_LARGE = "File is too large (>1GB). Consider using DuckDB CLI or splitting the file.";
        public final static String FILE_LARGE_WARNING = "Large file detected (%s). Loading may take a while...";
        public final static String FILE_SIZE_WARNING = "File size: %s. This may consume significant memory.";

        // Error messages
        public final static String ERROR_OPENING_TOOL_WINDOW = "Could not open Parquet Studio. Please try opening it manually from View → Tool Windows → Parquet Studio";
        public final static String ERROR_FILE_NOT_FOUND = "File not found: %s";
        public final static String ERROR_FILE_NOT_READABLE = "Cannot read file: %s";
        public final static String ERROR_LOADING_FILE = "Error loading file: %s";
    }

}
