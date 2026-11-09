//! 会议文件夹的约定，和 Python 版 meetings.py 一致：一场会议一个文件夹。
//!
//! <根目录>/2026-11-02_1430_周会/{录音.m4a, 转写.md, transcript.json, 纪要.md}

use chrono::{Local, NaiveDate, NaiveDateTime};
use regex::Regex;
use serde::Serialize;
use serde_json::Value;
use std::path::{Path, PathBuf};
use std::sync::LazyLock;

use crate::minutes::SectionKind;
use crate::transcript::{fmt_ts, Transcript};

pub const AUDIO: &str = "录音.m4a";

/// 导出文件名的主干：纪要标题优先，其次会议主题（`日期_时间_主题` 里的主题部分）。
///
/// 桌面写 SRT/Word 与 Android 导出 SRT 都用它——各写一份必然漂移。
pub fn export_stem(dir: &Path, id: &str) -> String {
    let minutes = std::fs::read_to_string(dir.join(MINUTES)).unwrap_or_default();
    let topic = id.splitn(3, '_').nth(2).unwrap_or(id);
    let name = safe_name(&minutes_title(&minutes).unwrap_or_else(|| topic.to_string()));
    if name.is_empty() {
        safe_name(topic)
    } else {
        name
    }
}

/// 纪要写盘时的统一收尾：去掉尾部空白，末尾留一个换行。
///
/// 模型生成与手工编辑走同一个函数，否则"保存后 diff 多一行"这种事会反复出现。
pub fn minutes_with_trailing_newline(text: &str) -> String {
    format!("{}\n", text.trim_end())
}

/// 文件名里不能出现的字符（Obsidian 链接也不认 # ^ [ ] |）。
///
/// 导出 SRT / 笔记时用；放在这里而不是 obsidian 模块，是因为两个平台都要用。
pub fn safe_name(s: &str) -> String {
    let s: String = s
        .chars()
        .map(|c| {
            if "/\\:*?\"<>|#^[]".contains(c) || c.is_control() {
                ' '
            } else {
                c
            }
        })
        .collect();
    s.split_whitespace().collect::<Vec<_>>().join(" ")
}
pub const MINUTES: &str = "纪要.md";
pub const TRANSCRIPT_MD: &str = "转写.md";
pub const TRANSCRIPT_JSON: &str = "transcript.json";
/// 用户改的标题、标签、归档（没有这个文件 = 全部默认）
pub const META: &str = "meta.json";
pub const MAX_TAGS: usize = 10;
pub const MAX_TAG_CHARS: usize = 20;
const RAW_TRACKS: [&str; 3] = ["system.wav", "mic.wav", "mixed.wav"];
pub const AUDIO_EXTS: [&str; 10] = [
    "m4a", "mp3", "wav", "mp4", "aac", "flac", "ogg", "webm", "mov", "mkv",
];

static FOLDER: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"^(\d{4}-\d{2}-\d{2})_(\d{2})(\d{2})_(.+)$").unwrap());

/// 桌面版的会议库根目录。其它平台（Android）用注入的 [`crate::paths::EnginePaths::meetings_root`]。
pub fn default_root() -> PathBuf {
    crate::paths::EnginePaths::desktop().meetings_root
}

/// 会议文件夹名里的标题部分：去掉文件名里不能用的字符和空白
pub fn folder_title(title: &str) -> String {
    static UNSAFE: LazyLock<Regex> = LazyLock::new(|| Regex::new(r#"[\\/:*?"<>|\s]+"#).unwrap());
    let safe = UNSAFE.replace_all(title.trim(), "_");
    if safe.is_empty() {
        "会议".into()
    } else {
        safe.into_owned()
    }
}

/// 毫秒时间戳 → 本地时间；<=0 表示"不知道，用现在"。
///
/// 导入录音时用文件修改时间决定会议时间，桌面与 Android 都要这一步。
pub fn local_time_from_millis(ms: i64) -> chrono::NaiveDateTime {
    if ms <= 0 {
        return chrono::Local::now().naive_local();
    }
    chrono::DateTime::from_timestamp_millis(ms)
        .map(|d| d.with_timezone(&chrono::Local).naive_local())
        .unwrap_or_else(|| chrono::Local::now().naive_local())
}

/// 示例会议的文件夹名：素材是 2026-10-28 14:00 的会（桌面与 Android 同一套命名）。
pub fn demo_folder(en: bool) -> String {
    let title = if en {
        "Sample · Signup conversion review"
    } else {
        "示例·注册转化复盘"
    };
    format!("2026-10-28_1400_{}", folder_title(title))
}

/// 把示例素材安装进会议库；已经有了就直接返回那个目录（幂等）。
///
/// `source` 里必须有 `transcript.json` 与纪要（`纪要.md` 或英文版），音频可选。
/// 桌面从 App 资源里取素材，Android 从 assets 里解出来——两边都要同一套"装进库"的逻辑。
pub fn install_demo(root: &Path, source: &Path, en: bool) -> std::io::Result<PathBuf> {
    // 文件夹名固定（带示例的日期）：装过一次就不再重复装
    let dir = root.join(demo_folder(en));
    if dir.is_dir() {
        return Ok(dir);
    }
    let minutes = if en {
        source.join("纪要.en.md")
    } else {
        source.join(MINUTES)
    };
    if !source.join(TRANSCRIPT_JSON).is_file() || !minutes.is_file() {
        return Err(std::io::Error::new(
            std::io::ErrorKind::NotFound,
            "示例会议的素材不全",
        ));
    }
    let install = || -> std::io::Result<()> {
        std::fs::create_dir_all(&dir)?;
        std::fs::copy(source.join(TRANSCRIPT_JSON), dir.join(TRANSCRIPT_JSON))?;
        std::fs::copy(&minutes, dir.join(MINUTES))?;
        // 音频可选：没有音频的示例也能看纪要，只是不能回放
        if let Ok(entries) = std::fs::read_dir(source) {
            for entry in entries.flatten() {
                let path = entry.path();
                let ext = path
                    .extension()
                    .and_then(|e| e.to_str())
                    .unwrap_or_default()
                    .to_lowercase();
                if AUDIO_EXTS.contains(&ext.as_str()) {
                    let target = dir.join(path.file_name().unwrap());
                    std::fs::copy(&path, target)?;
                }
            }
        }
        Ok(())
    };
    if let Err(e) = install() {
        // 半装出来的示例会议比没有更糟：它会出现在列表里但打不开
        let _ = std::fs::remove_dir_all(&dir);
        return Err(e);
    }
    Ok(dir)
}

pub fn new_folder(root: &Path, title: &str, when: NaiveDateTime) -> PathBuf {
    let safe = folder_title(title);
    let base = format!("{}_{safe}", when.format("%Y-%m-%d_%H%M"));
    let mut path = root.join(&base);
    let mut n = 2;
    while path.exists() {
        path = root.join(format!("{base}_{n}"));
        n += 1;
    }
    path
}

pub fn save_transcript(tr: &Transcript, dir: &Path) -> std::io::Result<()> {
    tr.save(&dir.join(TRANSCRIPT_JSON))?;
    std::fs::write(dir.join(TRANSCRIPT_MD), tr.to_markdown())
}

pub fn find_audio(dir: &Path) -> Option<PathBuf> {
    let main = dir.join(AUDIO);
    if main.is_file() {
        return Some(main);
    }
    let mut files: Vec<PathBuf> = std::fs::read_dir(dir)
        .ok()?
        .flatten()
        .map(|e| e.path())
        .collect();
    files.sort();
    files.into_iter().find(|p| {
        let name = p.file_name().and_then(|n| n.to_str()).unwrap_or("");
        let ext = p
            .extension()
            .and_then(|e| e.to_str())
            .unwrap_or("")
            .to_lowercase();
        AUDIO_EXTS.contains(&ext.as_str()) && !RAW_TRACKS.contains(&name)
    })
}

fn modified_date(p: &Path) -> Option<NaiveDate> {
    let t = std::fs::metadata(p).ok()?.modified().ok()?;
    Some(chrono::DateTime::<Local>::from(t).date_naive())
}

/// 会议日期：文件夹名里的优先，否则用录音或转写文件的修改时间。
pub fn meeting_date(dir: &Path) -> NaiveDate {
    let name = dir.file_name().and_then(|n| n.to_str()).unwrap_or("");
    if let Some(c) = FOLDER.captures(name) {
        if let Ok(d) = NaiveDate::parse_from_str(&c[1], "%Y-%m-%d") {
            return d;
        }
    }
    find_audio(dir)
        .and_then(|a| modified_date(&a))
        .or_else(|| modified_date(&dir.join(TRANSCRIPT_JSON)))
        .unwrap_or_else(|| Local::now().date_naive())
}

pub fn minutes_title(text: &str) -> Option<String> {
    text.lines()
        .find_map(|l| l.strip_prefix("# ").map(|t| t.trim().to_string()))
}

/// 纪要里"一句话总结"（英文纪要是 Summary）下面的那句。
///
/// 标题词表由 [`crate::minutes::SectionKind`] 单一持有：解析与取摘要如果各写一份，
/// 以后加一种语言就会出现"列表有总结、详情没总结"。
pub fn one_liner(text: &str) -> String {
    let mut lines = text.lines();
    while let Some(l) = lines.next() {
        let is_one_liner = l
            .trim()
            .strip_prefix("## ")
            .is_some_and(|h| SectionKind::from_heading(h) == SectionKind::OneLiner);
        if is_one_liner {
            return lines
                .find(|n| !n.trim().is_empty())
                .map(|n| n.trim().to_string())
                .unwrap_or_default();
        }
    }
    String::new()
}

/// 会议元数据 meta.json。写入时只写 title（为空时不写）、tags、archived 三个字段。
#[derive(Clone, Debug, Default, PartialEq, Serialize)]
pub struct Meta {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub title: Option<String>,
    pub tags: Vec<String>,
    pub archived: bool,
}

/// 标题：去首尾空白，空的当没有
pub fn normalize_title(t: Option<&str>) -> Option<String> {
    t.map(str::trim).filter(|t| !t.is_empty()).map(String::from)
}

/// 一个标签：去首尾空白，最长 20 个字符
pub fn normalize_tag(t: &str) -> String {
    t.trim()
        .chars()
        .take(MAX_TAG_CHARS)
        .collect::<String>()
        .trim_end()
        .to_string()
}

/// 标签：去首尾空白、空的丢掉、每个最长 20 个字符、去重（保持顺序）、最多 10 个
pub fn normalize_tags<S: AsRef<str>>(tags: &[S]) -> Vec<String> {
    let mut out: Vec<String> = Vec::new();
    for t in tags {
        let t = normalize_tag(t.as_ref());
        if !t.is_empty() && !out.contains(&t) {
            out.push(t);
        }
        if out.len() >= MAX_TAGS {
            break;
        }
    }
    out
}

/// 读 meta.json；没有、读不出或格式不对的字段都按默认值
pub fn read_meta(dir: &Path) -> Meta {
    let Some(v) = std::fs::read_to_string(dir.join(META))
        .ok()
        .and_then(|s| serde_json::from_str::<Value>(&s).ok())
    else {
        return Meta::default();
    };
    let tags: Vec<&str> = v["tags"]
        .as_array()
        .map(|a| a.iter().filter_map(Value::as_str).collect())
        .unwrap_or_default();
    Meta {
        title: normalize_title(v["title"].as_str()),
        tags: normalize_tags(&tags),
        archived: v["archived"].as_bool().unwrap_or(false),
    }
}

/// 写 meta.json：UTF-8、缩进 2、中文原样
pub fn write_meta(dir: &Path, meta: &Meta) -> std::io::Result<()> {
    let meta = Meta {
        title: normalize_title(meta.title.as_deref()),
        tags: normalize_tags(&meta.tags),
        archived: meta.archived,
    };
    let text = serde_json::to_string_pretty(&meta).map_err(std::io::Error::other)?;
    std::fs::write(dir.join(META), text)
}

/// 会议文件夹里的录音：录音.*（所有扩展名）、system.wav、mic.wav
pub fn audio_files(dir: &Path) -> Vec<PathBuf> {
    let mut out: Vec<PathBuf> = top_files(dir)
        .into_iter()
        .filter(|p| is_recording(p) || matches!(file_name(p), "system.wav" | "mic.wav"))
        .collect();
    out.sort();
    out
}

/// 残留文件：已经有 录音.* 时还留着的 system.wav / mic.wav（合成录音失败前的原始音轨），以及 *.part、*.tmp
pub fn leftover_files(dir: &Path) -> Vec<PathBuf> {
    let files = top_files(dir);
    let has_recording = files.iter().any(|p| is_recording(p));
    let mut out: Vec<PathBuf> = files
        .into_iter()
        .filter(|p| {
            let ext = p
                .extension()
                .and_then(|e| e.to_str())
                .unwrap_or("")
                .to_ascii_lowercase();
            (has_recording && matches!(file_name(p), "system.wav" | "mic.wav"))
                || ext == "part"
                || ext == "tmp"
        })
        .collect();
    out.sort();
    out
}

/// 有没有 录音.* 文件
pub fn has_recording(dir: &Path) -> bool {
    top_files(dir).iter().any(|p| is_recording(p))
}

fn file_name(p: &Path) -> &str {
    p.file_name().and_then(|n| n.to_str()).unwrap_or("")
}

fn is_recording(p: &Path) -> bool {
    file_name(p)
        .strip_prefix("录音.")
        .is_some_and(|ext| !ext.is_empty())
}

/// 文件夹里直接放着的文件（不进子文件夹）
fn top_files(dir: &Path) -> Vec<PathBuf> {
    std::fs::read_dir(dir)
        .map(|rd| {
            rd.flatten()
                .filter(|e| e.file_type().is_ok_and(|t| t.is_file()))
                .map(|e| e.path())
                .collect()
        })
        .unwrap_or_default()
}

/// 文件（夹）的大小：文件夹按里面所有文件算，符号链接不跟进去
pub fn size_of(path: &Path) -> u64 {
    let Ok(md) = std::fs::symlink_metadata(path) else {
        return 0;
    };
    if !md.is_dir() {
        return md.len();
    }
    std::fs::read_dir(path)
        .map(|rd| rd.flatten().map(|e| size_of(&e.path())).sum())
        .unwrap_or(0)
}

pub fn files_size(files: &[PathBuf]) -> u64 {
    files.iter().map(|p| size_of(p)).sum()
}

#[derive(Clone, Debug, Serialize)]
pub struct Summary {
    pub id: String,
    pub title: String,
    pub date: String,
    pub time: String,
    pub summary: String,
    pub has_minutes: bool,
    pub has_transcript: bool,
    /// `transcript.json` 在但解析不出来：目录还在，内容坏了。界面据此标记「不可读」而不是假装没有转写。
    /// `纪要.md` 读不到不算——那只是文本，读不到就是没纪要。
    pub unreadable: bool,
    pub duration: String,
    pub speakers: Vec<String>,
    pub tags: Vec<String>,
    pub archived: bool,
    /// 整个会议文件夹的大小
    pub size_bytes: u64,
    /// 有没有 录音.* 文件
    pub has_audio: bool,
}

pub fn summary(dir: &Path) -> Summary {
    let name = dir
        .file_name()
        .and_then(|n| n.to_str())
        .unwrap_or("")
        .to_string();
    let minutes = std::fs::read_to_string(dir.join(MINUTES)).unwrap_or_default();
    let caps = FOLDER.captures(&name);
    let time = caps
        .as_ref()
        .map(|c| format!("{}:{}", &c[2], &c[3]))
        .unwrap_or_default();
    let fallback = caps
        .as_ref()
        .map(|c| c[4].to_string())
        .unwrap_or_else(|| name.clone());
    let transcript_path = dir.join(TRANSCRIPT_JSON);
    let tr = Transcript::load(&transcript_path).ok();
    let unreadable = transcript_path.is_file() && tr.is_none();
    let meta = read_meta(dir);
    Summary {
        // 标题：用户改的 → 纪要第一行 # 标题 → 文件夹名里的会议名
        title: meta
            .title
            .or_else(|| minutes_title(&minutes))
            .unwrap_or(fallback),
        date: meeting_date(dir).format("%Y-%m-%d").to_string(),
        time,
        summary: one_liner(&crate::terms::strip(&minutes)),
        has_minutes: !minutes.is_empty(),
        has_transcript: tr.is_some(),
        unreadable,
        duration: tr
            .as_ref()
            .map(|t| fmt_ts(t.duration_ms))
            .unwrap_or_default(),
        speakers: tr
            .as_ref()
            .map(|t| {
                t.speakers_in_order()
                    .into_iter()
                    .map(|s| t.name_of(s))
                    .collect()
            })
            .unwrap_or_default(),
        tags: meta.tags,
        archived: meta.archived,
        size_bytes: size_of(dir),
        has_audio: has_recording(dir),
        id: name,
    }
}

/// 会议目录的判定：有转写、有录音、或者只有纪要，都算。
///
/// 只认转写和录音会把"用户手工整理过、删掉录音只留纪要"的目录从列表里抹掉。
fn is_meeting_dir(dir: &Path) -> bool {
    dir.join(TRANSCRIPT_JSON).is_file() || find_audio(dir).is_some() || dir.join(MINUTES).is_file()
}

pub fn list(root: &Path) -> Vec<Summary> {
    let Ok(rd) = std::fs::read_dir(root) else {
        return Vec::new();
    };
    let mut items: Vec<Summary> = rd
        .flatten()
        .map(|e| e.path())
        .filter(|p| p.is_dir() && is_meeting_dir(p))
        .map(|p| summary(&p))
        .collect();
    items.sort_by(|a, b| (&b.date, &b.time, &b.id).cmp(&(&a.date, &a.time, &a.id)));
    items
}

/// 存储空间统计（不含模型目录和磁盘剩余空间，那两个由调用方补上）。
/// `busy(id)` 为 true 的会议正在处理（比如正在合成录音），不算残留文件。
pub fn storage(root: &Path, busy: impl Fn(&str) -> bool) -> Value {
    let list = list(root);
    let (mut audio_bytes, mut leftover_bytes) = (0u64, 0u64);
    let mut rows: Vec<(u64, Value)> = Vec::new();
    for m in &list {
        let dir = root.join(&m.id);
        let audio = files_size(&audio_files(&dir));
        audio_bytes += audio;
        if !busy(&m.id) {
            leftover_bytes += files_size(&leftover_files(&dir));
        }
        rows.push((
            m.size_bytes,
            serde_json::json!({"id": m.id, "title": m.title, "date": m.date, "bytes": m.size_bytes,
                               "audio_bytes": audio, "archived": m.archived}),
        ));
    }
    // 从大到小；一样大时保持列表顺序（新的在前）
    rows.sort_by_key(|r| std::cmp::Reverse(r.0));
    let largest: Vec<Value> = rows.into_iter().take(10).map(|(_, v)| v).collect();
    serde_json::json!({
        "root": root,
        "count": list.len(),
        "meetings_bytes": list.iter().map(|m| m.size_bytes).sum::<u64>(),
        "audio_bytes": audio_bytes,
        "leftover_bytes": leftover_bytes,
        "largest": largest,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn folder_naming_and_listing() {
        let root = std::env::temp_dir().join(format!("tingo-meet-{}", std::process::id()));
        std::fs::create_dir_all(&root).unwrap();
        let when = NaiveDate::from_ymd_opt(2026, 11, 2)
            .unwrap()
            .and_hms_opt(14, 30, 0)
            .unwrap();
        let a = new_folder(&root, "周 会/评审", when);
        assert_eq!(a.file_name().unwrap(), "2026-11-02_1430_周_会_评审");
        std::fs::create_dir_all(&a).unwrap();
        assert!(new_folder(&root, "周 会/评审", when).ends_with("2026-11-02_1430_周_会_评审_2"));
        std::fs::write(a.join(MINUTES), "# 周会\n\n## 一句话总结\n定了上线时间。\n").unwrap();
        std::fs::write(a.join("录音.m4a"), b"x").unwrap();
        let raw = root.join("2026-11-02_1500_录音中");
        std::fs::create_dir_all(&raw).unwrap();
        std::fs::write(raw.join("system.wav"), b"x").unwrap(); // 录音中的原始轨道不算会议
        let items = list(&root);
        assert_eq!(items.len(), 1);
        assert_eq!(
            (
                items[0].title.as_str(),
                items[0].summary.as_str(),
                items[0].time.as_str()
            ),
            ("周会", "定了上线时间。", "14:30")
        );
        assert_eq!(
            meeting_date(&a),
            NaiveDate::from_ymd_opt(2026, 11, 2).unwrap()
        );
        std::fs::remove_dir_all(root).ok();
    }

    fn temp_root(name: &str) -> PathBuf {
        let root = std::env::temp_dir().join(format!("tingo-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&root);
        std::fs::create_dir_all(&root).unwrap();
        root
    }

    #[test]
    fn tags_normalize() {
        let long = "一".repeat(25);
        let tags = normalize_tags(&[" 项目A ", "", "  ", "周会", "项目A", &long, "周会 "]);
        assert_eq!(
            tags,
            vec!["项目A".to_string(), "周会".into(), "一".repeat(20)]
        );
        let many: Vec<String> = (0..15).map(|i| format!("t{i}")).collect();
        let tags = normalize_tags(&many);
        assert_eq!(tags.len(), 10);
        assert_eq!((tags[0].as_str(), tags[9].as_str()), ("t0", "t9"));
        // 截到 20 个字符后末尾的空白也去掉
        assert_eq!(
            normalize_tag(&format!("{} x", "a".repeat(19))),
            "a".repeat(19)
        );
        assert_eq!(normalize_title(Some("  ")), None);
        assert_eq!(normalize_title(Some(" 新标题 ")).as_deref(), Some("新标题"));
    }

    #[test]
    fn meta_read_write() {
        let dir = temp_root("meta");
        assert_eq!(read_meta(&dir), Meta::default());
        let meta = Meta {
            title: Some(" 周会 ".into()),
            tags: vec!["项目A".into(), "项目A".into(), " 周会".into()],
            archived: true,
        };
        write_meta(&dir, &meta).unwrap();
        let text = std::fs::read_to_string(dir.join(META)).unwrap();
        assert_eq!(text, "{\n  \"title\": \"周会\",\n  \"tags\": [\n    \"项目A\",\n    \"周会\"\n  ],\n  \"archived\": true\n}");
        assert_eq!(
            read_meta(&dir),
            Meta {
                title: Some("周会".into()),
                tags: vec!["项目A".into(), "周会".into()],
                archived: true
            }
        );
        // 空标题不写这个键
        write_meta(
            &dir,
            &Meta {
                title: Some("".into()),
                tags: vec![],
                archived: false,
            },
        )
        .unwrap();
        assert_eq!(
            std::fs::read_to_string(dir.join(META)).unwrap(),
            "{\n  \"tags\": [],\n  \"archived\": false\n}"
        );
        // 手改坏的、类型不对的字段按默认值；读的时候也整理标签
        std::fs::write(
            dir.join(META),
            "{\"title\": 3, \"tags\": [\" a \", 1, \"a\"], \"archived\": \"yes\"}",
        )
        .unwrap();
        assert_eq!(
            read_meta(&dir),
            Meta {
                title: None,
                tags: vec!["a".into()],
                archived: false
            }
        );
        std::fs::write(dir.join(META), "not json").unwrap();
        assert_eq!(read_meta(&dir), Meta::default());
        std::fs::remove_dir_all(dir).ok();
    }

    #[test]
    fn list_fields_and_title_precedence() {
        let root = temp_root("list-fields");
        let a = root.join("2026-10-01_0900_文件夹标题");
        std::fs::create_dir_all(&a).unwrap();
        std::fs::write(a.join("录音.mp3"), vec![0u8; 100]).unwrap();
        let items = list(&root);
        assert_eq!(items[0].title, "文件夹标题");
        assert_eq!(
            (
                items[0].tags.len(),
                items[0].archived,
                items[0].has_audio,
                items[0].size_bytes
            ),
            (0, false, true, 100)
        );
        std::fs::write(a.join(MINUTES), "# 纪要标题\n").unwrap();
        assert_eq!(summary(&a).title, "纪要标题");
        write_meta(
            &a,
            &Meta {
                title: Some("我改的".into()),
                tags: vec!["周会".into()],
                archived: true,
            },
        )
        .unwrap();
        let m = summary(&a);
        assert_eq!(
            (m.title.as_str(), m.tags.clone(), m.archived),
            ("我改的", vec!["周会".to_string()], true)
        );
        assert_eq!(m.size_bytes, size_of(&a));
        assert!(m.size_bytes > 100);
        // 只有原始音轨（不是 录音.*）时 has_audio 为 false
        let b = root.join("2026-10-02_0900_b");
        std::fs::create_dir_all(&b).unwrap();
        std::fs::write(
            b.join(TRANSCRIPT_JSON),
            serde_json::to_string(&Transcript::default()).unwrap(),
        )
        .unwrap();
        std::fs::write(b.join("system.wav"), b"x").unwrap();
        assert!(!summary(&b).has_audio);
        let v = serde_json::to_value(summary(&b)).unwrap();
        for k in ["tags", "archived", "size_bytes", "has_audio"] {
            assert!(v.get(k).is_some(), "{k}");
        }
        std::fs::remove_dir_all(root).ok();
    }

    /// 规格 meeting-library-read：transcript.json 坏了目录还在，要标记不可读而不是悄悄当成「没转写」
    #[test]
    fn directory_with_only_minutes_still_lists() {
        // 用户可能手工整理过：删掉录音、只留纪要。这种目录不能从列表里消失。
        let root = temp_root("minutes-only");
        let dir = root.join("2026-11-02_1430_手工整理");
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(dir.join(MINUTES), "# 手工整理\n\n## 一句话总结\n只有纪要。\n").unwrap();

        let items = list(&root);
        assert_eq!(items.len(), 1);
        assert!(items[0].has_minutes);
        assert!(!items[0].has_audio);
        assert!(!items[0].unreadable);
    }

    #[test]
    fn corrupted_transcript_is_marked_unreadable() {
        let root = temp_root("unreadable");
        let broken = root.join("2026-10-05_0900_损坏");
        std::fs::create_dir_all(&broken).unwrap();
        std::fs::write(broken.join(TRANSCRIPT_JSON), "{not json").unwrap();
        std::fs::write(broken.join(MINUTES), "# 损坏会议\n").unwrap();
        let m = summary(&broken);
        assert!(m.unreadable);
        assert!(!m.has_transcript);
        assert_eq!(m.title, "损坏会议"); // 其它字段照旧，坏的不影响展示
                                         // 只有 纪要.md、没有 transcript.json：只是没转写，不算损坏
        let plain = root.join("2026-10-06_0900_没有转写");
        std::fs::create_dir_all(&plain).unwrap();
        std::fs::write(plain.join(MINUTES), "# 没转写\n").unwrap();
        std::fs::write(plain.join("录音.m4a"), b"x").unwrap();
        assert!(!summary(&plain).unreadable);
        // 列表里坏的也在，不会被丢掉
        let ids: Vec<String> = list(&root).into_iter().map(|m| m.id).collect();
        assert_eq!(ids.len(), 2);
        assert!(ids.contains(&"2026-10-05_0900_损坏".to_string()));
        std::fs::remove_dir_all(root).ok();
    }

    #[test]
    fn audio_and_leftover_files() {
        let dir = temp_root("leftovers");
        for f in [
            "system.wav",
            "mic.wav",
            "mixed.wav",
            "转写.md",
            "x.part",
            "y.TMP",
        ] {
            std::fs::write(dir.join(f), b"x").unwrap();
        }
        let names = |v: Vec<PathBuf>| {
            v.iter()
                .map(|p| file_name(p).to_string())
                .collect::<Vec<_>>()
        };
        assert_eq!(names(audio_files(&dir)), vec!["mic.wav", "system.wav"]);
        // 还没有 录音.*：原始音轨不算残留（可能正在录、或者合成没做）
        assert_eq!(names(leftover_files(&dir)), vec!["x.part", "y.TMP"]);
        std::fs::write(dir.join("录音.m4a"), b"x").unwrap();
        std::fs::write(dir.join("录音.wav"), b"x").unwrap();
        assert_eq!(
            names(audio_files(&dir)),
            vec!["mic.wav", "system.wav", "录音.m4a", "录音.wav"]
        );
        assert_eq!(
            names(leftover_files(&dir)),
            vec!["mic.wav", "system.wav", "x.part", "y.TMP"]
        );
        std::fs::remove_dir_all(dir).ok();
    }

    #[test]
    fn storage_stats() {
        let root = temp_root("storage");
        let mk = |name: &str, files: &[(&str, usize)]| {
            let d = root.join(name);
            std::fs::create_dir_all(&d).unwrap();
            for (f, n) in files {
                std::fs::write(d.join(f), vec![0u8; *n]).unwrap();
            }
            d
        };
        // a：合成好了但原始音轨还在 → 残留；b：只有原始音轨（没有 录音.*）→ 不算残留；c：最大
        mk(
            "2026-10-01_0900_a",
            &[
                ("录音.m4a", 100),
                ("system.wav", 30),
                ("mic.wav", 20),
                (TRANSCRIPT_JSON, 5),
            ],
        );
        mk(
            "2026-10-02_0900_b",
            &[("system.wav", 40), (TRANSCRIPT_JSON, 5), ("dl.part", 7)],
        );
        let c = mk("2026-10-03_0900_c", &[("录音.mp3", 1000), (MINUTES, 10)]);
        write_meta(
            &c,
            &Meta {
                archived: true,
                ..Default::default()
            },
        )
        .unwrap();
        mk("2026-10-04_0900_not-a-meeting", &[("notes.txt", 5000)]);
        let v = storage(&root, |_| false);
        assert_eq!(v["count"], 3);
        assert_eq!(v["audio_bytes"], 100 + 30 + 20 + 40 + 1000);
        assert_eq!(v["leftover_bytes"], 30 + 20 + 7);
        let meta_len = size_of(&c.join(META));
        assert_eq!(v["meetings_bytes"], 155 + 52 + 1010 + meta_len);
        let largest = v["largest"].as_array().unwrap();
        let ids: Vec<&str> = largest.iter().map(|x| x["id"].as_str().unwrap()).collect();
        assert_eq!(
            ids,
            vec![
                "2026-10-03_0900_c",
                "2026-10-01_0900_a",
                "2026-10-02_0900_b"
            ]
        );
        assert_eq!(largest[0]["audio_bytes"], 1000);
        assert_eq!(largest[0]["archived"], true);
        assert_eq!(largest[0]["date"], "2026-10-03");
        assert_eq!(largest[1]["bytes"], 155);
        // 正在处理的会议不算残留（比如正在合成录音）
        let v = storage(&root, |id| id.ends_with("_a"));
        assert_eq!(v["leftover_bytes"], 7);
        std::fs::remove_dir_all(root).ok();
    }
}
