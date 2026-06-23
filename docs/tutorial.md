# Tutorial: Your first Vortex file

This tutorial walks you through writing and reading a Vortex file from scratch.
You will end up with a working Maven project that stores time-series data in Vortex format
and reads it back column by column.

**Prerequisites:** Java 25+, Maven 3.9+.

---

## 1. Create a Maven project

```bash
mvn archetype:generate \
  -DgroupId=com.example \
  -DartifactId=vortex-demo \
  -DarchetypeArtifactId=maven-archetype-quickstart \
  -DarchetypeVersion=1.5 \
  -DinteractiveMode=false
cd vortex-demo
```

Add the dependency to `pom.xml` (inside `<dependencies>`):

```xml
<dependency>
  <groupId>io.github.dfa1.vortex</groupId>
  <artifactId>vortex-reader</artifactId>
  <version>0.8.3</version>
</dependency>
```

Set the compiler to Java 25:

```xml
<properties>
  <maven.compiler.release>25</maven.compiler.release>
</properties>
```

---

## 2. Define a schema

A Vortex file is a typed struct — every column has a declared type before any data is written.

```java
import io.github.dfa1.vortex.core.DType;

DType.Struct schema = DType.structBuilder()
    .field("timestamp", DType.i64())                   // unix epoch millis
    .field("symbol",    DType.utf8())                  // ticker symbol
    .field("price",     DType.f64())                   // trade price
    .field("volume",    DType.i64().asNullable())      // shares traded, may be null
    .build();
```

`DType.i64()`, `DType.utf8()`, `DType.f64()`, etc. return non-nullable types by default.
Chain `.asNullable()` to opt into nulls.
See [reference.md#core-types](reference.md#core-types) for the full factory list.

---

## 3. Write data

```java
import io.github.dfa1.vortex.writer.VortexWriter;
import io.github.dfa1.vortex.writer.WriteOptions;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import static java.nio.file.StandardOpenOption.*;

Path outPath = Path.of("trades.vortex");

try (FileChannel ch = FileChannel.open(outPath, CREATE, WRITE, TRUNCATE_EXISTING);
     VortexWriter writer = VortexWriter.create(ch, schema, WriteOptions.defaults())) {

    writer.writeChunk(c -> c
        .put("timestamp", new long[]   {1_700_000_000_000L, 1_700_000_001_000L, 1_700_000_002_000L})
        .put("symbol",    new String[] {"AAPL", "AAPL", "MSFT"})
        .put("price",     new double[] {189.95, 190.10, 374.20})
        .put("volume",    new Long[]   {100L, null, 175L}));   // boxed → nullable column
}
```

Each `.put` validates the column name and array type against the schema at the call
site — unknown columns, wrong array types, and boxed arrays for non-nullable columns
all throw `IllegalArgumentException` immediately. Missing columns surface as
`IllegalStateException` when the lambda returns.

`writeChunk` takes one batch of rows. Call it multiple times to write multiple chunks —
each chunk is compressed independently and can be skipped during a scan if zone-map
statistics rule it out.

The file is complete and readable as soon as `VortexWriter` is closed.

---

## 4. Read it back

```java
import io.github.dfa1.vortex.io.VortexReader;
import io.github.dfa1.vortex.scan.ScanOptions;
import io.github.dfa1.vortex.reader.array.DoubleArray;
import io.github.dfa1.vortex.reader.array.LongArray;

try (VortexReader vf = VortexReader.open(outPath);
     var iter = vf.scan(ScanOptions.all())) {

    while (iter.hasNext()) {
        var chunk = iter.next();   // advances to the next batch

        LongArray  ts     = chunk.column("timestamp");
        DoubleArray price = chunk.column("price");

        for (long i = 0; i < chunk.rowCount(); i++) {
            System.out.printf("%d  %.2f%n", ts.getLong(i), price.getDouble(i));
        }
        // ⚠ do not store references past this point —
        //   iter.hasNext() frees the chunk's memory
    }
}
```

**Important:** every chunk lives in an off-heap `Arena`. Calling `iter.hasNext()` closes
that arena and releases the memory. Read all values before advancing the iterator.
See [explanation.md#memory-model](explanation.md#memory-model) for why the lifetime works this way.

Expected output:

```
1700000000000  189.95
1700000001000  190.10
1700000002000  374.20
```

---

## 5. Project columns and limit rows

Reading every column is wasteful when you only need two. Use `withColumns` to project, and
`withLimit` to stop after `n` rows:

```java
ScanOptions opts = ScanOptions.all()
    .withColumns("symbol", "price")
    .withLimit(2);

try (VortexReader vf = VortexReader.open(outPath);
     var iter = vf.scan(opts)) {

    while (iter.hasNext()) {
        var chunk = iter.next();
        // chunk only contains "symbol" and "price"
    }
}
```

---

## What's next

You now have a working write-then-read flow. From here:

- [how-to.md](how-to.md) — task recipes: filter rows, project columns, convert Parquet, use the CLI
- [reference.md](reference.md) — API surface, CLI subcommands, operator tables
- [compatibility.md](compatibility.md) — which encodings are supported
- [explanation.md](explanation.md) — memory model, testing strategy, benchmarks
