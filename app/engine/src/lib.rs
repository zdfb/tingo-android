//! 纪要的核心：识别、说话人区分、会议存储、纪要生成。
//!
//! 模块按 feature 分成「可移植核心」和「桌面专属」两部分（见 `Cargo.toml`）：
//! 核心是纯逻辑与文件读写，任何平台都能编；桌面专属要起子进程、跑 axum 服务、链接 sherpa-onnx、
//! 调 statvfs。桌面构建全开，Android 用 `--no-default-features` 只取核心。

/// crate 版本，供 FFI 边界层与界面展示使用（避免两处各写一份）。
pub const VERSION: &str = env!("CARGO_PKG_VERSION");

#[cfg(feature = "desktop-shell")]
pub mod apple_speech;
#[cfg(feature = "sherpa-asr")]
pub mod asr;
#[cfg(feature = "sherpa-asr")]
pub mod audio;
#[cfg(feature = "desktop-shell")]
pub mod autostop;
#[cfg(feature = "sherpa-asr")]
pub mod chunked;
pub mod cluster;
pub mod config;
pub mod disk;
pub mod fishbone;
pub mod i18n;
pub mod meetings;
pub mod minutes;
pub mod minutes_history;
pub mod models;
#[cfg(feature = "desktop-shell")]
pub mod obsidian;
pub mod paths;
pub mod push;
#[cfg(feature = "desktop-shell")]
pub mod record;
#[cfg(feature = "http-server")]
pub mod server;
pub mod speakers;
pub mod storage;
pub mod summarize;
pub mod terms;
pub mod transcript;
#[cfg(feature = "desktop-shell")]
pub mod update;
