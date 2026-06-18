# Architecture Decision Records

This directory contains ADRs following the
[MADR 3.0](https://adr.github.io/madr/) format (Markdown Architectural Decision Records).

## Format

Each ADR is a Markdown file named `NNNN-short-title.md`. Use `template.md` as the starting point.

**Status values:** Proposed → Accepted → Implemented → Completed | Deferred | Deprecated | Superseded

## Index

| ADR  | Title                                         | Status    |
|------|-----------------------------------------------|-----------|
| 0001 | Split read and write runtimes out of core     | Completed |
| 0002 | Pluggable DType, Layout, and Compute          | Deferred  |
| 0003 | VortexException message sanitization          | Accepted  |
| 0004 | ResourceLimits + ReadOptions                  | Accepted  |
| 0005 | Vector API adoption                           | Deferred  |
| 0006 | Benchmark publishing                          | Accepted  |
| 0007 | Pure-Java vortex.pco encoder                  | Implemented |
| 0008 | Domain primitives for unsigned integers       | Proposed  |
| 0009 | Write API ergonomics                          | Completed |
| 0010 | Lazy decode                                   | Implemented |
| 0011 | Writer zero-copy MemorySegment overload       | Deferred  |
| 0012 | Zero-copy layout decoding: lazy Chunked/Dict  | Implemented |
| 0013 | Compute primitives: masks, kernels, no-materialise | Proposed  |
| 0014 | Variant encoding: chunked constants now, parquet.variant later | Implemented |
| 0015 | Drop Materialized fallbacks once Lazy has shipped | Completed |
