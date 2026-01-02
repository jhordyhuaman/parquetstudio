# Changelog

All notable changes to Parquet Studio will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.4.1] - 2025-01-02

### Fixed
- **StackOverflowError on file open** - Fixed critical bug that caused the IDE to freeze when opening Parquet files due to recursive layout calculation in tabbed pane
- **Duplicate file loading** - Fixed issue where clicking on an already-open file would reload it instead of switching to the existing tab
- **Tab display** - Restored tab titles showing filename with close indicator (×)

### Improved
- **Loading feedback** - Added loading panel with progress indicator while files are being loaded
- **Welcome panel** - Shows helpful instructions when no files are open in Parquet Studio
- **File validation** - Added validation for large files (>500MB warning, >1GB blocked) with user confirmation
- **Error notifications** - Improved error messages with balloon notifications for better UX

### Technical
- Added `loadingFile` field to track files during loading process for better duplicate detection
- Deferred tab selection using `SwingUtilities.invokeLater()` to prevent layout recursion
- Added retry mechanism for opening Tool Window (3 attempts with 500ms delay)

[1.4.1]: https://github.com/jhordyhuaman/parquetstudio/releases/tag/v1.4.1

## [1.4.0] - 2025-12-02

### Added
- **Schema Panel** - View the detected Parquet schema as formatted JSON directly in the plugin
- **External Schema Load** - Load `.schema` / `.json` files to define target types and see `current -> target` mappings
- **Save with Schema** - Option to write Parquet files using the loaded schema; strict mode validates column count parity before saving
- **Type Normalization** - Automatic mapping for common Avro/Parquet type variants (e.g., `timestamp_millis` → `timestamp`, `int32` → `integer`)

### Improved
- Clear warnings and status when the external schema does not match the Parquet columns
- Updated documentation for schema workflows and developer guidance

[1.4.0]: https://github.com/jhordyhuaman/parquetstudio/releases/tag/v1.4.0
## [1.3.0] - 2024-12-19

### Added
- **Icon-based UI** - Replaced text buttons with intuitive icons for better UX
- **Automatic File Association** - Double-click `.parquet` files to open them automatically in Parquet Studio
- **File Type Recognition** - IntelliJ IDEA now recognizes `.parquet` files as Parquet Studio files

### Improved
- **Modern UI** - Cleaner interface with icon-only buttons and tooltips
- **Better Integration** - Seamless integration with IntelliJ IDEA's file system
- **Architecture Refactoring** - Reorganized code into 3-layer architecture (UI, Service, Model) for better maintainability and testability
  - Created `ParquetEditorService` to separate business logic from UI
  - Reorganized packages: `model/`, `service/`, `ui/`, `filetype/`, `factory/`
  - Improved code organization and separation of concerns

[1.3.0]: https://github.com/jhordyhuaman/parquetstudio/releases/tag/v1.3.0

## [1.2.0] - 2024-11-18

### Added
- **Multiple Tabs Support** - Edit multiple Parquet files simultaneously with tab-based navigation
- **Tab Management** - Open, close, and switch between multiple Parquet files
- **Duplicate File Detection** - Prevents opening the same file twice (switches to existing tab)
- **Right-click to Close** - Close tabs by right-clicking on the tab header
- **ParquetEditorPanel** - New reusable component for individual file editing

### Improved
- Refactored UI architecture for better code organization
- Each file now has its own independent editor state
- Better separation of concerns between tab management and file editing

[1.2.0]: https://github.com/jhordyhuaman/parquetstudio/releases/tag/v1.2.0

## [1.1.0] - 2024-11-18

### Added
- **Add Column** functionality - Add new columns to Parquet tables with custom name and type
- **Delete Column** functionality - Remove columns from Parquet tables with confirmation dialog
- Flexible TIMESTAMP parsing - Support for multiple timestamp formats:
  - ISO format: `2024-11-12T10:30:00`
  - Space-separated: `2024-11-12 10:30:00`
  - With milliseconds: `2022-07-11 15:53:24.671` or `2022-07-11T15:53:24.671`
  - With microseconds and nanoseconds

### Improved
- Enhanced DATE and TIMESTAMP column editing with proper cell editors
- Better error handling for invalid date/timestamp formats
- Improved user experience when working with date and time columns

[1.1.0]: https://github.com/jhordyhuaman/parquetstudio/releases/tag/v1.1.0

## [1.0.0] - 2024-11-12

### Added
- Initial release of Parquet Studio
- Open Parquet files using DuckDB
- View Parquet data in editable JTable
- Edit cells with automatic type validation
- Add new rows with type-appropriate defaults
- Delete selected rows
- Real-time search across all columns
- Save edited data to new Parquet files
- Support for INTEGER, DOUBLE, BOOLEAN, VARCHAR, DATE, TIMESTAMP types
- Status bar showing row count and file name
- Toolbar with all CRUD operations
- Comprehensive logging for debugging

### Technical
- Built on DuckDB JDBC 0.10.2
- IntelliJ Platform Plugin SDK
- Java 17 compatibility
- Swing-based UI with JBTable

[1.0.0]: https://github.com/jhordyhuaman/parquet-studio/releases/tag/v1.0.0
