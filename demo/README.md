# Vortex HTTP range demo

Shows that a filtered, projected `VortexHttpReader` scan over HTTP fetches only the bytes it
actually touches — not the whole file — using nothing but plain HTTP `Range` requests. No cloud
account, no query service: just a `.vortex` file sitting behind a minimal object-storage server.

Three standalone tools:

| Module | Artifact | What it does |
|---|---|---|
| `fakedata-generator` | `vortex-fakedata-generator.jar` | Generates a synthetic `.vortex` file from a compact column-description grammar |
| `server` | `vortex-server.jar` | Minimal object-storage HTTP server — `GET`/`HEAD` with byte-range support, `PUT`, listing |
| `client` | `vortex-demo.jar` | Uploads a file, runs a filtered/projected scan, reports bytes fetched vs. the file's full size |

## Build once

```bash
./mvnw package -pl demo/server,demo/fakedata-generator,demo/client -am -DskipTests
```

Produces:

```
demo/server/target/vortex-server.jar
demo/fakedata-generator/target/vortex-fakedata-generator.jar
demo/client/target/vortex-demo.jar
```

## Run the live demo (two terminals)

**Terminal 1 — start the server** (serves whatever directory you point it at, on whatever port):

```bash
java -jar demo/server/target/vortex-server.jar 8080 /tmp/vortex-demo-data
```

Leave it running — it logs every request it serves, which is the point: you'll watch it print a
handful of small `Range` fetches instead of one big download.

**Terminal 2 — generate the dataset:**

```bash
java -jar demo/fakedata-generator/target/vortex-fakedata-generator.jar \
    --rows 2000000 --out /tmp/trades.vortex --sort-by symbol \
    "timestamp:i64:series(1700000000000,1000)" \
    "symbol:utf8:enum(SYM,30)" \
    "price:f64:range(50,150)" \
    "volume:i64:range(100,10000)"
```

Prints a live progress bar with ETA to stderr while writing (throttled, so it won't flood the
terminal — only really visible on larger row counts).

2,000,000 rows across 30 symbols, sorted by symbol — the sort is what makes zone-map pruning
dramatic for a single-symbol filter (each symbol ends up clustered into just one or two chunks).

**Terminal 2 — upload and scan it:**

```bash
java -jar demo/client/target/vortex-demo.jar \
    --file /tmp/trades.vortex --server http://127.0.0.1:8080/
```

Expected output (numbers will vary slightly with row count):

```
Uploading to http://127.0.0.1:8080/trades.vortex ...

Scanning for symbol=SYM015, projecting 'price' over HTTP...

Matched rows: 65536
Bytes fetched over HTTP during the scan: 513,912 / 24,550,442 (2.09% of the object)
```

Switch back to **Terminal 1** — you'll see the `PUT` (the upload) followed by a small number of
`GET ... range=bytes=...` lines, each a few hundred KB, not one 24 MB download.

## One-terminal version (no server to manage)

Omit `--server` and the client embeds its own:

```bash
java -jar demo/client/target/vortex-demo.jar --file /tmp/trades.vortex
```

Good for a quick local check; the two-terminal version is more compelling live, since the
audience can watch the server's request log update in real time.

## Customizing the story

- **Different filter/projection** — `vortex-demo` accepts `--filter-column`, `--filter-value`,
  and `--project` (defaults: `symbol`, `SYM015`, `price`). Match these to whatever schema you
  generate.
- **Different dataset shape** — `vortex-fakedata-generator`'s column grammar is
  `name:type:generator(args)`. Run it with no arguments for the full grammar reference
  (types, generators, an example). The `series(start,step)` generator is a direct nod to SQL's
  `generate_series`.
- **No pruning story without `--sort-by`** — if rows for a given filter value are scattered
  across every chunk instead of clustered, zone-map pruning has nothing to skip and the scan
  degrades toward "fetch most of the file." Sorting by the column you intend to filter on is
  what makes the demo's numbers dramatic.

## Known gap this demo surfaced

`WriteOptions`'s default `globalDict=true` silently defeats zone-map pruning for `Utf8` columns
(see [issue #378](https://github.com/dfa1/vortex-java/issues/378)) — `vortex-fakedata-generator`
writes with `globalDict=false` to work around it. If you generate a file some other way and don't
see any pruning, check that setting first.
