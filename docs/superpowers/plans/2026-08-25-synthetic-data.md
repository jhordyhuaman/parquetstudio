# Synthetic Data Generator Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Generate realistic synthetic rows from a schema (local file, remote URL with token, or the open file's schema), per the spec.

**Architecture:** Two pure-ish services (`SyntheticDataGenerator` for generation, `RemoteSchemaService` for HTTP fetch) + one dialog and two toolbar actions. Parsing reuses the existing `DataSchemaService`.

**Tech Stack:** Java 17 (`java.net.http.HttpClient`, `com.sun.net.httpserver` for tests), JUnit 5 + AssertJ, plain Swing.

**Spec:** docs/superpowers/specs/2026-08-25-synthetic-data-design.md — binding authority; read before each task.

## Global Constraints
- Java 17; no new dependencies; 2-space indent; all existing tests keep passing.
- The auth token must NEVER appear in logs, exception messages, or persisted state; token field in UI is a `JPasswordField`.
- Generation is offline and deterministic with a seed; unknown types produce NULL columns + a warning, never a crash.
- No manual runIde steps — gate is build+tests green.

---

### Task 1: `SyntheticDataGenerator` with tests

**Files:**
- Create: `src/main/java/com/github/jhordyhuaman/parquetstudio/service/SyntheticDataGenerator.java`
- Test (create): `src/test/java/com/github/jhordyhuaman/parquetstudio/SyntheticDataGeneratorTest.java`

**Interfaces (Produces):**
```java
public class SyntheticDataGenerator {
  public GenerationResult generate(List<String> columnNames, List<String> columnTypes,
                                   int rowCount, Long seed, double nullRatio);
  public static final class GenerationResult {
    public ParquetData getData();
    public List<String> getWarnings(); // skipped/unsupported columns
  }
}
```
Behavior exactly per the spec's "Type heuristics" and "Column-name heuristics" sections (implement every listed rule; nullRatio applied per cell; unknown type → all-NULL column + warning `"<col>: unsupported type <T>, generated NULLs"`). Values must be typed objects matching what `ParquetTableModel`/save expect: `BigDecimal` for DECIMAL, `Integer`/`Long`/`Double`, `LocalDate`/`LocalDateTime`, `Boolean`, `String`.

- [ ] Step 1: failing tests: `generatesExactRowCountAndTypedValues` (DECIMAL(10,2) cells are BigDecimal with scale 2; DATE cells LocalDate in [2020-01-01, today]; INTEGER in range); `sameSeedIsDeterministic` (two calls, identical data); `nullRatioZeroAndOne`; `emailHeuristicProducesAtSign` (column `customer_email` VARCHAR); `codeHeuristicUppercaseAlnum` (column `g_entific_id`); `unknownTypeYieldsNullsAndWarning` (type `STRUCT(x INT)`).
- [ ] RED → implement → GREEN → full suite → commit `feat: SyntheticDataGenerator — heuristic typed synthetic rows`.

### Task 2: `RemoteSchemaService` with tests

**Files:**
- Create: `src/main/java/com/github/jhordyhuaman/parquetstudio/service/RemoteSchemaService.java`
- Test (create): `src/test/java/com/github/jhordyhuaman/parquetstudio/RemoteSchemaServiceTest.java`

**Interfaces (Produces):**
```java
public class RemoteSchemaService {
  public enum TokenStyle { BEARER, JFROG }
  /** Fetches the schema body from url. token may be null/blank for public URLs. */
  public String fetchSchema(String url, String token, TokenStyle style) throws Exception;
}
```
JDK HttpClient, 15s connect+request timeout, follow redirects. Header per style (`Authorization: Bearer <t>` / `X-JFrog-Art-Api: <t>`), only when token non-blank. Non-2xx → `IOException("Schema fetch failed: HTTP <code> for <url-without-query>")` — assert the token never appears in the message. No logging of the token anywhere.

- [ ] Step 1: failing tests using `com.sun.net.httpserver.HttpServer` bound to port 0 (ephemeral): `fetchesBodyOnOk`; `sendsBearerHeader` (server captures Authorization and test asserts value); `sendsJfrogHeader`; `non2xxThrowsWithoutToken` (401; message contains "401", does not contain the token string).
- [ ] RED → implement → GREEN → full suite → commit `feat: RemoteSchemaService — fetch schema over HTTP with optional token`.

### Task 3: UI — GenerateDataDialog + editor action + wiring

**Files:**
- Create: `src/main/java/com/github/jhordyhuaman/parquetstudio/ui/GenerateDataDialog.java`
- Modify: `src/main/java/com/github/jhordyhuaman/parquetstudio/ui/ParquetToolWindow.java` (toolbar "Generate Data" button → dialog → worker → write file via `ParquetEditorService`/`DuckDBParquetService` save path → open in tab via `openFileInTab`)
- Modify: `src/main/java/com/github/jhordyhuaman/parquetstudio/ui/ParquetEditorPanel.java` ("Add synthetic rows" toolbar action: small `JOptionPane`-style prompt or mini-dialog for row count + optional seed; append rows through the table model so events fire and dirty flag sets)
- Consumes: Task 1 `SyntheticDataGenerator.generate(...)`, Task 2 `fetchSchema(...)`, existing `DataSchemaService` parser (read it first to find the parse entry point and the `SchemaStructure` → names/types mapping used by the Schema panel).

Dialog per spec (AddColumnDialog style): schema-source radios (local file chooser / URL + JPasswordField token + TokenStyle combo), row count (1–1_000_000, default 100), optional seed field, null% spinner (0–100, default 5), target-file chooser. Worker chain with progress in status; completion notification includes generation warnings if any; fetch/parse errors per spec's Errors section.

- [ ] Implement; `./gradlew build` green; existing tests pass; commit `feat: Generate Data — new file from schema (local/URL) and add synthetic rows to open file`.

### Task 4: End-to-end test + Release 1.8.0
- [ ] Add to `SyntheticDataGeneratorTest` (or a new `SyntheticDataEndToEndTest`): generate 50 rows for columnNames/types taken from an existing fixture via `DuckDBParquetService.loadParquet`, save with the existing save path to a temp file, reload → 50 rows, schema intact.
- [ ] `pluginVersion=1.8.0`; `changeNotes=New: synthetic data generator — create Parquet files from a schema (local file or URL with token, e.g. Artifactory) or append realistic test rows to the open file. Deterministic with a seed; fully offline.`; CHANGELOG `## [1.8.0]`.
- [ ] `./gradlew clean build verifyPlugin` green; commit `chore: release 1.8.0`.

## Verification matrix
Automated: 10+ new tests + full suite + verifyPlugin. Manual (maintainer): dialog flows, URL fetch against real Artifactory.
