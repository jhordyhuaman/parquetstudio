# Synthetic Data Generator — Design Spec

Approved by the maintainer on 2026-08-25 (conversation). Ships as v1.8.0.

## Purpose

Generate realistic synthetic rows for ingestion testing, from a schema. Two flows:
1. **New file from schema**: schema source = local `.schema`/`.json` file OR a remote URL (e.g. Artifactory) with optional auth token → choose row count → a new `.parquet` is written and opened in the editor.
2. **Add rows to the open file**: with a file open, generate N rows matching its current schema and append them to the table (unsaved, dirty flag set as with any edit).

No AI/LLM, no network beyond the schema fetch: generation is heuristic, offline, and deterministic when a seed is given.

## Components

### `SyntheticDataGenerator` (service, pure logic, no I/O)
`ParquetData generate(List<String> columnNames, List<String> columnTypes, int rowCount, Long seed, double nullRatio)`.
- Seed null → `new Random()`; seed given → `new Random(seed)` (reproducible).
- `nullRatio` (0.0–1.0, default 0.05): probability any generated cell is NULL. Applied per cell.
- **Type heuristics** (DuckDB type strings as produced by `normalizeType`):
  - `DECIMAL(p,s)`: random `BigDecimal` with exactly scale `s`, magnitude up to `min(p-s, 7)` integer digits, non-negative by default.
  - `INTEGER`: 0–999_999. `BIGINT`: 0–9_999_999_999L. `DOUBLE`/`FLOAT`: 0–1_000_000 with 2–4 decimals.
  - `DATE`: `LocalDate` between 2020-01-01 and today. `TIMESTAMP`: `LocalDateTime` in the same range with random time.
  - `BOOLEAN`: 50/50. `VARCHAR`: see name heuristics; fallback = 8–16 random lowercase alphanumerics.
- **Column-name heuristics** (case-insensitive substring match on the column name; refine the base type):
  - contains `date` or `fecha` (VARCHAR column) → ISO date string.
  - contains `id`, `code`, `codigo`, `cod` → uppercase alphanumeric code like `AB12CD34`.
  - contains `amount`, `monto`, `importe`, `price`, `precio` (numeric) → positive value with 2 decimals.
  - contains `name`, `nombre` → pick from a small embedded first/last-name dictionary (~40 entries, plain Java array).
  - contains `email`, `correo` → `<name><nn>@example.com`.
  - contains `phone`, `telefono` → 9-digit numeric string.
  - contains `country`, `pais` → ISO-2 code from a small embedded list.
  - No match → base type generator.
- Unknown/unsupported types → NULL cells for that column (never crash), and the result carries a warning list naming the skipped columns.

### `RemoteSchemaService` (service)
`String fetchSchema(String url, String token, TokenStyle style)` using JDK 17 `java.net.http.HttpClient` (no new deps).
- `TokenStyle`: `BEARER` (`Authorization: Bearer <t>`) or `JFROG` (`X-JFrog-Art-Api: <t>`); token optional (public URLs).
- 15s timeout; follows redirects; non-2xx → exception with status code and NO token in the message; the token is NEVER logged, persisted, or included in exceptions.
- Returns the response body; parsing is done by the existing `DataSchemaService` parser (same `.schema`/`.json` format the Schema panel loads). HTTPS and HTTP both allowed (corporate Artifactory often internal HTTP).

### UI
- **Tool window toolbar**: a "Generate Data" button (works with no file open) → `GenerateDataDialog`:
  - Schema source: radio {Local file → file chooser; URL → URL text field + token password-field + token-style combo (Bearer / JFrog)}.
  - Row count (default 100, max 1_000_000), optional seed, null % spinner (default 5).
  - OK → SwingWorker: fetch/read schema → parse → generate → write parquet (via existing save path, default compression) to a user-chosen target file → open it in a tab.
- **Editor toolbar**: an "Add synthetic rows" action (small dialog: row count, optional seed) → generates rows against the current model's columnNames/columnTypes and appends via the table model (fires events → dirty flag set automatically).
- Token field is a `JPasswordField`; its value lives only in memory for the single request.

## Errors
- Fetch failures (DNS, timeout, 401/403/404) → error dialog with status/reason, reminder to check URL/token; never echo the token.
- Parse failures → existing schema-parse error style, naming the source (file name or URL without query string).
- Generation warnings (skipped unsupported columns) → shown in the completion notification.

## Testing
- Generator: correct types per column (DECIMAL scale/precision respected, DATE/TIMESTAMP in range, row count exact); determinism (same seed → identical ParquetData twice); nullRatio 0 → no nulls, 1.0 → all nulls (nullable path); name heuristics (a column `customer_email` VARCHAR yields strings containing `@`); unknown type → all-NULL column + warning.
- RemoteSchemaService: against `com.sun.net.httpserver.HttpServer` on localhost — happy path returns body; Bearer and JFrog header actually sent (server asserts); 401 → exception whose message contains "401" and does NOT contain the token; timeout path optional.
- End-to-end: generate 50 rows from a schema built off an existing fixture's types → save → reload → row count 50 and types intact.

## Out of scope (YAGNI)
LLM-based generation, cross-column correlations, uniqueness constraints, locale packs, saving URL/token presets, appending directly to disk without the editor.
