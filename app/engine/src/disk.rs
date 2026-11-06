//! 磁盘余量探测。放在 trait 后面是因为两个平台的系统调用完全不同：
//! 桌面用 `statvfs`（见 [`Statvfs`]），Android 由 Kotlin 用 `StatFs` 实现 [`DiskSpace`] 后经 FFI 注入。

use std::path::Path;

/// 某个路径所在磁盘的剩余空间（普通用户可用的字节数）。路径不存在时看最近一层存在的上级目录。
pub trait DiskSpace {
    fn free_bytes(&self, path: &Path) -> Option<u64>;
}

/// 桌面实现：`statvfs`。libc 只在这里出现，Android 构建（`--no-default-features`）不含它。
#[cfg(feature = "desktop-shell")]
pub struct Statvfs;

#[cfg(feature = "desktop-shell")]
impl DiskSpace for Statvfs {
    fn free_bytes(&self, path: &Path) -> Option<u64> {
        use std::os::unix::ffi::OsStrExt;
        let existing = path.ancestors().find(|p| p.exists())?;
        let c = std::ffi::CString::new(existing.as_os_str().as_bytes()).ok()?;
        let mut st: libc::statvfs = unsafe { std::mem::zeroed() };
        if unsafe { libc::statvfs(c.as_ptr(), &mut st) } != 0 {
            return None;
        }
        #[allow(clippy::unnecessary_cast)] // 各平台字段类型不一样
        Some(st.f_bavail as u64 * st.f_frsize as u64)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// 固定返回值的替身：调用方能注入，测试不必碰真实磁盘
    struct Fixed(Option<u64>);

    impl DiskSpace for Fixed {
        fn free_bytes(&self, _path: &Path) -> Option<u64> {
            self.0
        }
    }

    #[test]
    fn callers_go_through_the_trait() {
        let p = Path::new("/nonexistent/tingo/fixture");
        assert_eq!(Fixed(Some(1024)).free_bytes(p), Some(1024));
        assert_eq!(Fixed(None).free_bytes(p), None);
    }

    #[cfg(feature = "desktop-shell")]
    #[test]
    fn statvfs_reports_free_space() {
        let dir = std::env::temp_dir();
        assert!(Statvfs.free_bytes(&dir).is_some_and(|n| n > 0));
        // 还没建的目录：看最近一层存在的上级（绝对路径总能走到 /）
        assert!(Statvfs.free_bytes(&dir.join("tingo-还没建/的目录")).is_some());
        assert!(Statvfs.free_bytes(Path::new("/nonexistent/tingo/fixture")).is_some());
        // 连存在的上级都找不到（相对路径，一层都不存在）
        assert_eq!(Statvfs.free_bytes(Path::new("tingo-不存在的目录/dir")), None);
    }
}
