package com.github.jhordyhuaman.parquetstudio.service;

import java.util.Locale;

/**
 * Decides when a file path cannot be handed to DuckDB directly.
 *
 * DuckDB's read_parquet/COPY treat the path as a glob pattern and, on Windows,
 * fail on paths near MAX_PATH (260) even with long-path support enabled
 * (duckdb/duckdb#20384, #4699). Affected files are copied to a short temp
 * path with Java NIO, which has neither limitation.
 */
public final class SafeParquetPath {
  // Margin below MAX_PATH: DuckDB internals may append to the path.
  static final int WINDOWS_SAFE_PATH_LENGTH = 240;
  private static final String GLOB_CHARS = "*?[]{}";

  private SafeParquetPath() {}

  public static boolean isRunningOnWindows() {
    return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
  }

  public static boolean needsSafeCopy(String absolutePath, boolean isWindows) {
    if (isWindows && absolutePath.length() > WINDOWS_SAFE_PATH_LENGTH) {
      return true;
    }
    for (int i = 0; i < absolutePath.length(); i++) {
      if (GLOB_CHARS.indexOf(absolutePath.charAt(i)) >= 0) {
        return true;
      }
    }
    return false;
  }

  @FunctionalInterface
  public interface IoConsumer<T> {
    void accept(T t) throws Exception;
  }

  public static java.io.File toReadable(java.io.File source) throws java.io.IOException {
    if (!needsSafeCopy(source.getAbsolutePath(), isRunningOnWindows())) {
      return source;
    }
    java.nio.file.Path temp =
        java.nio.file.Files.createTempFile("parquetstudio-", ".parquet");
    java.nio.file.Files.copy(source.toPath(), temp,
        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    temp.toFile().deleteOnExit();
    return temp.toFile();
  }

  public static boolean isTempCopy(java.io.File original, java.io.File readable) {
    return !original.equals(readable);
  }

  /**
   * Always writes to a temp file first, then atomically moves it onto {@code target}.
   * This ensures a mid-write failure never corrupts or destroys the original file.
   */
  public static void writeThenMove(java.io.File target, IoConsumer<java.io.File> writer)
      throws Exception {
    java.nio.file.Path temp =
        java.nio.file.Files.createTempFile("parquetstudio-save-", ".parquet");
    try {
      // DuckDB COPY refuses to overwrite; hand it a non-existing path.
      java.nio.file.Files.deleteIfExists(temp);
      writer.accept(temp.toFile());
      java.nio.file.Files.move(temp, target.toPath(),
          java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    } finally {
      java.nio.file.Files.deleteIfExists(temp);
    }
  }
}
