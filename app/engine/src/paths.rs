//! 引擎用到的目录，全部由调用方注入；库里不再自己猜 HOME。
//!
//! 桌面版由 [`EnginePaths::desktop`] 按老规矩填充默认值（`TINGO_*` 环境变量、
//! `~/.config/tingo`、配置里的 `[storage] root`、`~/Documents/Tingo`、jiyao 旧位置回退），
//! 所以桌面行为与改造前逐字一致；Android 侧由 Kotlin 传入沙箱目录（[`EnginePaths::android`]）。

use std::path::{Path, PathBuf};

use crate::config::{env_var, with_legacy};

/// 运行平台。只有少数平台约定不同：`~/` 展开这类便利只有桌面才有意义。
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Platform {
    Desktop,
    Android,
}

#[derive(Clone, Debug, PartialEq)]
pub struct EnginePaths {
    /// 会议库根目录：一场会议一个子文件夹
    pub meetings_root: PathBuf,
    /// 配置目录，`config.toml` 在这里
    pub config_dir: PathBuf,
    /// 可丢的缓存（模型、临时文件）
    pub cache_dir: PathBuf,
    platform: Platform,
    /// 家目录只用来展开用户输入的 `~/…`；Android 上是选择器给的绝对路径，没有这个概念
    home: Option<PathBuf>,
}

impl EnginePaths {
    /// 桌面默认值。
    pub fn desktop() -> Self {
        Self::desktop_in(&home_dir())
    }

    /// 指定家目录的桌面默认值。`desktop()` 用它，测试也用它（免得改进程级环境变量）。
    /// `TINGO_CONFIG` / `TINGO_ROOT` 与配置里的 `[storage] root` 照旧生效。
    pub fn desktop_in(home: &Path) -> Self {
        let config_file = config_file_in(home);
        let cfg = crate::config::load(&config_file);
        let meetings_root = match env_var("ROOT") {
            Some(p) => PathBuf::from(p),
            None if !cfg.storage_root.is_empty() => PathBuf::from(cfg.storage_root),
            None => documents_root(home),
        };
        let config_dir = match config_file.parent().filter(|p| !p.as_os_str().is_empty()) {
            Some(dir) => dir.to_path_buf(),
            // TINGO_CONFIG 只给了文件名（相对路径）时，目录仍是老位置
            None => home.join(".config/tingo"),
        };
        Self {
            meetings_root,
            config_dir,
            cache_dir: cache_dir_in(home),
            platform: Platform::Desktop,
            home: Some(home.to_path_buf()),
        }
    }

    /// Android 沙箱：`root` 是应用私有目录（`filesDir`），会议、配置、缓存各占一个子目录。
    pub fn android(root: &Path) -> Self {
        Self {
            meetings_root: root.join("meetings"),
            config_dir: root.join("config"),
            cache_dir: root.join("cache"),
            platform: Platform::Android,
            home: None,
        }
    }

    pub fn platform(&self) -> Platform {
        self.platform
    }

    /// 把用户输入的路径变成绝对路径：桌面上 `~/…` 展开成家目录，其它原样返回（交给调用方校验）。
    pub fn expand(&self, input: &str) -> PathBuf {
        let input = input.trim();
        match (self.platform, &self.home, input.strip_prefix("~/")) {
            (Platform::Desktop, Some(home), Some(tail)) => home.join(tail),
            _ => PathBuf::from(input),
        }
    }
}

/// 家目录；没有 HOME 时为空路径（和改造前的 `unwrap_or_default` 一致，相对路径照样能拼出来）。
pub(crate) fn home_dir() -> PathBuf {
    std::env::var_os("HOME").map(PathBuf::from).unwrap_or_default()
}

/// 配置文件：`TINGO_CONFIG` 优先；改名前（jiyao）的位置存在而新位置不存在时沿用旧位置。
pub(crate) fn config_file_in(home: &Path) -> PathBuf {
    if let Some(p) = env_var("CONFIG") {
        return PathBuf::from(p);
    }
    with_legacy(
        home.join(".config/tingo/config.toml"),
        home.join(".config/jiyao/config.toml"),
    )
}

fn documents_root(home: &Path) -> PathBuf {
    let docs = home.join("Documents");
    with_legacy(docs.join("Tingo"), docs.join("jiyao"))
}

fn cache_dir_in(home: &Path) -> PathBuf {
    // macOS 的缓存约定；其它桌面平台没有既定位置，用 XDG 的 ~/.cache
    if cfg!(target_os = "macos") {
        home.join("Library/Caches/tingo")
    } else {
        home.join(".cache/tingo")
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn home(name: &str) -> PathBuf {
        let d = std::env::temp_dir().join(format!("tingo-paths-{}-{name}", std::process::id()));
        let _ = std::fs::remove_dir_all(&d);
        std::fs::create_dir_all(&d).unwrap();
        d
    }

    /// 桌面默认值与改造前的推导一致：`~/Documents/Tingo` 与 `~/.config/tingo`
    #[test]
    fn desktop_matches_old_derivation() {
        let h = home("desktop");
        let p = EnginePaths::desktop_in(&h);
        assert_eq!(p.meetings_root, h.join("Documents/Tingo"));
        assert_eq!(p.config_dir, h.join(".config/tingo"));
        let cache_dir = if cfg!(target_os = "macos") {
            h.join("Library/Caches/tingo")
        } else {
            h.join(".cache/tingo")
        };
        assert_eq!(p.cache_dir, cache_dir);
        assert_eq!(p.platform(), Platform::Desktop);
        // config.rs 记的配置文件位置与这里一致
        assert_eq!(config_file_in(&h), p.config_dir.join("config.toml"));
        assert_eq!(p.expand(" ~/会议/资料 "), h.join("会议/资料"));
        assert_eq!(p.expand("/Volumes/会议资料"), PathBuf::from("/Volumes/会议资料"));
        std::fs::remove_dir_all(h).ok();
    }

    /// 老用户还在 jiyao 的位置时沿用旧位置（配置文件与会议库各自判断存在与否）
    #[test]
    fn legacy_jiyao_dirs_are_kept() {
        let h = home("legacy");
        std::fs::create_dir_all(h.join(".config/jiyao")).unwrap();
        std::fs::create_dir_all(h.join("Documents/jiyao")).unwrap();
        std::fs::write(h.join(".config/jiyao/config.toml"), "").unwrap();
        let p = EnginePaths::desktop_in(&h);
        assert_eq!(p.config_dir, h.join(".config/jiyao"));
        assert_eq!(p.meetings_root, h.join("Documents/jiyao"));
        // 新位置一旦存在就回新位置
        std::fs::create_dir_all(h.join(".config/tingo")).unwrap();
        std::fs::create_dir_all(h.join("Documents/Tingo")).unwrap();
        std::fs::write(h.join(".config/tingo/config.toml"), "").unwrap();
        let p = EnginePaths::desktop_in(&h);
        assert_eq!(p.config_dir, h.join(".config/tingo"));
        assert_eq!(p.meetings_root, h.join("Documents/Tingo"));
        std::fs::remove_dir_all(h).ok();
    }

    /// 配置里的 `[storage] root` 决定会议库位置（桌面设置页改过目录的用法）
    #[test]
    fn storage_root_from_config_wins() {
        let h = home("storage-root");
        std::fs::create_dir_all(h.join(".config/tingo")).unwrap();
        let custom = h.join("我的会议");
        std::fs::write(
            h.join(".config/tingo/config.toml"),
            format!("[storage]\nroot = \"{}\"\n", custom.display()),
        )
        .unwrap();
        assert_eq!(EnginePaths::desktop_in(&h).meetings_root, custom);
        std::fs::remove_dir_all(h).ok();
    }

    /// Android 没有家目录：目录都在调用方给的沙箱根下，`~/` 不展开
    #[test]
    fn android_uses_injected_root() {
        let root = Path::new("/data/user/0/dev.tingo/files");
        let p = EnginePaths::android(root);
        assert_eq!(p.platform(), Platform::Android);
        assert_eq!(p.meetings_root, root.join("meetings"));
        assert_eq!(p.config_dir, root.join("config"));
        assert_eq!(p.cache_dir, root.join("cache"));
        assert_eq!(p.expand("~/会议"), PathBuf::from("~/会议"));
    }
}
