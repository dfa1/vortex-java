//! Writes `zstd_buffers.vortex`: two columns, each wrapped in `vortex.zstd_buffers` by Rust's own
//! `ZstdBuffers::compress`, under the opt-in `zstd2026.02.0` edition (edition enforcement stays on).
//!
//! - `ints`: nullable i32 (`vortex.primitive`, values buffer + validity child), every 7th row null.
//! - `strs`: nullable utf8 (`vortex.varbin`, bytes buffer + offsets/validity children), every 5th
//!   row null.
//!
//! Row `i`: `ints = i * 3 - 500`, `strs = "s{i % 13}"`. Usage: `cargo run --release -- <out>`.

use std::sync::Arc;

use vortex::VortexSessionDefault;
use vortex::array::IntoArray;
use vortex::array::arrays::PrimitiveArray;
use vortex::array::arrays::StructArray;
use vortex::array::arrays::VarBinArray;
use vortex::array::validity::Validity;
use vortex::dtype::DType;
use vortex::dtype::Nullability;
use vortex::editions::EditionSessionExt;
use vortex::encodings::zstd::ZstdBuffers;
use vortex::encodings::zstd::editions::ZSTD_2026_02;
use vortex::file::WriteOptionsSessionExt;
use vortex::layout::layouts::flat::writer::FlatLayoutStrategy;
use vortex::session::VortexSession;

const ROWS: i32 = 1_000;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let out = std::env::args().nth(1).ok_or("usage: zstd-buffers-fixture <out.vortex>")?;
    let session = VortexSession::default();
    session.enable_edition(ZSTD_2026_02)?;

    let ints = PrimitiveArray::from_option_iter((0..ROWS).map(|i| (i % 7 != 0).then_some(i * 3 - 500)));
    let strs = VarBinArray::from_iter(
        (0..ROWS).map(|i| (i % 5 != 0).then(|| format!("s{}", i % 13))),
        DType::Utf8(Nullability::Nullable),
    );
    let table = StructArray::try_new(
        ["ints", "strs"].into(),
        vec![
            ZstdBuffers::compress(&ints.into_array(), 3, &session)?.into_array(),
            ZstdBuffers::compress(&strs.into_array(), 3, &session)?.into_array(),
        ],
        ROWS as usize,
        Validity::NonNullable,
    )?;

    let mut file = tokio::fs::File::create(&out).await?;
    session
        .write_options()
        .with_strategy(Arc::new(FlatLayoutStrategy::default()))
        .write(&mut file, table.into_array().to_array_stream())
        .await?;
    Ok(())
}
