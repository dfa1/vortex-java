# How-to guides

Task-oriented recipes. Each section solves one concrete goal.

---

## Count rows

**API:**

```java
long total = 0;
try (VortexReader vf = VortexReader.open(Path.of("data.vortex"));
     var iter = vf.scan(ScanOptions.all())) {
    while (iter.hasNext()) {
        total += iter.next().rowCount();
    }
}
System.out.println(total);
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

Supported operators: `Eq`, `Neq`, `Lt`, `Lte`, `Gt`, `Gte`.

**CLI:**

```bash
java -jar cli/target/vortex.jar filter trades.vortex "volume >= 1000000"
```

Filter operators: `>`, `>=`, `<`, `<=`, `=`, `==`. Values parsed as integer, double, boolean, or string.

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

EncodingRegistry registry = EncodingRegistry.loadAll().allowUnknown();

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

---

## Add a custom encoding

Three touch-points required:

**1. Register the encoding ID** in `EncodingId.java`:

```java
MY_ENCODING("com.example.my_encoding"),
```

**2. Implement `Encoding`:**

```java
public final class MyEncoding implements Encoding {

    @Override
    public EncodingId id() { return EncodingId.MY_ENCODING; }

    @Override
    public EncodeResult encode(DType dtype, Object data) { return Encoder.encode(dtype, data); }

    @Override
    public Array decode(DecodeContext ctx) { return Decoder.decode(ctx); }

    private static final class Encoder { /* ... */ }
    private static final class Decoder { /* ... */ }
}
```

**3. Register via `ServiceLoader`** — add the fully-qualified class name to:

```
META-INF/services/io.github.dfa1.vortex.encoding.Encoding
```

The encoding is then picked up automatically by `EncodingRegistry.loadAll()`.
To register it only for a specific reader without touching the global registry:

```java
EncodingRegistry registry = EncodingRegistry.loadAll();
registry.register(new MyEncoding());
VortexReader vf = VortexReader.open(path, registry);
```
