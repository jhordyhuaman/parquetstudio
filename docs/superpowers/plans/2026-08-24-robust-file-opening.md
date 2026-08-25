# Robust File Opening & DuckDB Path Fixes — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate the two verified bug families: (1) DuckDB "No files found that match the pattern" on Windows long paths / glob characters, and (2) intermittent open/close failures caused by the fragile tool-window opening architecture; plus targeted data-integrity cleanups in `saveParquet`.

**Architecture:** A new `SafeParquetPath` utility copies problematic files to a short temp path via Java NIO (which handles long paths) before DuckDB touches them, and routes saves through a temp file + atomic move. The tool-window lookup is replaced by a project-level Service that the factory registers, removing the Swing-tree scan, retries, and static state. Duplicate-open prevention moves entirely into `ParquetToolWindow` (idempotent "switch to existing tab").

**Tech Stack:** Java 17, IntelliJ Platform SDK (2023.3, Gradle IntelliJ Plugin 1.17.4), DuckDB JDBC 0.10.2, JUnit 5 + AssertJ.

**Spec:** The investigation report in the conversation of 2026-08-24 (root causes: duckdb/duckdb#20384 long paths ≥ ~260 chars on Windows — NOT fixed upstream even in 1.4.3; duckdb/duckdb#4699 glob chars `* ? [ ] { }` in paths; `fileOpened` never reset; static `openingFiles`; recursive Swing scan version-dependent).

## Global Constraints

- Java 17 (`javaVersion=17` in gradle.properties); no new dependencies.
- Do NOT upgrade `duckdb_jdbc` — the upstream bug is unfixed; the workaround is ours.
- All tests: `./gradlew test`. Single class: `./gradlew test --tests "com.github.jhordyhuaman.parquetstudio.<Class>"`.
- Existing tests must keep passing after every task.
- Code style: match existing (2-space indent, `LOGGER` per class via `Logger.getInstance`). New user-facing strings go in `Constants.Message` (English, like existing ones).
- Commit after each task with the given message; work happens on a feature branch `feature/robust-file-opening` created from `main` at the start.

---

### Task 0: Branch setup

**Files:** none

- [ ] **Step 1: Create branch**

```bash
cd /Volumes/SSD-EXTERNO/Projects/parquetstudio
git checkout main && git pull && git checkout -b feature/robust-file-opening
```

- [ ] **Step 2: Verify baseline is green**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL. If it is not, STOP and report — do not fix unrelated failures silently.

---

### Task 1: `SafeParquetPath` utility (detection logic)

The pure-logic core: decide when a path is unsafe for DuckDB. Windows-ness is injected so it is testable on macOS/Linux.

**Files:**
- Create: `src/main/java/com/github/jhordyhuaman/parquetstudio/service/SafeParquetPath.java`
- Test (create): `src/test/java/com/github/jhordyhuaman/parquetstudio/SafeParquetPathTest.java`

**Interfaces:**
- Produces: `static boolean needsSafeCopy(String absolutePath, boolean isWindows)`; `static boolean isRunningOnWindows()`. (Task 2 adds the copy methods to this same class.)

- [ ] **Step 1: Write the failing tests**

```java
package com.github.jhordyhuaman.parquetstudio;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.jhordyhuaman.parquetstudio.service.SafeParquetPath;
import org.junit.jupiter.api.Test;

class SafeParquetPathTest {

  @Test
  void shortCleanPathIsSafe() {
    assertThat(SafeParquetPath.needsSafeCopy("C:\\data\\file.parquet", true)).isFalse();
    assertThat(SafeParquetPath.needsSafeCopy("/tmp/data/file.parquet", false)).isFalse();
  }

  @Test
  void longPathNeedsCopyOnWindowsOnly() {
    String longPath = "D:\\" + "a".repeat(250) + "\\file.parquet"; // > 240 chars
    assertThat(SafeParquetPath.needsSafeCopy(longPath, true)).isTrue();
    assertThat(SafeParquetPath.needsSafeCopy(longPath, false)).isFalse();
  }

  @Test
  void globCharactersNeedCopyOnAnyOs() {
    assertThat(SafeParquetPath.needsSafeCopy("/data/file [1].parquet", false)).isTrue();
    assertThat(SafeParquetPath.needsSafeCopy("/data/copy*.parquet", false)).isTrue();
    assertThat(SafeParquetPath.needsSafeCopy("/data/wh?t.parquet", false)).isTrue();
    assertThat(SafeParquetPath.needsSafeCopy("/data/{a}.parquet", false)).isTrue();
    assertThat(SafeParquetPath.needsSafeCopy("C:\\data\\file[x].parquet", true)).isTrue();
  }

  @Test
  void hivePartitionEqualsSignIsSafe() {
    assertThat(SafeParquetPath.needsSafeCopy("/data/gf_cutoff_date=2024-02-14/part.parquet", false))
        .isFalse();
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.github.jhordyhuaman.parquetstudio.SafeParquetPathTest"`
Expected: COMPILE FAILURE ("cannot find symbol SafeParquetPath").

- [ ] **Step 3: Write minimal implementation**

```java
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.github.jhordyhuaman.parquetstudio.SafeParquetPathTest"`
Expected: 4 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/github/jhordyhuaman/parquetstudio/service/SafeParquetPath.java src/test/java/com/github/jhordyhuaman/parquetstudio/SafeParquetPathTest.java
git commit -m "feat: add SafeParquetPath detection for DuckDB-unsafe paths (long Windows paths, glob chars)"
```

---

### Task 2: `SafeParquetPath` temp-copy for reads and temp-write for saves

**Files:**
- Modify: `src/main/java/com/github/jhordyhuaman/parquetstudio/service/SafeParquetPath.java`
- Test (modify): `src/test/java/com/github/jhordyhuaman/parquetstudio/SafeParquetPathTest.java`

**Interfaces:**
- Produces:
  - `static File toReadable(File source) throws IOException` — returns `source` itself when safe; otherwise copies to a temp file `parquetstudio-<rand>.parquet` and returns that copy.
  - `static boolean isTempCopy(File original, File readable)` — true when `toReadable` made a copy (callers delete it in a finally block).
  - `static void writeThenMove(File target, IoConsumer<File> writer) throws Exception` — when `target` is unsafe, hands the writer a temp file and then moves it onto `target` with NIO (`REPLACE_EXISTING`); when safe, hands the writer `target` directly.
  - `@FunctionalInterface interface IoConsumer<T> { void accept(T t) throws Exception; }` (nested in `SafeParquetPath`).

- [ ] **Step 1: Write the failing tests** (append to `SafeParquetPathTest`)

```java
  @org.junit.jupiter.api.io.TempDir
  java.nio.file.Path tempDir;

  @Test
  void toReadableReturnsSameFileWhenSafe() throws Exception {
    java.io.File f = tempDir.resolve("plain.parquet").toFile();
    java.nio.file.Files.writeString(f.toPath(), "x");
    java.io.File readable = SafeParquetPath.toReadable(f);
    assertThat(readable).isEqualTo(f);
    assertThat(SafeParquetPath.isTempCopy(f, readable)).isFalse();
  }

  @Test
  void toReadableCopiesGlobNamedFile() throws Exception {
    java.io.File f = tempDir.resolve("data [1].parquet").toFile();
    java.nio.file.Files.writeString(f.toPath(), "content");
    java.io.File readable = SafeParquetPath.toReadable(f);
    try {
      assertThat(readable).isNotEqualTo(f);
      assertThat(readable.getName()).endsWith(".parquet");
      assertThat(SafeParquetPath.needsSafeCopy(readable.getAbsolutePath(),
          SafeParquetPath.isRunningOnWindows())).isFalse();
      assertThat(java.nio.file.Files.readString(readable.toPath())).isEqualTo("content");
      assertThat(SafeParquetPath.isTempCopy(f, readable)).isTrue();
    } finally {
      readable.delete();
    }
  }

  @Test
  void writeThenMovePlacesContentAtGlobNamedTarget() throws Exception {
    java.io.File target = tempDir.resolve("out [x].parquet").toFile();
    SafeParquetPath.writeThenMove(target,
        f -> java.nio.file.Files.writeString(f.toPath(), "written"));
    assertThat(java.nio.file.Files.readString(target.toPath())).isEqualTo("written");
  }

  @Test
  void writeThenMoveWritesDirectlyWhenSafe() throws Exception {
    java.io.File target = tempDir.resolve("out.parquet").toFile();
    SafeParquetPath.writeThenMove(target,
        f -> {
          assertThat(f).isEqualTo(target);
          java.nio.file.Files.writeString(f.toPath(), "direct");
        });
    assertThat(java.nio.file.Files.readString(target.toPath())).isEqualTo("direct");
  }
```

- [ ] **Step 2: Run tests to verify the new ones fail**

Run: `./gradlew test --tests "com.github.jhordyhuaman.parquetstudio.SafeParquetPathTest"`
Expected: COMPILE FAILURE (missing `toReadable`, `isTempCopy`, `writeThenMove`).

- [ ] **Step 3: Implement** (add to `SafeParquetPath`)

```java
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

  public static void writeThenMove(java.io.File target, IoConsumer<java.io.File> writer)
      throws Exception {
    if (!needsSafeCopy(target.getAbsolutePath(), isRunningOnWindows())) {
      writer.accept(target);
      return;
    }
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
```

Note: `toReadable`'s temp file is created (exists, empty) then overwritten by the copy — that is fine for reads. For writes we delete the temp first because DuckDB's `COPY TO` fails on an existing file in some versions.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "com.github.jhordyhuaman.parquetstudio.SafeParquetPathTest"`
Expected: 8 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add -A src/main/java/com/github/jhordyhuaman/parquetstudio/service/SafeParquetPath.java src/test/java/com/github/jhordyhuaman/parquetstudio/SafeParquetPathTest.java
git commit -m "feat: SafeParquetPath temp-copy read and temp-write+move save"
```

---

### Task 3: Route `DuckDBParquetService` through `SafeParquetPath`

**Files:**
- Modify: `src/main/java/com/github/jhordyhuaman/parquetstudio/service/DuckDBParquetService.java` (`loadParquet` ~lines 52–119, `saveParquet` ~lines 124–287)
- Test (modify): `src/test/java/com/github/jhordyhuaman/parquetstudio/DuckDBParquetServiceTest.java`

**Interfaces:**
- Consumes: `SafeParquetPath.toReadable / isTempCopy / writeThenMove` from Task 2.
- Produces: unchanged public API (`loadParquet(File)`, `saveParquet(File, ParquetData)`).

- [ ] **Step 1: Write the failing end-to-end tests** (append to `DuckDBParquetServiceTest`; follow the existing test style in that file — read it first)

```java
  @org.junit.jupiter.api.io.TempDir
  java.nio.file.Path safePathTempDir;

  @Test
  void loadsParquetFileWhoseNameContainsGlobCharacters() throws Exception {
    java.io.File source = new java.io.File("src/test/resources/parquet/logical_date.parquet");
    java.io.File globNamed = safePathTempDir.resolve("copia [1].parquet").toFile();
    java.nio.file.Files.copy(source.toPath(), globNamed.toPath());

    ParquetData data = new DuckDBParquetService().loadParquet(globNamed);

    assertThat(data.getRows()).isNotEmpty();
    assertThat(data.getColumnNames()).isNotEmpty();
  }

  @Test
  void savesParquetFileWhoseNameContainsGlobCharacters() throws Exception {
    java.io.File source = new java.io.File("src/test/resources/parquet/logical_date.parquet");
    DuckDBParquetService service = new DuckDBParquetService();
    ParquetData data = service.loadParquet(source);

    java.io.File target = safePathTempDir.resolve("salida [x].parquet").toFile();
    service.saveParquet(target, data);

    assertThat(target).exists();
    ParquetData reloaded = service.loadParquet(target);
    assertThat(reloaded.getRows()).hasSameSizeAs(data.getRows());
  }
```

(If `logical_date.parquet` is unreadable by the existing service — check how current tests load fixtures — use whichever fixture the existing tests use, e.g. the one already exercised in `DuckDBParquetServiceTest`.)

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew test --tests "com.github.jhordyhuaman.parquetstudio.DuckDBParquetServiceTest"`
Expected: the two new tests FAIL with `IO Error: No files found that match the pattern` (reproducing the user-reported bug). Existing tests still pass.

- [ ] **Step 3: Integrate in `loadParquet`**

At the top of `loadParquet`, after the driver check, wrap the body:

```java
    File readable = SafeParquetPath.toReadable(file);
    try {
      // ... existing body, but every ps.setString(1, file.getAbsolutePath())
      // becomes ps.setString(1, readable.getAbsolutePath())
    } finally {
      if (SafeParquetPath.isTempCopy(file, readable)) {
        if (!readable.delete()) {
          LOGGER.warn("Could not delete temp copy: " + readable.getAbsolutePath());
        }
      }
    }
```

There are exactly two `ps.setString(1, file.getAbsolutePath())` call sites (schema query line ~77 and data query line ~93) — change both. Add `import com.github.jhordyhuaman.parquetstudio.service.SafeParquetPath;` is unnecessary (same package).

- [ ] **Step 4: Integrate in `saveParquet`**

Wrap the whole existing connection/export body so DuckDB writes to the file `SafeParquetPath` chooses:

```java
  public void saveParquet(File file, ParquetData data) throws Exception {
    LOGGER.info("Saving Parquet file: " + file.getAbsolutePath());
    if (data.getColumnNames().isEmpty()) {
      throw new IllegalArgumentException("No columns to save");
    }
    if (!driverLoaded) {
      throw new SQLException("DuckDB JDBC driver not loaded. Check classpath for org.duckdb:duckdb_jdbc dependency.");
    }
    SafeParquetPath.writeThenMove(file, actualTarget -> doSaveParquet(actualTarget, data));
  }

  private void doSaveParquet(File file, ParquetData data) throws Exception {
    // = the previous body of saveParquet from "try (Connection conn = ..." onward,
    // unchanged (it already uses file.getAbsolutePath() in the COPY statement).
  }
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew test --tests "com.github.jhordyhuaman.parquetstudio.DuckDBParquetServiceTest"`
Expected: ALL pass, including the two new ones. Then `./gradlew test` — all green.

- [ ] **Step 6: Commit**

```bash
git add -A src/main/java/com/github/jhordyhuaman/parquetstudio/service/DuckDBParquetService.java src/test/java/com/github/jhordyhuaman/parquetstudio/DuckDBParquetServiceTest.java
git commit -m "fix: read/write Parquet via safe temp path — fixes DuckDB 'No files found that match the pattern' on Windows long paths and glob chars"
```

---

### Task 4: Clean up `saveParquet` (dummy-row hack, hardcoded column 10, debug blocks)

**Files:**
- Modify: `src/main/java/com/github/jhordyhuaman/parquetstudio/service/DuckDBParquetService.java` (inside `doSaveParquet` after Task 3)
- Test (modify): `src/test/java/com/github/jhordyhuaman/parquetstudio/DuckDBParquetServiceTest.java`

**Interfaces:** unchanged public API.

- [ ] **Step 1: Write the failing/pinning test** — an all-NULL DECIMAL column must survive a save/load round-trip with its type intact and correct row count:

```java
  @Test
  void allNullDecimalColumnKeepsTypeAndRowCountOnSave() throws Exception {
    java.util.List<String> names = java.util.List.of("id", "amount");
    java.util.List<String> types = java.util.List.of("INTEGER", "DECIMAL(23,10)");
    java.util.List<java.util.List<Object>> rows = new java.util.ArrayList<>();
    rows.add(java.util.Arrays.asList(1, null));
    rows.add(java.util.Arrays.asList(2, null));
    ParquetData data = new ParquetData(names, types, rows);

    java.io.File target = safePathTempDir.resolve("allnull.parquet").toFile();
    DuckDBParquetService service = new DuckDBParquetService();
    service.saveParquet(target, data);

    ParquetData reloaded = service.loadParquet(target);
    assertThat(reloaded.getRows()).hasSize(2);                 // no dummy row leaked
    assertThat(reloaded.getColumnTypes().get(1)).startsWith("DECIMAL(23,10)");
    assertThat(reloaded.getColumnTypes().get(0)).isEqualTo("INTEGER");
  }
```

(Adjust the `ParquetData` constructor call to its actual signature — check `ParquetData.java`; it is `(List<String> columnNames, List<String> columnTypes, List<List<Object>> rows)`.)

- [ ] **Step 2: Run it**

Run: `./gradlew test --tests "com.github.jhordyhuaman.parquetstudio.DuckDBParquetServiceTest"`
Expected: PASS or FAIL — record which. This test pins behavior. It also would have crashed pre-Task-4 if the file had ≤10 columns? No — that crash is in save. Verify: with 2 columns, the current code at `data.getColumnNames().get(10)` (line ~201) throws `IndexOutOfBoundsException`, so expected: **FAIL with IndexOutOfBoundsException** — this is the bug being fixed.

- [ ] **Step 3: Delete from `doSaveParquet`:**
  1. The "VERIFYING TABLE SCHEMA" `DESCRIBE` block (former lines 157–168).
  2. The "VERIFYING DATA TYPES AFTER INSERT" block with hardcoded `get(10)` (former lines 198–205).
  3. The entire dummy-row machinery: `needsDummyRow` detection, `_dummy_marker` ALTER/INSERT, and the conditional export query (former lines 216–276). Replace with the single export:

```java
      String exportQuery = "COPY (SELECT * FROM " + tempTable + ") TO '"
          + file.getAbsolutePath().replace("'", "''") + "' (FORMAT PARQUET)";
      try (Statement st = conn.createStatement()) {
        st.execute(exportQuery);
      }
```

  4. Keep `nullCounts`/`valueCounts` ONLY if still referenced; after removing the blocks above they are unused — delete them and the per-column NULL-ratio logging loop (former lines 207–214). `buildColumnList` becomes unused — delete it.
  5. Downgrade remaining per-column `LOGGER.info("Column ...")` in the DDL loop to `LOGGER.debug`.

- [ ] **Step 4: Run tests**

Run: `./gradlew test`
Expected: ALL pass, including `allNullDecimalColumnKeepsTypeAndRowCountOnSave` (DuckDB preserves the declared column type in `COPY TO` even when all values are NULL — this test proves the hack was unnecessary; if it FAILS with the type degraded, STOP and report instead of re-adding the hack).

- [ ] **Step 5: Commit**

```bash
git add -A src/main/java/com/github/jhordyhuaman/parquetstudio/service/DuckDBParquetService.java src/test/java/com/github/jhordyhuaman/parquetstudio/DuckDBParquetServiceTest.java
git commit -m "fix: remove dummy-row hack and hardcoded column-10 debug check in saveParquet (IndexOutOfBounds on files with <=10 columns)"
```

---

### Task 5: Lazy, retryable DuckDB driver loading

**Files:**
- Modify: `src/main/java/com/github/jhordyhuaman/parquetstudio/service/DuckDBParquetService.java` (static block, lines 33–47; the two `if (!driverLoaded)` checks)

**Interfaces:** unchanged public API. Internal: `private static synchronized void ensureDriverLoaded() throws SQLException`.

- [ ] **Step 1: Replace the one-shot static block**

```java
  private static volatile boolean driverLoaded = false;

  private static synchronized void ensureDriverLoaded() throws SQLException {
    if (driverLoaded) {
      return;
    }
    try {
      Class<?> driverClass = Class.forName("org.duckdb.DuckDBDriver");
      Driver driver = (Driver) driverClass.getDeclaredConstructor().newInstance();
      DriverManager.registerDriver(driver);
      driverLoaded = true;
      LOGGER.info("DuckDB JDBC driver loaded");
    } catch (Exception e) {
      LOGGER.error("Failed to load DuckDB JDBC driver", e);
      throw new SQLException(
          "DuckDB JDBC driver could not be loaded: " + e.getMessage()
              + ". Try restarting the IDE.", e);
    }
  }
```

Delete the `static { ... }` block. In `loadParquet` and `saveParquet`, replace the `if (!driverLoaded) throw ...` checks with `ensureDriverLoaded();`. Also delete the JDBC driver-enumeration debug logging in `loadParquet` (lines 53–62) and downgrade the remaining "Attempting to create connection" / "Connection established" logs to `LOGGER.debug`.

- [ ] **Step 2: Run tests**

Run: `./gradlew test`
Expected: ALL pass (driver loads lazily on first use).

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/github/jhordyhuaman/parquetstudio/service/DuckDBParquetService.java
git commit -m "fix: lazy retryable DuckDB driver loading instead of one-shot static init"
```

---

### Task 6: Project service replaces Swing-tree scan

**Files:**
- Create: `src/main/java/com/github/jhordyhuaman/parquetstudio/service/ParquetStudioWindowService.java`
- Modify: `src/main/java/com/github/jhordyhuaman/parquetstudio/factory/ParquetToolWindowFactory.java`
- Modify: `src/main/resources/META-INF/plugin.xml`

**Interfaces:**
- Produces (used by Task 7):
  - `ParquetStudioWindowService.getInstance(Project project)` → service
  - `void registerPanel(ParquetToolWindow panel)` / `@Nullable ParquetToolWindow getPanel()`

- [ ] **Step 1: Create the service**

```java
package com.github.jhordyhuaman.parquetstudio.service;

import com.github.jhordyhuaman.parquetstudio.ui.ParquetToolWindow;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.Nullable;

/**
 * Project-level registry for the ParquetToolWindow panel, so callers reach it
 * via API instead of scanning the tool window's Swing hierarchy (which differs
 * across IntelliJ versions).
 */
@Service(Service.Level.PROJECT)
public final class ParquetStudioWindowService {
  private volatile ParquetToolWindow panel;

  public static ParquetStudioWindowService getInstance(Project project) {
    return project.getService(ParquetStudioWindowService.class);
  }

  public void registerPanel(ParquetToolWindow panel) {
    this.panel = panel;
  }

  @Nullable
  public ParquetToolWindow getPanel() {
    return panel;
  }
}
```

Note: light services annotated `@Service` need no `plugin.xml` registration on platform 233. Do NOT add a `<projectService>` entry.

- [ ] **Step 2: Register the panel in the factory** — replace the body of `createToolWindowContent`:

```java
  @Override
  public void createToolWindowContent(Project project, ToolWindow toolWindow) {
    ParquetToolWindow parquetToolWindow = new ParquetToolWindow();
    ParquetStudioWindowService.getInstance(project).registerPanel(parquetToolWindow);
    ContentFactory contentFactory = ContentFactory.getInstance();
    Content content = contentFactory.createContent(parquetToolWindow, "", false);
    toolWindow.getContentManager().addContent(content);
  }
```

(add `import com.github.jhordyhuaman.parquetstudio.service.ParquetStudioWindowService;`)

- [ ] **Step 3: Build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL (service not yet consumed — that is Task 7).

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/github/jhordyhuaman/parquetstudio/service/ParquetStudioWindowService.java src/main/java/com/github/jhordyhuaman/parquetstudio/factory/ParquetToolWindowFactory.java
git commit -m "feat: project service exposing ParquetToolWindow panel"
```

---

### Task 7: Rewrite `ParquetFileEditor` opening flow (no retries, no flags, no scan)

**Files:**
- Modify: `src/main/java/com/github/jhordyhuaman/parquetstudio/factory/ParquetFileEditor.java`
- Modify: `src/main/java/com/github/jhordyhuaman/parquetstudio/factory/ParquetEditorProvider.java`
- Modify: `src/main/java/com/github/jhordyhuaman/parquetstudio/Constants.java`

**Interfaces:**
- Consumes: `ParquetStudioWindowService` (Task 6); `ParquetToolWindow.openFileInTab(File)` (made idempotent in Task 8 — this task can land first because `openFileInTab` already switches to an existing tab).

Design: opening becomes **idempotent and stateless**. Every `selectNotify()` (and construction) simply asks the tool window to show the file; `ParquetToolWindow` decides whether that means "new tab" or "switch to existing tab". Delete `fileOpened`, `openingInProgress`, `retryCount`, all `Timer` retries, and `findParquetToolWindowRecursive`.

- [ ] **Step 1: Reject non-local files in the provider** — in `ParquetEditorProvider.accept`:

```java
  @Override
  public boolean accept(@NotNull Project project, @NotNull VirtualFile file) {
    return file.isInLocalFileSystem()
        && file.getExtension() != null
        && file.getExtension().equalsIgnoreCase("parquet");
  }
```

- [ ] **Step 2: Rewrite the open path in `ParquetFileEditor`**

Delete fields `fileOpened`, `openingInProgress`, `retryCount` and methods `tryOpenWithRetry`, `openFileInToolWindow`, `handleOpenFailure`, `findParquetToolWindowRecursive`. Delete now-unused imports (`AtomicBoolean`, `AtomicInteger`, `Timer` usage). Replace `openInParquetStudio` with:

```java
  private void openInParquetStudio() {
    ApplicationManager.getApplication().invokeLater(() -> {
      if (project.isDisposed()) {
        return;
      }
      ToolWindow toolWindow =
          ToolWindowManager.getInstance(project).getToolWindow("Parquet Studio");
      if (toolWindow == null) {
        showErrorNotification(Constants.Message.ERROR_OPENING_TOOL_WINDOW);
        return;
      }
      toolWindow.activate(() -> {
        ParquetToolWindow panel =
            ParquetStudioWindowService.getInstance(project).getPanel();
        if (panel == null) {
          // Factory runs during activate(); content not created yet on this EDT pass.
          ApplicationManager.getApplication().invokeLater(() -> {
            ParquetToolWindow retryPanel =
                ParquetStudioWindowService.getInstance(project).getPanel();
            if (retryPanel != null) {
              retryPanel.openFileInTab(new File(file.getPath()));
            } else {
              showErrorNotification(Constants.Message.ERROR_OPENING_TOOL_WINDOW);
            }
          });
          return;
        }
        panel.openFileInTab(new File(file.getPath()));
      }, true);
    });
  }
```

(add `import com.github.jhordyhuaman.parquetstudio.service.ParquetStudioWindowService;`)

`selectNotify()` becomes:

```java
  @Override
  public void selectNotify() {
    if (validateFile()) {
      openInParquetStudio();
    }
  }
```

Constructor: keep `if (validateFile()) { openInParquetStudio(); }` — double invocation (constructor + first selectNotify) is now harmless because `openFileInTab` is idempotent.

Replace the loading panel copy so users understand the editor tab is a pointer, not a viewer. In `createLoadingPanel`, change the two labels:
- `Constants.Message.LOADING_INITIALIZING` → new constant `Constants.Message.OPENED_IN_TOOL_WINDOW` = `"This file is open in the Parquet Studio tool window"` (add to `Constants.Message`).
- Remove the indeterminate `JProgressBar` block (it spins forever); keep icon + file name, and add a button that re-triggers `openInParquetStudio()`:

```java
    gbc.gridy = 3;
    JButton showButton = new JButton("Show in Parquet Studio");
    showButton.addActionListener(e -> openInParquetStudio());
    panel.add(showButton, gbc);
```

- [ ] **Step 3: Build and run existing tests**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL; `ParquetToolWindowTest` still passes.

- [ ] **Step 4: Manual verification in sandbox**

Run: `./gradlew runIde`
In the sandbox IDE: create/open a project containing a `.parquet` file (copy `src/test/resources/parquet/logical_date.parquet` in). Verify, in order:
1. Double-click the file → tool window opens with the data in a tab.
2. Double-click the same file again → switches to the existing tab, no duplicate.
3. Close the tool-window tab, then click the still-open editor tab → the file REOPENS in the tool window (this was the `fileOpened` bug).
4. Editor tab shows "This file is open in the Parquet Studio tool window" + working button, no infinite spinner.
Record the results of each in the task report.

- [ ] **Step 5: Commit**

```bash
git add -A src/main/java/com/github/jhordyhuaman/parquetstudio/factory src/main/java/com/github/jhordyhuaman/parquetstudio/Constants.java
git commit -m "fix: idempotent stateless file opening via project service (removes retries, Swing scan, stale fileOpened flag)"
```

---

### Task 8: Simplify `ParquetToolWindow.openParquetFileInTab` (instance state, EDT-only, load-then-add)

**Files:**
- Modify: `src/main/java/com/github/jhordyhuaman/parquetstudio/ui/ParquetToolWindow.java`
- Test (modify): `src/test/java/com/github/jhordyhuaman/parquetstudio/ParquetToolWindowTest.java`

**Interfaces:**
- Produces: `openFileInTab(File)` — idempotent, must be called on EDT (Task 7 guarantees this; the JFileChooser path is already EDT).

Design: all tab bookkeeping happens on the EDT, so the `static` set, `synchronized` blocks, and the duplicated double-check disappear. The dead `panelToTabIndex` map goes too.

- [ ] **Step 1: Read the existing `ParquetToolWindowTest`** to see which behaviors are pinned (tab count, duplicate prevention) and keep them passing. Add one test if not present:

```java
  @Test
  void openingSameFileTwiceCreatesOneTab() throws Exception {
    // Follow the existing test's construction pattern for ParquetToolWindow and
    // a fixture file; run assertions via SwingUtilities.invokeAndWait since
    // openFileInTab is now EDT-only.
    java.io.File fixture = new java.io.File("src/test/resources/parquet/logical_date.parquet");
    ParquetToolWindow window = new ParquetToolWindow();
    javax.swing.SwingUtilities.invokeAndWait(() -> {
      window.openFileInTab(fixture);
      window.openFileInTab(fixture);
    });
    javax.swing.SwingUtilities.invokeAndWait(() -> {});
    org.assertj.core.api.Assertions.assertThat(window.getTabCount()).isEqualTo(1);
  }
```

(If an equivalent test already exists, adapt it to `invokeAndWait` instead of adding a duplicate.)

- [ ] **Step 2: Rewrite `openParquetFileInTab`**

Delete: `private static final Set<String> openingFiles`, the `Map<ParquetEditorPanel, Integer> panelToTabIndex` field, `updateTabMappings()`, and every `synchronized (ParquetToolWindow.class)` block. New implementation:

```java
  private void openParquetFileInTab(File file) {
    if (!SwingUtilities.isEventDispatchThread()) {
      SwingUtilities.invokeLater(() -> openParquetFileInTab(file));
      return;
    }
    String filePath = getNormalizedPath(file);

    int existing = findTabIndexForPath(filePath);
    if (existing >= 0) {
      showTabsPanel();
      tabbedPane.setSelectedIndex(existing);
      return;
    }

    ParquetEditorPanel editorPanel = new ParquetEditorPanel();
    showTabsPanel();
    tabbedPane.addTab(file.getName() + "  \u00d7", null, editorPanel, file.getAbsolutePath());
    tabbedPane.setSelectedIndex(tabbedPane.getTabCount() - 1);
    // Load AFTER the tab exists so failures render inside the tab, not before it.
    editorPanel.loadParquetFile(file);
    LOGGER.info("Opened file in new tab: " + file.getName());
  }

  private int findTabIndexForPath(String normalizedPath) {
    for (int i = 0; i < tabbedPane.getTabCount(); i++) {
      Component component = tabbedPane.getComponentAt(i);
      if (component instanceof ParquetEditorPanel) {
        ParquetEditorPanel panel = (ParquetEditorPanel) component;
        File current = panel.hasFile() ? panel.getCurrentFile()
            : panel.getLoadingOrCurrentFile();
        if (current != null && getNormalizedPath(current).equals(normalizedPath)) {
          return i;
        }
      }
    }
    return -1;
  }
```

Important: the duplicate check must also match a file that is still **loading** (previously `hasFile()` was false during load, which is exactly the race that produced duplicate tabs). Check `ParquetEditorPanel` for the `loadingFile` field (it exists, set in `loadParquetFile`); add to `ParquetEditorPanel`:

```java
  /** Returns the loaded file, or the file currently being loaded, or null. */
  public File getLoadingOrCurrentFile() {
    if (hasFile()) {
      return getCurrentFile();
    }
    return loadingFile;
  }
```

In `closeTab`, remove the `panelToTabIndex.remove(panel)` and `updateTabMappings()` calls (fields deleted). Keep `updateTabComponents()` and `updateView()`.

- [ ] **Step 3: Run tests**

Run: `./gradlew test --tests "com.github.jhordyhuaman.parquetstudio.ParquetToolWindowTest"`
Expected: ALL pass (including the duplicate-prevention test). Then `./gradlew test` — all green.

- [ ] **Step 4: Manual verification in sandbox**

Run: `./gradlew runIde`
1. Rapid-fire double-click the same `.parquet` file 5 times quickly → exactly one tab.
2. Open two different files → two tabs; close one → welcome panel logic intact (close the second too → welcome panel shows).
3. Open a file, and while its loading panel is visible, double-click it again in the project tree → still one tab.

- [ ] **Step 5: Commit**

```bash
git add -A src/main/java/com/github/jhordyhuaman/parquetstudio/ui src/test/java/com/github/jhordyhuaman/parquetstudio/ParquetToolWindowTest.java
git commit -m "fix: EDT-only idempotent tab opening; remove static openingFiles set and stale index map"
```

---

### Task 9: Unsaved-changes guard on tab close

**Files:**
- Modify: `src/main/java/com/github/jhordyhuaman/parquetstudio/ui/ParquetEditorPanel.java`
- Modify: `src/main/java/com/github/jhordyhuaman/parquetstudio/ui/ParquetToolWindow.java` (`closeTab`)
- Test (modify): `src/test/java/com/github/jhordyhuaman/parquetstudio/ParquetEditorPanelTest.java`

**Interfaces:**
- Produces: `ParquetEditorPanel.isDirty()` / package-visible `markSaved()`.

- [ ] **Step 1: Write the failing test** (append to `ParquetEditorPanelTest`, following its existing construction pattern — read it first):

```java
  @Test
  void panelBecomesDirtyOnModelEditAndCleanAfterMarkSaved() throws Exception {
    java.io.File fixture = new java.io.File("src/test/resources/parquet/logical_date.parquet");
    ParquetEditorPanel panel = new ParquetEditorPanel();
    javax.swing.SwingUtilities.invokeAndWait(() -> panel.loadParquetFile(fixture));
    // Wait for the async SwingWorker load to finish.
    long deadline = System.currentTimeMillis() + 15000;
    while (!panel.hasFile() && System.currentTimeMillis() < deadline) {
      Thread.sleep(100);
    }
    org.assertj.core.api.Assertions.assertThat(panel.hasFile()).isTrue();
    org.assertj.core.api.Assertions.assertThat(panel.isDirty()).isFalse();

    javax.swing.SwingUtilities.invokeAndWait(() ->
        panel.getTableModel().setValueAt("changed", 0, 0));
    org.assertj.core.api.Assertions.assertThat(panel.isDirty()).isTrue();

    panel.markSaved();
    org.assertj.core.api.Assertions.assertThat(panel.isDirty()).isFalse();
  }
```

(If `ParquetEditorPanel` has no `getTableModel()` accessor, add one — check first; `tableModel` is a field. If existing tests use a different wait pattern for async load, reuse theirs.)

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew test --tests "com.github.jhordyhuaman.parquetstudio.ParquetEditorPanelTest"`
Expected: COMPILE FAILURE (`isDirty`/`markSaved` missing).

- [ ] **Step 3: Implement in `ParquetEditorPanel`**

```java
  private volatile boolean dirty = false;
  private final javax.swing.event.TableModelListener dirtyListener = e -> dirty = true;

  public boolean isDirty() {
    return dirty;
  }

  public void markSaved() {
    dirty = false;
  }
```

Wire-up points (all inside `ParquetEditorPanel`):
1. In `loadParquetFile`'s `done()` success branch, after `dataTable.setModel(tableModel)`: `tableModel.addTableModelListener(dirtyListener); dirty = false;`
2. Structural edits also dirty the panel: find the add-row / delete-row / add-column / delete-column action handlers (search for the button action listeners around lines 700–950) and add `dirty = true;` at the end of each successful mutation **if** those mutations do not already fire `TableModelListener` events (row insert/delete through `ParquetTableModel` typically fire events — verify by reading `ParquetTableModel`; only add manual `dirty = true` where no event fires).
3. In the save action's `SwingWorker.done()` success branch (around line 1104–1145), call `markSaved();`.

- [ ] **Step 4: Guard in `ParquetToolWindow.closeTab`** — before removing the tab:

```java
      if (panel.isDirty()) {
        int choice = javax.swing.JOptionPane.showConfirmDialog(
            this,
            "\"" + panel.getDisplayName() + "\" has unsaved changes. Close anyway?",
            "Unsaved Changes",
            javax.swing.JOptionPane.YES_NO_OPTION,
            javax.swing.JOptionPane.WARNING_MESSAGE);
        if (choice != javax.swing.JOptionPane.YES_OPTION) {
          return;
        }
      }
```

(`JOptionPane` rather than IntelliJ `Messages` keeps `ParquetToolWindowTest` runnable headless-ish and matches the panel's plain-Swing style; the panel already uses `Messages` elsewhere — either is acceptable, but `JOptionPane` avoids needing an `Application` in tests. Do not block: this runs on EDT from a user click only.)

- [ ] **Step 5: Run tests, then manual check**

Run: `./gradlew test` → all green.
`./gradlew runIde`: edit a cell, close the tab → confirmation appears; save, close → no confirmation.

- [ ] **Step 6: Commit**

```bash
git add -A src/main/java/com/github/jhordyhuaman/parquetstudio/ui src/test/java/com/github/jhordyhuaman/parquetstudio/ParquetEditorPanelTest.java
git commit -m "feat: warn before closing a tab with unsaved changes"
```

---

### Task 10: Surface silent NULL conversions on save

**Files:**
- Modify: `src/main/java/com/github/jhordyhuaman/parquetstudio/service/DuckDBParquetService.java` (`setParameter`, `doSaveParquet`)
- Test (modify): `src/test/java/com/github/jhordyhuaman/parquetstudio/DuckDBParquetServiceTest.java`

**Interfaces:**
- Produces: `saveParquet` throws no new checked types, but after a save the service exposes `public List<String> getLastSaveConversionWarnings()` — one entry per column with lost values, e.g. `"amount: 3 value(s) could not be converted to DECIMAL(23,10) and were saved as NULL"`. Task 11 shows them in the UI.

- [ ] **Step 1: Write the failing test**

```java
  @Test
  void reportsValuesSilentlyConvertedToNullOnSave() throws Exception {
    java.util.List<String> names = java.util.List.of("id", "amount");
    java.util.List<String> types = java.util.List.of("INTEGER", "DECIMAL(10,2)");
    java.util.List<java.util.List<Object>> rows = new java.util.ArrayList<>();
    rows.add(java.util.Arrays.asList(1, "not-a-number"));
    rows.add(java.util.Arrays.asList(2, "12.50"));
    ParquetData data = new ParquetData(names, types, rows);

    DuckDBParquetService service = new DuckDBParquetService();
    java.io.File target = safePathTempDir.resolve("warns.parquet").toFile();
    service.saveParquet(target, data);

    org.assertj.core.api.Assertions.assertThat(service.getLastSaveConversionWarnings())
        .hasSize(1);
    org.assertj.core.api.Assertions.assertThat(service.getLastSaveConversionWarnings().get(0))
        .contains("amount").contains("1 value");
  }
```

- [ ] **Step 2: Run to verify it fails** (compile error: missing method).

- [ ] **Step 3: Implement**

In `DuckDBParquetService` add a field and accessor:

```java
  private final List<String> lastSaveConversionWarnings = new ArrayList<>();

  public List<String> getLastSaveConversionWarnings() {
    return new ArrayList<>(lastSaveConversionWarnings);
  }
```

Change `setParameter`'s signature to return an enum-like int or, simpler, keep `boolean wasNull` but distinguish *intentional* null (input null/empty) from *failed conversion*: change return type to `private int setParameter(...)` returning `0` = value set, `1` = null input, `2` = conversion failed (the `catch (NumberFormatException e)` branch at former line 481). In `doSaveParquet`, clear `lastSaveConversionWarnings` at the start, count code==2 per column in a `int[] failedCounts`, and after the insert loop:

```java
      for (int i = 0; i < data.getColumnNames().size(); i++) {
        if (failedCounts[i] > 0) {
          lastSaveConversionWarnings.add(
              data.getColumnNames().get(i) + ": " + failedCounts[i]
                  + " value(s) could not be converted to "
                  + toDuckDBType(data.getColumnTypes().get(i))
                  + " and were saved as NULL");
        }
      }
```

- [ ] **Step 4: Run tests** → all green.

- [ ] **Step 5: Commit**

```bash
git add -A src/main/java/com/github/jhordyhuaman/parquetstudio/service/DuckDBParquetService.java src/test/java/com/github/jhordyhuaman/parquetstudio/DuckDBParquetServiceTest.java
git commit -m "feat: collect per-column conversion-to-NULL warnings during save"
```

---

### Task 11: Show conversion warnings in the save flow

**Files:**
- Modify: `src/main/java/com/github/jhordyhuaman/parquetstudio/ui/ParquetEditorPanel.java` (save `SwingWorker.done()`, ~lines 1104–1145)
- Modify: `src/main/java/com/github/jhordyhuaman/parquetstudio/service/ParquetEditorService.java` (expose the warnings — read the file first; it wraps `DuckDBParquetService`, add a passthrough `public List<String> getLastSaveConversionWarnings()` delegating to its `DuckDBParquetService` field).

**Interfaces:**
- Consumes: `getLastSaveConversionWarnings()` from Task 10.

- [ ] **Step 1: Wire the passthrough in `ParquetEditorService`** (read the file to find the `DuckDBParquetService` field name; add the one-line delegate).

- [ ] **Step 2: In the save worker's `done()` success branch**, after the existing success handling (and the `markSaved()` from Task 9):

```java
                java.util.List<String> warnings =
                    editorService.getLastSaveConversionWarnings();
                if (!warnings.isEmpty()) {
                  Messages.showWarningDialog(
                      "Saved, but some values could not be converted and were written as NULL:\n\n"
                          + String.join("\n", warnings),
                      "Save Completed With Warnings");
                }
```

- [ ] **Step 3: Build + manual check**

Run: `./gradlew build` → green. `./gradlew runIde`: load a file with a DECIMAL column, type garbage text into a DECIMAL cell, save → warning dialog lists the column; clean save → no dialog.

- [ ] **Step 4: Commit**

```bash
git add -A src/main/java/com/github/jhordyhuaman/parquetstudio
git commit -m "feat: warn user when save converted unparseable values to NULL"
```

---

### Task 12: Release hygiene

**Files:**
- Modify: `gradle.properties` (`pluginVersion`, `changeNotes`)
- Modify: `CHANGELOG.md` (follow its existing format — read it first)
- Modify: `src/main/resources/META-INF/plugin.xml` (vendor email `jhordyhuaman@example.com` → ask the maintainer; if unavailable, leave and note it in the report)

- [ ] **Step 1: Bump version**

`pluginVersion=1.6.0`; `changeNotes=Fix: Windows long-path and special-character file loading (DuckDB pattern error). Rewritten file-opening flow (no more intermittent open/close failures). Warn on unsaved changes and on values converted to NULL during save. Removed save-time crash on files with 10 or fewer columns.`

- [ ] **Step 2: Update CHANGELOG.md** with a `## [1.6.0]` section mirroring the change notes, matching the file's existing style.

- [ ] **Step 3: Full verification**

Run: `./gradlew clean build verifyPlugin`
Expected: BUILD SUCCESSFUL, all tests pass, plugin verification clean.

- [ ] **Step 4: Commit**

```bash
git add gradle.properties CHANGELOG.md src/main/resources/META-INF/plugin.xml
git commit -m "chore: release 1.6.0"
```

---

## Out of scope (explicitly deferred — do NOT do these now)

- Pagination / streaming for large files (biggest perf win, separate plan).
- Rendering data inside the `FileEditor` instead of the tool window (larger redesign).
- Migration to IntelliJ Platform Gradle Plugin 2.x.
- Migrating Swing components to `JBTable`/`SearchTextField`/`FileChooserFactory`.
- Upgrading `duckdb_jdbc` (upstream bug unfixed; revisit when duckdb/duckdb#20384 closes).

## Verification matrix (final acceptance, run after Task 12)

| # | Scenario | Expected |
|---|----------|----------|
| 1 | `./gradlew clean build verifyPlugin` | green |
| 2 | Load fixture with `[1]` in filename (test) | loads |
| 3 | Save to filename with `[x]` (test) | saves, reloads |
| 4 | Save 2-column file (test) | no IndexOutOfBounds |
| 5 | All-NULL DECIMAL column round-trip (test) | type + row count preserved |
| 6 | sandbox: double-click parquet ×2 | one tab |
| 7 | sandbox: close tool-window tab, reselect editor tab | reopens |
| 8 | sandbox: dirty tab close | confirmation dialog |
| 9 | sandbox: garbage in DECIMAL cell + save | warning dialog |

Windows-specific long-path behavior cannot be executed on this macOS machine; the detection logic is unit-tested with `isWindows=true` injection (Task 1), and the temp-copy path is exercised by the glob-char tests on all OSes. Note this residual risk in the final report.
