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
}
