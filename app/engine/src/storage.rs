//! Copy a meeting library without deleting or overwriting the original files.
use std::fs;
use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use serde::Serialize;
use sha2::{Digest, Sha256};

use crate::disk::DiskSpace;
use crate::i18n::tr;
use crate::paths::EnginePaths;

#[derive(Clone, Default, Serialize)]
pub struct Progress {
    pub active: bool,
    pub copied_bytes: u64,
    pub total_bytes: u64,
}

/// 解析设置页里输入的目标目录：桌面可写 `~/…`（由注入的 [`EnginePaths`] 展开），Android 只收绝对路径。
pub fn destination(source: &Path, input: &str, paths: &EnginePaths) -> Result<PathBuf, String> {
    let path = paths.expand(input);
    if !path.is_absolute() { return Err(tr("请选择绝对路径的文件夹", "Choose an absolute folder path").into()); }
    let path = path.canonicalize().map_err(|e| e.to_string())?;
    if !path.is_dir() { return Err(tr("请选择文件夹", "Choose a folder").into()); }
    let source = source.canonicalize().unwrap_or_else(|_| source.to_path_buf());
    if path == source { return Ok(path); }
    if path.starts_with(&source) || source.starts_with(&path) {
        return Err(tr("新旧目录不能互相包含，请选择另一个独立文件夹", "The folders must not contain each other").into());
    }
    let probe = path.join(format!(".tingo-write-check-{}", std::process::id()));
    let mut file = fs::OpenOptions::new().write(true).create_new(true).open(&probe).map_err(|e| e.to_string())?;
    let result = file.write_all(b"Tingo").and_then(|_| file.sync_all());
    drop(file);
    let _ = fs::remove_file(probe);
    result.map_err(|e| e.to_string())?;
    Ok(path)
}

/// 文件的 sha256（小写十六进制）：复制校验与安装包校验用。
pub fn sha256_file(path: &Path) -> std::io::Result<String> {
    let mut f = fs::File::open(path)?;
    let mut h = Sha256::new();
    let mut buf = vec![0u8; 1 << 16];
    loop {
        let n = f.read(&mut buf)?;
        if n == 0 {
            break;
        }
        h.update(&buf[..n]);
    }
    Ok(h.finalize().iter().map(|b| format!("{b:02x}")).collect())
}

fn inventory(dir: &Path, entries: &mut Vec<(PathBuf, bool, u64)>, root: &Path) -> Result<(), String> {
    for entry in fs::read_dir(dir).map_err(|e| e.to_string())? {
        let path = entry.map_err(|e| e.to_string())?.path();
        let meta = fs::symlink_metadata(&path).map_err(|e| e.to_string())?;
        if !meta.is_dir() && !meta.is_file() {
            return Err(format!("{}: {}", tr("目录中有链接或特殊文件，无法自动复制", "Cannot copy links or special files"), path.display()));
        }
        entries.push((path.strip_prefix(root).unwrap().to_path_buf(), meta.is_dir(), meta.len()));
        if meta.is_dir() { inventory(&path, entries, root)?; }
    }
    Ok(())
}

/// Stage and verify every file first. Errors never remove the source library.
/// 磁盘余量由调用方注入的 [`DiskSpace`] 判断：桌面是 `statvfs`，Android 是 Kotlin 的 `StatFs`。
pub fn copy_library(source: &Path, target: &Path, disk: &dyn DiskSpace, mut progress: impl FnMut(u64, u64)) -> Result<(), String> {
    if fs::read_dir(target).map_err(|e| e.to_string())?.next().is_some() {
        return Err(tr("复制会议需要一个空文件夹，避免覆盖已有文件", "Choose an empty folder to copy meetings without overwriting files").into());
    }
    let mut entries = Vec::new();
    inventory(source, &mut entries, source)?;
    let total = entries.iter().filter(|(_, d, _)| !d).map(|(_, _, n)| n).sum();
    if disk.free_bytes(target).is_some_and(|free| free < total) {
        return Err(tr("目标磁盘空间不足", "Not enough space on the destination disk").into());
    }
    progress(0, total);
    let stage = target.join(format!(".tingo-copy-{}", std::process::id()));
    fs::create_dir(&stage).map_err(|e| e.to_string())?;
    let result = (|| {
        let mut copied = 0;
        let mut buffer = vec![0u8; 1024 * 1024];
        for (relative, directory, _) in entries {
            let src = source.join(&relative);
            let dst = stage.join(&relative);
            if directory { fs::create_dir(&dst).map_err(|e| e.to_string())?; continue; }
            let mut input = fs::File::open(&src).map_err(|e| e.to_string())?;
            let mut output = fs::OpenOptions::new().write(true).create_new(true).open(&dst).map_err(|e| e.to_string())?;
            loop {
                let n = input.read(&mut buffer).map_err(|e| e.to_string())?;
                if n == 0 { break; }
                output.write_all(&buffer[..n]).map_err(|e| e.to_string())?;
                copied += n as u64;
                progress(copied, total);
            }
            // Keep history timestamps and the dates of imported meetings.
            output.set_times(fs::FileTimes::new().set_modified(input.metadata().map_err(|e| e.to_string())?.modified().map_err(|e| e.to_string())?)).map_err(|e| e.to_string())?;
            output.sync_all().map_err(|e| e.to_string())?;
            if sha256_file(&src).map_err(|e| e.to_string())? != sha256_file(&dst).map_err(|e| e.to_string())? {
                return Err(tr("复制校验失败，仍使用原目录", "Copy verification failed; the original folder is still in use").into());
            }
        }
        for entry in fs::read_dir(&stage).map_err(|e| e.to_string())? {
            let entry = entry.map_err(|e| e.to_string())?;
            let destination = target.join(entry.file_name());
            if destination.exists() { return Err(tr("目标目录出现同名文件，已停止切换", "A conflicting file appeared; switching stopped").into()); }
            fs::rename(entry.path(), destination).map_err(|e| e.to_string())?;
        }
        Ok(())
    })();
    let _ = fs::remove_dir_all(stage);
    result
}

#[cfg(test)]
mod tests {
    use super::*;

    fn temp(name: &str) -> PathBuf {
        let d = std::env::temp_dir().join(format!("tingo-copy-{}-{name}", std::process::id()));
        let _ = fs::remove_dir_all(&d);
        fs::create_dir_all(&d).unwrap();
        d
    }

    struct Fixed(Option<u64>);

    impl DiskSpace for Fixed {
        fn free_bytes(&self, _path: &Path) -> Option<u64> {
            self.0
        }
    }

    #[test]
    fn sha256_of_known_content() {
        let dir = temp("sha");
        let f = dir.join("abc.txt");
        fs::write(&f, "abc").unwrap();
        assert_eq!(sha256_file(&f).unwrap(), "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assert!(sha256_file(&dir.join("missing")).is_err());
        fs::remove_dir_all(dir).ok();
    }

    #[test]
    fn destination_expands_home_from_injected_paths() {
        let home = temp("home");
        fs::create_dir_all(home.join("会议/资料")).unwrap();
        let paths = EnginePaths::desktop_in(&home);
        let source = home.join("Documents/Tingo");
        fs::create_dir_all(&source).unwrap();
        // ~/… 用注入的家目录展开
        assert_eq!(destination(&source, " ~/会议/资料 ", &paths).unwrap(), home.join("会议/资料").canonicalize().unwrap());
        // Android 上 ~ 不展开，也就不是绝对路径
        let android = EnginePaths::android(&home);
        assert!(destination(&source, "~/会议/资料", &android).is_err());
        // 相对路径、互相包含都要拒绝
        assert!(destination(&source, "会议/资料", &paths).is_err());
        assert!(destination(&source, home.to_str().unwrap(), &paths).is_err());
        fs::remove_dir_all(home).ok();
    }

    #[test]
    fn copy_library_checks_injected_free_space() {
        let dir = temp("copy");
        let source = dir.join("old");
        fs::create_dir_all(source.join("2026-10-28_1430_周会")).unwrap();
        fs::write(source.join("2026-10-28_1430_周会/纪要.md"), vec![b'x'; 100]).unwrap();
        let target = dir.join("new");
        fs::create_dir_all(&target).unwrap();
        // 余量不足：直接拒绝，源目录一个字节都不动
        let err = copy_library(&source, &target, &Fixed(Some(1)), |_, _| {}).unwrap_err();
        assert!(err.contains("空间不足") || err.contains("Not enough space"));
        assert_eq!(fs::read_dir(&target).unwrap().count(), 0);
        fs::remove_dir_all(&target).unwrap();
        // 余量足够：复制成功，文件内容一致
        fs::create_dir_all(&target).unwrap();
        let mut progress = Vec::new();
        copy_library(&source, &target, &Fixed(Some(u64::MAX)), |copied, total| progress.push((copied, total))).unwrap();
        assert_eq!(progress.first(), Some(&(0, 100)));
        assert_eq!(progress.last(), Some(&(100, 100)));
        assert_eq!(fs::read(target.join("2026-10-28_1430_周会/纪要.md")).unwrap(), vec![b'x'; 100]);
        fs::remove_dir_all(dir).ok();
    }
}
