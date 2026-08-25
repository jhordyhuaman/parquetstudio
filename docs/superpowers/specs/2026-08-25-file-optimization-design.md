# File Optimization (Compact / Fragment / Consolidate) — Design Spec

Approved by the maintainer on 2026-08-25 (conversation). Ships as v1.7.0.

## Purpose

Give data engineers Spark-like file management (`coalesce`/`repartition`) inside the editor, powered by DuckDB only. The toolbar "Compact" button becomes an "Optimize" button that opens a dialog with three operations.

## Operations

### 1. Compact (already implemented on this branch)
Rewrite the currently open file in place with ZSTD compression. Report `before → after (-NN%)`; report plainly when size did not shrink. Counts as a save (clears dirty flag). Atomic via existing temp+move.

### 2. Fragment
Split the currently open file into `part-00000.parquet, part-00001.parquet, …` in a user-chosen destination directory, ZSTD-compressed. Three criteria (radio buttons + one numeric field):
- **N files**: rows-per-part = ceil(totalRows / N).
- **Rows per file**: user-given X rows per part.
- **Approx. size per file**: user-given X MB; rows-per-part computed as `max(1, floor(X_MB / (currentFileSize/totalRows)))` — an approximation, labeled "approx." in the UI.

Implementation: version-proof LIMIT/OFFSET loop — one DuckDB `COPY (SELECT * FROM read_parquet(src) LIMIT r OFFSET k*r) TO 'part-….parquet' (FORMAT PARQUET, COMPRESSION ZSTD)` per part. Do NOT rely on `FILE_SIZE_BYTES`/`ROW_GROUPS_PER_FILE` COPY options (version-dependent in duckdb_jdbc 0.10.2). The source file is never modified. Source path goes through `SafeParquetPath.toReadable`; each part is written directly by DuckDB into the destination directory (destination chosen by the user via directory chooser; if any `part-*.parquet` already exists there, ask confirm-overwrite first).

### 3. Consolidate
Pick a directory containing multiple `part-*.parquet` (same schema) → merge into ONE user-named output file, ZSTD-compressed (Spark `coalesce(1)` equivalent). Implementation: pre-validate schemas by reading each file's column names+types via a `LIMIT 0` query and comparing to the first file's; on mismatch, abort with a message naming the offending file and the differing column. Then `COPY (SELECT * FROM read_parquet([list of files])) TO output (FORMAT PARQUET, COMPRESSION ZSTD)` — pass an explicit sorted file list, not a glob, so SafeParquetPath concerns don't apply to pattern chars in the directory name; each source path still goes through `toReadable` if unsafe. Output written atomically (temp+move via `SafeParquetPath.writeThenMove`).

## UI

- Toolbar button (replaces the plain Compact button's action; same icon/tooltip updated to "Optimize file…").
- `OptimizeFileDialog` (follow `AddColumnDialog`'s plain-Swing style): radio group {Compact, Fragment, Consolidate}, contextual inputs enabled per selection, OK/Cancel. Fragment/Consolidate use `JFileChooser` (directories) consistent with existing style.
- All operations run in a `SwingWorker` mirroring the existing save worker; status label shows progress (`part 3/8…`); completion shows a summary notification (files created, total size before/after).
- Compact requires an open file; Fragment requires an open file; Consolidate works even with no file open (it reads from a directory).

## Errors

- Fragment with N > totalRows → cap N at totalRows (each part ≥1 row), inform in summary.
- Consolidate with 0 or 1 matching files → message, no-op.
- Any DuckDB failure → existing error-dialog style; no partial output left behind (fragment writes to the destination directly, so on failure mid-way, report which parts were written and that the source is untouched).

## Testing

Service-level (JUnit, no UI): fragment round-trip (fragment fixture into 3 parts → part count, total row count preserved, each part readable, schema identical); rows-per-file criterion honored (last part may be smaller); consolidate round-trip (fragment then consolidate → row count and schema equal original); consolidate schema-mismatch abort (two fixtures with different schemas → exception naming the file); N > rows capping. UI dialog untested (manual checklist).

## Out of scope (YAGNI)

Codec selection, compression level, partitioning by column values (Hive-style), multi-file editing, progress cancellation.
