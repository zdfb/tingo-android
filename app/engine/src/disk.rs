//! 磁盘余量探测。放在 trait 后面是因为文件系统的探测方式随平台而异：
//! 桌面与 Android 都走 `statvfs`（见 [`Statvfs`] / [`AndroidStatvfs`]），调用方只认 trait。

use std::path::Path;

/// 某个路径所在磁盘的剩余空间（普通用户可用的字节数）。路径不存在时看最近一层存在的上级目录。
pub trait DiskSpace {
    fn free_bytes(&self, path: &Path) -> Option<u64>;
}

/// 桌面实现：`statvfs`。libc 只在这里出现，Android 构建（`--no-default-features`）不含它。
#[cfg(any(feature = "desktop-shell", target_os = "android"))]
pub struct Statvfs;

#[cfg(any(feature = "desktop-shell", target_os = "android"))]
impl DiskSpace for Statvfs {
    fn free_bytes(&self, path: &Path) -> Option<u64> {
        statvfs_free_bytes(path)
    }
}

/// `statvfs` 平台无关的那一半：桌面与 Android 共用同一份实现。
#[cfg(any(feature = "desktop-shell", target_os = "android"))]
fn statvfs_free_bytes(path: &Path) -> Option<u64> {
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

/// Android 实现：同样是 `statvfs`，但不依赖 `desktop-shell` feature——
/// 引擎的 Android 形态（`--no-default-features`）也要能判断磁盘够不够。
#[cfg(target_os = "android")]
pub struct AndroidStatvfs;

#[cfg(target_os = "android")]
impl DiskSpace for AndroidStatvfs {
    fn free_bytes(&self, path: &Path) -> Option<u64> {
        statvfs_free_bytes(path)
    }
}

/// 固定返回值的替身：调用方注入它，测试不必碰真实磁盘。
#[cfg(test)]
pub(crate) struct FixedDiskSpace(pub Option<u64>);

#[cfg(test)]
impl DiskSpace for FixedDiskSpace {
    fn free_bytes(&self, _path: &Path) -> Option<u64> {
        self.0
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn callers_go_through_the_trait() {
        let p = Path::new("/nonexistent/tingo/fixture");
        assert_eq!(FixedDiskSpace(Some(1024)).free_bytes(p), Some(1024));
        assert_eq!(FixedDiskSpace(None).free_bytes(p), None);
    }

    #[cfg(feature = "desktop-shell")]
    #[test]
    fn statvfs_reports_free_space() {
        let dir = std::env::temp_dir();
        assert!(Statvfs.free_bytes(&dir).is_some_and(|n| n > 0));
        // 还没建的目录：看最近一层存在的上级（绝对路径总能走到 /）
        assert!(Statvfs
            .free_bytes(&dir.join("tingo-还没建/的目录"))
            .is_some());
        assert!(Statvfs
            .free_bytes(Path::new("/nonexistent/tingo/fixture"))
            .is_some());
        // 连存在的上级都找不到（相对路径，一层都不存在）
        assert_eq!(
            Statvfs.free_bytes(Path::new("tingo-不存在的目录/dir")),
            None
        );
    }
}
