# File Optimization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Turn the Compact button into an Optimize dialog with Compact / Fragment / Consolidate, per the spec.

**Architecture:** New `ParquetOptimizationService` holds fragment/consolidate logic (DuckDB LIMIT/OFFSET loop, explicit file lists, SafeParquetPath for unsafe paths). UI is one plain-Swing `OptimizeFileDialog` plus SwingWorker wiring in `ParquetEditorPanel`/`ParquetToolWindow`.

**Tech Stack:** Java 17, DuckDB JDBC 0.10.2, JUnit 5 + AssertJ, plain Swing.

**Spec:** docs/superpowers/specs/2026-08-25-file-optimization-design.md — the binding authority; read it before each task.

## Global Constraints
- Java 17; no new dependencies; 2-space indent; all existing tests keep passing (`./gradlew test`).
- Do not modify the existing compact save path (commits bace854/02d7da6 on this branch) except to relocate its button action behind the new dialog.
- Fragment/consolidate must NOT use `FILE_SIZE_BYTES`/`ROW_GROUPS_PER_FILE` COPY options (version-dependent) — LIMIT/OFFSET loop only.
- All ZSTD writes; consolidate output via `SafeParquetPath.writeThenMove`; fragment parts written directly to the destination dir.
- No manual runIde steps — gate is build+tests green; manual checklist goes to the maintainer at the end.

---

### Task 1: `ParquetOptimizationService` (fragment + consolidate) with tests

**Files:**
- Create: `src/main/java/com/github/jhordyhuaman/parquetstudio/service/ParquetOptimizationService.java`
- Test (create): `src/test/java/com/github/jhordyhuaman/parquetstudio/ParquetOptimizationServiceTest.java`

**Interfaces (Produces):**
```java
public class ParquetOptimizationService {
  /** Splits source into part files. Returns the created files in order. */
  public List<File> fragment(File source, File destDir, FragmentCriterion criterion, long value) throws Exception;
  public enum FragmentCriterion { NUM_FILES, ROWS_PER_FILE, APPROX_MB_PER_FILE }
  /** Merges the given parquet files (schema-validated) into output. Returns total rows written. */
  public long consolidate(List<File> sources, File output) throws Exception;
  /** Lists part-*.parquet (and *.parquet) files in dir, sorted by name. */
  public List<File> listParquetFiles(File dir);
}
```
Behavior per spec: count rows first (`SELECT COUNT(*)`), compute rows-per-part per criterion (NUM_FILES: ceil(total/N), N capped at total; ROWS_PER_FILE: value; APPROX_MB_PER_FILE: `max(1, floor(valueMB*1024*1024 / max(1, fileSize/total)))`), then one `COPY (SELECT * FROM read_parquet(?) LIMIT r OFFSET o) TO 'part-%05d.parquet' (FORMAT PARQUET, COMPRESSION ZSTD)` per part (source path via `SafeParquetPath.toReadable`, delete temp copy in finally; parts named `part-00000.parquet` upward). Consolidate: read each source's schema via `LIMIT 0` metadata; if any differs from the first (names or types, ordered), throw `IllegalArgumentException` naming the offending file and column; else `COPY (SELECT * FROM read_parquet([<quoted list>])) TO … (FORMAT PARQUET, COMPRESSION ZSTD)` through `writeThenMove`, escaping single quotes in each path. 0 sources → IllegalArgumentException; 1 source → still valid (acts as compact-to-new-file).

- [ ] Step 1: Failing tests — using the existing fixture the DuckDB tests use and `@TempDir`:
  - `fragmentIntoNFilesPreservesRowsAndSchema`: fragment into 3 → 3 files exist, sum of reloaded row counts == original, each part's columnNames/columnTypes equal original.
  - `fragmentByRowsHonorsChunkSize`: rows-per-file=2 on the fixture → every part except possibly the last has exactly 2 rows.
  - `fragmentCapsPartsAtRowCount`: NUM_FILES = totalRows+5 → number of parts == totalRows.
  - `consolidateRoundTrip`: fragment into 3, consolidate the parts → row count and schema equal original.
  - `consolidateRejectsMismatchedSchemas`: consolidate {fixtureA, a file saved from fixtureA with one column dropped via ParquetData manipulation} → IllegalArgumentException whose message contains the second file's name.
  - `consolidateRejectsEmptyList`: empty list → IllegalArgumentException.
- [ ] Step 2: run → RED (class missing). Step 3: implement. Step 4: targeted GREEN then full `./gradlew test`. Step 5: commit `feat: ParquetOptimizationService — fragment and consolidate via DuckDB`.

### Task 2: `OptimizeFileDialog` + toolbar wiring

**Files:**
- Create: `src/main/java/com/github/jhordyhuaman/parquetstudio/ui/OptimizeFileDialog.java`
- Modify: `src/main/java/com/github/jhordyhuaman/parquetstudio/ui/ParquetEditorPanel.java` (compact button becomes "Optimize file…", opens the dialog; Compact choice reuses the existing compact worker unchanged)
- Modify: `src/main/java/com/github/jhordyhuaman/parquetstudio/ui/ParquetToolWindow.java` (toolbar gains an Optimize button too, enabled always; with no open file only Consolidate is selectable)

**Interfaces (Consumes):** Task 1's service; existing compact worker; `formatFileSize`.

Dialog per spec (AddColumnDialog style): radio {Compact, Fragment, Consolidate}; Fragment panel: criterion radios + numeric field + dest-dir chooser; Consolidate panel: source-dir chooser (+ populated file-count label via `listParquetFiles`) + output file chooser; inputs enabled per selection; OK validates (positive numbers, dirs exist) before closing. Workers: fragment/consolidate run in SwingWorker publishing `part i/n`; completion notification summarizes files+sizes (existing notification style); failures use the existing error-dialog style and state the source was untouched. Overwrite confirm if destination already has `part-*.parquet`.

- [ ] Implement; `./gradlew build` green; existing tests pass; commit `feat: Optimize dialog — compact, fragment, consolidate`.

### Task 3: Release 1.7.0
- [ ] `pluginVersion=1.7.0`; `changeNotes=New: Optimize dialog — compact files with ZSTD, fragment into N files / by rows / by size, and consolidate part-files into one (Spark-style coalesce/repartition powered by DuckDB).`; CHANGELOG `## [1.7.0]` section in existing style.
- [ ] `./gradlew clean build verifyPlugin` green.
- [ ] Commit `chore: release 1.7.0`.

## Verification matrix
Automated: the 6 Task-1 tests + full suite + verifyPlugin. Manual (deferred to maintainer): dialog flows in runIde.
