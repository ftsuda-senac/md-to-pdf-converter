# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**md-to-pdf** is a Spring Boot CLI utility that converts Markdown files to PDF with support for protected blocks (content rendered as non-selectable images). Built for educational use at SENAC (TADS program).

Key characteristics:
- **No database**: Stateless CLI tool using Spring Boot's `CommandLineRunner`
- **Batch processing**: Converts all `.md` files in an input directory to `.pdf` in an output directory
- **Protected blocks**: Markdown content wrapped in `:::protected ... :::` is rendered as PNG images embedded in the PDF, making it non-copyable
- **GitHub-style rendering**: Uses Flexmark with GFM extensions and custom CSS based on GitHub's markdown styling

## Stack

- **Java 21** with Spring Boot 3.5.14 (core, no web server)
- **Flexmark 0.64.8**: Markdown → HTML parsing with GitHub Flavored Markdown support
- **OpenHTMLtoPDF 1.0.10**: HTML → PDF rendering (built on PDFBox)
- **Jsoup 1.18.3**: HTML5 to XHTML conversion for OpenHTMLtoPDF compatibility

## Architecture

The conversion pipeline flows through four main services:

### ConversionRunner (`runner/ConversionRunner.java`)
- Entry point implementing `CommandLineRunner`
- Discovers all `.md` files in input directory (non-recursive)
- Orchestrates sequential conversion of each file
- Currently hardcoded to `E:\senac\tutorial-dados-pessoais` (line 52) — should be restored to use `properties.inputDir()` for production
- Logs summary: success count, failure count, and detailed error messages

### MarkdownService (`service/MarkdownService.java`)
- **Pre-processes** markdown: Detects `:::protected ... :::` blocks using regex pattern (DOTALL + MULTILINE)
- Delegates protected block rendering to `ProtectedBlockService`, receives base64 PNG strings
- Replaces blocks with `<img src="data:image/png;base64,...">` tags (placed on isolated lines so Flexmark treats them as block-level)
- **Parses** remaining markdown with Flexmark using extensions: Tables, Strikethrough, Autolink, TaskList
- **Renders** to HTML fragment, wraps in full page with inline GitHub CSS
- Soft breaks are converted to `<br />` to match GitHub behavior

### ProtectedBlockService (`service/ProtectedBlockService.java`)
- Renders markdown blocks as non-selectable images via rasterization
- Pipeline:
  1. Parse block markdown to HTML (Flexmark, no extensions)
  2. Wrap in minimal page with `PROTECTED_CSS` (yellow background, orange border)
  3. Render to in-memory PDF (via `PdfService`)
  4. Rasterize all PDF pages (PDFBox at `imageDpi` DPI)
  5. Stack multiple pages vertically if needed
  6. Crop trailing whitespace (preserving 16px margin)
  7. Encode to base64 PNG
- `PROTECTED_CSS` constant defines styling (separate from main `github.css`)
- Image DPI controlled by `mdpdf.image-dpi` property (default 144, use 192 for high-quality printing)

### PdfService (`service/PdfService.java`)
- Two modes:
  - `renderToBytes(html)`: Returns PDF as byte array (used for protected block rasterization)
  - `renderToFile(html, path)`: Writes PDF directly to disk
- Converts HTML5 to XHTML via Jsoup (OpenHTMLtoPDF requires strict XML parsing)
- Uses OpenHTMLtoPDF's fast mode (`useFastMode()`)
- Throws `PdfRenderException` on failures

### MdToPdfProperties (`config/MdToPdfProperties.java`)
- Record holding three properties with defaults:
  - `inputDir`: `.md` directory (env: `MDPDF_INPUT_DIR`, default: `./markdowns`)
  - `outputDir`: PDF directory (env: `MDPDF_OUTPUT_DIR`, default: `./pdfs`)
  - `imageDpi`: DPI for rasterizing blocks (env: `MDPDF_IMAGE_DPI`, default: `144`)
- Bound via `@ConfigurationProperties(prefix = "mdpdf")` from `application.yml`

## Build & Run

### Maven Build
```bash
# Compile and run in one step
./mvnw spring-boot:run

# Or build JAR then run
./mvnw clean package -DskipTests
java \
  --add-opens=java.base/java.lang=ALL-UNNAMED \
  --add-opens=java.base/java.util=ALL-UNNAMED \
  --add-opens=java.desktop/sun.awt=ALL-UNNAMED \
  --add-opens=java.desktop/sun.font=ALL-UNNAMED \
  -jar target/md-to-pdf-1.0.0.jar
```

The JVM flags are **required** — OpenHTMLtoPDF and PDFBox access internal JDK APIs.

### Configuration
Set environment variables to override defaults:
```bash
export MDPDF_INPUT_DIR=/path/to/markdowns
export MDPDF_OUTPUT_DIR=/path/to/pdfs
export MDPDF_IMAGE_DPI=192
./mvnw spring-boot:run
```

Or edit `src/main/resources/application.yml` directly.

## Protected Block Syntax

Valid block (delimiters on own lines):
```markdown
Some normal text.

:::protected
## Answer Key

The answer is **B** because...

| Point     | Value |
|-----------|-------|
| Correct   | 1.0   |
:::

More normal text.
```

Invalid patterns:
- `:::protected` mid-line (must be alone on line)
- `:::` followed by text on same line
- Nested `:::protected` blocks

The rendered block appears with yellow background (`#fffde7`) and orange left border in the PDF. Content cannot be selected or copied by PDF readers.

## Key Implementation Details

### HTML Processing
- Flexmark generates HTML5; Jsoup converts to valid XHTML/XML before OpenHTMLtoPDF processes it
- Protected blocks are replaced **before** parsing, so their `<img>` tags are treated as raw HTML blocks (not escaped or wrapped in `<p>`)
- Soft breaks (`\n`) become `<br />` to match GitHub

### CSS & Styling
- `src/main/resources/github.css`: Main stylesheet (CSS 2.1, no flexbox/grid/variables). Controls page size (A4), margins (20mm top/bottom, 25mm left/right), typography, and layout
- `PROTECTED_CSS` in `ProtectedBlockService`: Separate, minimal CSS for protected block rendering (ensures consistent appearance)
- Both stylesheets use CSS 2.1 only (required by OpenHTMLtoPDF)

### Headless Mode
- `MdToPdfApplication` sets `java.awt.headless = true` in a static initializer before Spring beans initialize
- Critical for rendering images (PDFBox's rasterization) on servers without display hardware

### Multi-page Blocks
- Protected blocks can span multiple PDF pages; `ProtectedBlockService.rasterizeAndStack()` stacks them vertically
- Last page is auto-cropped to remove trailing whitespace

## Known Limitations

- No syntax highlighting in code blocks (monospace font only; can be extended with highlight.js + CSS classes)
- GFM emoji shortcodes (`:rocket:`) not supported
- External images (`![](https://...)`) require workarounds (use local files with absolute paths or base64 data URIs)
- Nested protected blocks are not supported

## Debugging

- Logging is configured in `application.yml`: root level WARN, `br.edu.senac.mdpdf` at INFO
- Uncomment `com.openhtmltopdf: WARN` in `application.yml` for detailed PDF rendering logs
- Set log level to DEBUG for protected block processing details

## Fixed Issues to Watch

- **Line 52 in ConversionRunner**: Currently hardcoded to `E:\senac\tutorial-dados-pessoais`. This should use `properties.inputDir()` for proper operation with configuration properties. This appears to be a development override left in place.

## File Structure

```
src/main/java/br/edu/senac/mdpdf/
├── MdToPdfApplication.java         (Spring Boot entry point, sets headless mode)
├── config/
│   └── MdToPdfProperties.java      (Configuration record)
├── runner/
│   └── ConversionRunner.java       (Batch orchestrator)
└── service/
    ├── MarkdownService.java        (MD → HTML, protected block detection)
    ├── ProtectedBlockService.java  (Block → PNG base64)
    └── PdfService.java             (HTML → PDF, XHTML conversion)

src/main/resources/
├── application.yml                 (Spring Boot config, logging)
└── github.css                      (PDF styling)
```

No tests are currently included in the project.
