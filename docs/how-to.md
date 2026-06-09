# How-to guides

Task-oriented recipes. Each section solves one concrete goal.
For API details (classes, methods, operator tables), see [reference.md](reference.md).
For the design rationale behind the iterator lifecycle, see [explanation.md#memory-model](explanation.md#memory-model).

---

## Build the CLI

Build the fat jar once; reuse it for every CLI recipe below:

```bash
./mvnw package -pl cli -am -DskipTests
java -jar cli/target/vortex.jar <subcommand> [args]
```

For the full subcommand list, see [reference.md#cli](reference.md#cli).

---

## Count rows

**API:**

```java
var total = new java.util.concurrent.atomic.AtomicLong();
try (VortexReader vf = VortexReader.open(Path.of("data.vortex"));
     var iter = vf.scan(ScanOptions.all())) {
    iter.forEachRemaining(c -> total.addAndGet(c.rowCount()));
}
System.out.println(total.get());
```

**CLI:**

```bash
java -jar cli/target/vortex.jar count data.vortex
```

---

## Inspect file structure

**API:**

```java
try (VortexReader vf = VortexReader.open(Path.of("data.vortex"))) {
    System.out.println(vf.dtype());   // column names and types
    System.out.println(vf.layout());  // layout tree (Struct → Chunked → Flat …)
}
```

**CLI:**

```bash
# column names and types
java -jar cli/target/vortex.jar schema data.vortex

# full layout tree with encoding IDs, row counts, buffer sizes
java -jar cli/target/vortex.jar inspect data.vortex

# per-column min/max statistics
java -jar cli/target/vortex.jar stats data.vortex
```

---

## Inspect interactively (TUI)

For files where the static `inspect` output is too dense, the `tui` subcommand opens
an interactive terminal browser. The layout tree is loaded lazily — per-array
statistics, dictionary entries, hex previews, and decoded data are fetched
on demand as you navigate.

```bash
# local file
java -jar cli/target/vortex.jar tui data.vortex

# remote file (HTTP range requests)
java -jar cli/target/vortex.jar tui https://example.com/data.vortex
```

A loading bar prints to stderr while metadata is read, then the screen splits
into a tree pane on the left and a details pane on the right:

```
 data.vortex                                                                    
 v struct  (3000000 rows)                                                       
     v timestamp: vortex.zoned  (3000000 rows, stats)              | encoding: vortex.zoned
         > vortex.chunked  (3000000 rows)                          | rows:     3000000
     > symbol: vortex.dict  (3000000 rows)                         | min:      1700000000000
     > price: vortex.alp  (3000000 rows, stats)                    | max:      1700002999000
       volume: fastlanes.bitpacked  (3000000 rows)                 |
                                                                   | bit width: 21
                                                                   | offsets:   8 segments
                                                                   |
                                                                   | preview (hex):
                                                                   |   0x00f0c2e9b3 8c01...
 ↑↓ nav   →/Enter expand   ← collapse   q quit                                  
```

**Keymap:**

| Key                 | Action                                  |
|---------------------|-----------------------------------------|
| `↑` / `↓`           | Move selection one row                  |
| `PgUp` / `PgDn`     | Jump 10 rows                            |
| `Home` / `End`      | Jump to first / last visible row        |
| `→`                 | Expand node                             |
| `←`                 | Collapse node                           |
| `Enter`             | Toggle expand / collapse                |
| `q` / `Q` / `Esc`   | Quit                                    |

**Tree markers:**

| Marker | Meaning                                |
|--------|----------------------------------------|
| `>`    | Collapsed (has children)               |
| `v`    | Expanded                               |
| (none) | Leaf node                              |

The `, stats` suffix on a row indicates the node carries zone-map statistics
(min / max per chunk) — selecting it shows the values in the details pane.
`vortex.dict` nodes show their dictionary entries; flat numeric leaves show
a hex preview of the encoded buffer plus decoded data.

Set `VORTEX_DEBUG=1` to print full stack traces on error.

---

## Project columns

**API:**

```java
ScanOptions opts = ScanOptions.all().withColumns("symbol", "price");

try (VortexReader vf = VortexReader.open(Path.of("trades.vortex"));
     var iter = vf.scan(opts)) {
    while (iter.hasNext()) {
        var chunk = iter.next();
        // chunk.columns() contains only "symbol" and "price"
    }
}
```

**CLI:**

```bash
java -jar cli/target/vortex.jar select trades.vortex symbol price
```

---

## Filter rows

**API:**

```java
RowFilter filter = new RowFilter.Gte("volume", 1_000_000);
ScanOptions opts = ScanOptions.all().withFilter(filter);

try (VortexReader vf = VortexReader.open(Path.of("trades.vortex"));
     var iter = vf.scan(opts)) {
    while (iter.hasNext()) {
        var chunk = iter.next();
        // only rows where volume >= 1_000_000
    }
}
```

Combine filters with `and()`:

```java
RowFilter filter = new RowFilter.Gte("volume", 1_000_000)
    .and(new RowFilter.Lte("price", 200.0));
```

For the supported predicate set and CLI operator syntax, see
[reference.md#rowfilter](reference.md#rowfilter-iogithubdfa1vortexscanrowfilter)
and [reference.md#filter-expression-syntax](reference.md#filter-expression-syntax).

**CLI:**

```bash
java -jar cli/target/vortex.jar filter trades.vortex "volume >= 1000000"
```

---

## Preview the first N rows

**API:**

```java
ScanOptions opts = ScanOptions.all().withLimit(10);

try (VortexReader vf = VortexReader.open(Path.of("data.vortex"));
     var iter = vf.scan(opts)) {
    while (iter.hasNext()) {
        var chunk = iter.next();
        // at most 10 rows total across all chunks
    }
}
```

**CLI:**

```bash
# export first 10 rows to CSV
java -jar cli/target/vortex.jar export data.vortex | head -n 11   # 1 header + 10 rows
```

---

## Convert Parquet to Vortex

**API:**

```java
import io.github.dfa1.vortex.parquet.ParquetImporter;

ParquetImporter.importParquet(
    Path.of("data.parquet"),
    Path.of("data.vortex")
);
```

Project specific columns during conversion:

```java
import io.github.dfa1.vortex.parquet.ImportOptions;

ImportOptions opts = ImportOptions.defaults()
    .withColumns(List.of("trip_distance", "fare_amount"));

ParquetImporter.importParquet(Path.of("data.parquet"), Path.of("data.vortex"), opts);
```

**CLI:**

```bash
# output defaults to <input>.vortex
java -jar cli/target/vortex.jar import data.parquet

# explicit output path
java -jar cli/target/vortex.jar import data.parquet out.vortex
```

---

## Convert CSV to Vortex

**CLI only** (CSV has no schema — types are inferred):

```bash
java -jar cli/target/vortex.jar import data.csv
# writes data.vortex, prints size savings
```

---

## Export to CSV

**CLI:**

```bash
# all columns
java -jar cli/target/vortex.jar export data.vortex > out.csv

# specific columns
java -jar cli/target/vortex.jar select data.vortex col1 col2 > out.csv

# filtered rows
java -jar cli/target/vortex.jar filter data.vortex "price >= 100" > out.csv
```

---

## Read files with unknown encodings

By default, a file containing an unrecognised encoding ID throws `VortexException`.
Use `allowUnknown()` to read the file anyway — columns with unknown encodings are
returned as `UnknownArray` (opaque, not decodable, but the rest of the file is readable):

```java
import io.github.dfa1.vortex.encoding.EncodingRegistry;
import io.github.dfa1.vortex.core.array.UnknownArray;

EncodingRegistry registry = EncodingRegistry.builder()
        .registerServiceLoaded()
        .allowUnknown()
        .build();

try (VortexReader vf = VortexReader.open(Path.of("future.vortex"), registry);
     var iter = vf.scan(ScanOptions.all())) {
    while (iter.hasNext()) {
        var chunk = iter.next();
        chunk.columns().forEach((name, arr) -> {
            if (arr instanceof UnknownArray u) {
                System.out.println(name + ": unknown encoding " + u.encodingId());
            }
        });
    }
}
```
