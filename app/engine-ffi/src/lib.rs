//! tingo 引擎的 UniFFI 边界层。
//!
//! 这里只有两件事：把引擎能力以「领域类型 + 用例函数」暴露给 Kotlin，以及保证任何
//! Rust 侧 panic 都被转换成一个可展示的错误，而不是让宿主进程崩溃。
//!
//! 边界刻意保持窄：ASR 在 Android 上由 Kotlin 侧承担，因此这里不引入 sherpa 相关模块。

use std::panic::{self, AssertUnwindSafe};
use std::path::{Path, PathBuf};
use std::sync::Arc;

use tingo_engine::fishbone as engine_fishbone;
use tingo_engine::meetings;
use tingo_engine::minutes as engine_minutes;
use tingo_engine::paths::EnginePaths;
use tingo_engine::transcript as engine_transcript;

uniffi::setup_scaffolding!();

/// 桥接层的错误类别。Kotlin 侧据类别选文案，不直接展示原始信息里的英文细节。
#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum EngineError {
    /// 调用方传入的参数不合法（空 id、含路径分隔符的 id 等）。
    #[error("input rejected: {detail}")]
    InvalidInput { detail: String },
    /// 需要的文件或目录不存在。
    #[error("resource missing: {detail}")]
    Missing { detail: String },
    /// 文件存在但内容无法解析。
    #[error("malformed content: {detail}")]
    Malformed { detail: String },
    /// 平台权限不足（如目录不可读写）。
    #[error("permission denied: {detail}")]
    PermissionDenied { detail: String },
    /// 引擎内部错误，含 panic 兜底。
    #[error("internal error: {detail}")]
    Internal { detail: String },
}

/// 引擎能力矩阵：界面据此决定哪些入口可见。
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct Capabilities {
    pub meeting_storage: bool,
    pub transcript_parse: bool,
    pub minutes_parse: bool,
    pub fishbone_parse: bool,
    pub speaker_naming: bool,
    pub terms_extract: bool,
}

/// 会议列表项。字段与引擎的 `meetings::Summary` 一一对应，界面不再二次拼装。
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct MeetingSummary {
    pub id: String,
    pub title: String,
    /// `YYYY-MM-DD`
    pub date: String,
    /// `HH:MM`，目录名里没时间时为空串
    pub time: String,
    /// 纪要里「一句话总结」那句话
    pub one_liner: String,
    pub has_minutes: bool,
    pub has_transcript: bool,
    /// `transcript.json` 损坏、目录不可读。Kotlin 侧为此条目显示「不可读」标记。
    pub unreadable: bool,
    pub has_audio: bool,
    /// 转写时长，`mm:ss` 或 `hh:mm:ss`
    pub duration: String,
    pub speakers: Vec<String>,
    pub tags: Vec<String>,
    pub archived: bool,
    pub size_bytes: u64,
}

/// 转写里的一句。说话人用编号表达，别名另存，界面按需替换。
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct Sentence {
    pub speaker: u32,
    pub start_ms: u64,
    pub end_ms: u64,
    pub text: String,
}

/// 一位说话人的别名。
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct SpeakerAlias {
    pub speaker: u32,
    pub name: String,
}

/// 合并后的发言段：同一说话人连续句子合成一段，仍保留边界时间与逐句内容。
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct Turn {
    /// 已解析的显示名（有别名用别名，否则「说话人N」）
    pub speaker_name: String,
    pub start_ms: u64,
    pub end_ms: u64,
    pub text: String,
    pub sentences: Vec<Sentence>,
}

/// 一份转写。
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct Transcript {
    pub source: String,
    pub duration_ms: u64,
    pub aliases: Vec<SpeakerAlias>,
    pub turns: Vec<Turn>,
}

/// 鱼骨图分支：`refs` 是形如 `12:34` 的原文引用。
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct FishboneBranch {
    pub title: String,
    pub detail: String,
    pub refs: Vec<String>,
}

/// 鱼骨图。
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct Fishbone {
    pub problem: String,
    pub branches: Vec<FishboneBranch>,
}


/// 纪要章节种类。中英标题在引擎侧已归一到这里，界面不必按语言匹配标题。
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum SectionKind {
    OneLiner,
    Discussion,
    Decisions,
    Actions,
    OpenQuestions,
    RootCause,
    /// 手改或新增的章节，`heading` 里带原标题
    Other,
}

/// 纪要的一个章节。`body` 是原样正文（含子标题、表格、代码块），不做渲染。
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct MinutesSection {
    pub kind: SectionKind,
    pub heading: String,
    pub body: String,
}

/// 一份解析后的纪要。
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct MinutesDoc {
    /// H1 标题；没有 H1 时为空串
    pub title: String,
    pub sections: Vec<MinutesSection>,
}

/// 一个会议的全部可读内容。缺哪个文件，对应字段就是 `None`，不视为损坏。
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct MeetingDetail {
    pub summary: MeetingSummary,
    pub transcript: Option<Transcript>,
    /// 纪要原文（Markdown）。分章节解析由 `parse_minutes` 负责。
    pub minutes_text: Option<String>,
    pub fishbone: Option<Fishbone>,
}

/// 引擎实例：持有注入的目录，所有用例函数都是它的方法。
#[derive(uniffi::Object)]
pub struct TingoEngine {
    paths: EnginePaths,
}

#[uniffi::export]
impl TingoEngine {
    /// 以 Android 沙箱根目录构造。会议、配置、缓存三个子目录由引擎按约定推导。
    #[uniffi::constructor]
    pub fn new(data_root: String) -> Arc<Self> {
        let paths = EnginePaths::android(&PathBuf::from(data_root));
        Arc::new(Self { paths })
    }

    /// 会议库根目录的绝对路径，供界面展示与排障。
    pub fn meetings_root(&self) -> String {
        self.paths.meetings_root.display().to_string()
    }

    /// 扫描会议库：按时间倒序返回。根目录不存在时返回空列表而不是报错。
    pub fn scan_library(&self) -> Result<Vec<MeetingSummary>, EngineError> {
        let root = self.paths.meetings_root.clone();
        guard("scan_library", move || Ok(meetings::list(&root).into_iter().map(to_summary).collect()))
    }

    /// 读取单个会议。`id` 必须是会议库根目录下的单层目录名。
    pub fn read_meeting(&self, id: String) -> Result<MeetingDetail, EngineError> {
        let dir = meeting_dir(&self.paths.meetings_root, &id)?;
        guard("read_meeting", move || read_meeting_in(&dir))
    }

    /// 解析纪要 Markdown 为章节。章节解析只在引擎侧实现一次，界面拿到的是结构化结果。
    pub fn parse_minutes(&self, text: String) -> Result<MinutesDoc, EngineError> {
        guard("parse_minutes", move || Ok(to_minutes(engine_minutes::parse(&text))))
    }

    /// 归档 / 取消归档。只改 meta.json 的 `archived` 字段，其它字段原样保留。
    pub fn set_archived(&self, id: String, archived: bool) -> Result<(), EngineError> {
        let dir = meeting_dir(&self.paths.meetings_root, &id)?;
        guard("set_archived", move || {
            let mut meta = meetings::read_meta(&dir);
            if meta.archived == archived {
                return Ok(());
            }
            meta.archived = archived;
            meetings::write_meta(&dir, &meta).map_err(|e| io_error(e, "set_archived"))
        })
    }

    /// 删除整场会议（含录音、转写、纪要），返回释放的字节数。
    ///
    /// 直接删而不是进废纸篓：Android 沙箱里没有系统废纸篓，界面必须先确认。
    pub fn delete_meeting(&self, id: String) -> Result<u64, EngineError> {
        let dir = meeting_dir(&self.paths.meetings_root, &id)?;
        guard("delete_meeting", move || {
            let freed = meetings::size_of(&dir);
            std::fs::remove_dir_all(&dir).map_err(|e| io_error(e, "delete_meeting"))?;
            Ok(freed)
        })
    }

    /// 解析纪要里的鱼骨图代码块。没有代码块返回 `None`；内容非法返回 `Malformed`。
    pub fn parse_fishbone(&self, minutes_text: String) -> Result<Option<Fishbone>, EngineError> {
        guard("parse_fishbone", move || {
            match engine_fishbone::blocks(&minutes_text).into_iter().next() {
                None => Ok(None),
                Some((_, None)) => Err(EngineError::Malformed {
                    detail: "fishbone block is not valid JSON".to_string(),
                }),
                Some((_, Some(spec))) => Ok(Some(to_fishbone(spec))),
            }
        })
    }
}

/// 引擎版本。取被链接的 `tingo-engine` crate 版本，避免两处各写一份。
#[uniffi::export]
pub fn engine_version() -> String {
    tingo_engine::VERSION.to_string()
}

/// 当前构建实提供的能力。Android 构建没有 ASR，但核心的会话存储与
/// 转写/纪要/鱼骨图解析、说话人命名、术语抽取都在。
#[uniffi::export]
pub fn capabilities() -> Capabilities {
    Capabilities {
        meeting_storage: true,
        transcript_parse: true,
        minutes_parse: true,
        fishbone_parse: true,
        speaker_naming: true,
        terms_extract: true,
    }
}

/// 导出函数的统一入口：panic 转成 `EngineError::Internal`，不穿透到宿主进程。
fn guard<T>(what: &str, f: impl FnOnce() -> Result<T, EngineError>) -> Result<T, EngineError> {
    match panic::catch_unwind(AssertUnwindSafe(f)) {
        Ok(result) => result,
        Err(_) => Err(EngineError::Internal {
            detail: format!("{what} panicked"),
        }),
    }
}

/// 会议 id 只能是单层目录名：挡掉 `../` 这类逃逸，避免宿主把 ID 当成任意路径。
fn meeting_dir(root: &Path, id: &str) -> Result<PathBuf, EngineError> {
    if id.trim().is_empty() || id.contains('/') || id.contains('\\') || id == "." || id == ".." {
        return Err(EngineError::InvalidInput {
            detail: format!("invalid meeting id: {id}"),
        });
    }
    let dir = root.join(id);
    if !dir.is_dir() {
        return Err(EngineError::Missing {
            detail: format!("meeting not found: {id}"),
        });
    }
    Ok(dir)
}

/// 读文件失败按原因分类：权限不足和内容损坏对用户是两件事。
fn io_error(err: std::io::Error, what: &str) -> EngineError {
    let detail = format!("{what}: {err}");
    match err.kind() {
        std::io::ErrorKind::PermissionDenied => EngineError::PermissionDenied { detail },
        _ => EngineError::Malformed { detail },
    }
}

fn read_meeting_in(dir: &Path) -> Result<MeetingDetail, EngineError> {
    let summary = to_summary(meetings::summary(dir));
    let transcript = match engine_transcript::Transcript::load(&dir.join("transcript.json")) {
        Ok(tr) => Some(to_transcript(tr)),
        Err(err) if err.kind() == std::io::ErrorKind::NotFound => None,
        Err(err) => return Err(io_error(err, "transcript.json")),
    };
    let minutes_text = std::fs::read_to_string(dir.join("纪要.md")).ok();
    let fishbone = minutes_text
        .as_deref()
        .and_then(|md| engine_fishbone::blocks(md).into_iter().find_map(|(_, spec)| spec))
        .map(to_fishbone);
    Ok(MeetingDetail {
        summary,
        transcript,
        minutes_text,
        fishbone,
    })
}

fn to_summary(s: meetings::Summary) -> MeetingSummary {
    MeetingSummary {
        id: s.id,
        title: s.title,
        date: s.date,
        time: s.time,
        one_liner: s.summary,
        has_minutes: s.has_minutes,
        has_transcript: s.has_transcript,
        unreadable: s.unreadable,
        has_audio: s.has_audio,
        duration: s.duration,
        speakers: s.speakers,
        tags: s.tags,
        archived: s.archived,
        size_bytes: s.size_bytes,
    }
}

fn to_transcript(tr: engine_transcript::Transcript) -> Transcript {
    let aliases: Vec<SpeakerAlias> = tr
        .speaker_names
        .iter()
        .map(|(speaker, name)| SpeakerAlias {
            speaker: *speaker as u32,
            name: name.clone(),
        })
        .collect();
    // 合并规则与引擎 `Transcript::turns` 一致（同一说话人连续句子合一段，
    // 文本直接相接、结束时间取最后一句），区别是这里额外保留逐句内容供界面定位。
    let mut turns: Vec<Turn> = Vec::new();
    for s in &tr.sentences {
        let name = tr.name_of(s.speaker);
        let sentence = Sentence {
            speaker: s.speaker as u32,
            start_ms: s.start_ms,
            end_ms: s.end_ms,
            text: s.text.clone(),
        };
        match turns.last_mut() {
            Some(turn) if turn.speaker_name == name => {
                turn.text.push_str(&s.text);
                turn.end_ms = s.end_ms;
                turn.sentences.push(sentence);
            }
            _ => turns.push(Turn {
                speaker_name: name,
                start_ms: s.start_ms,
                end_ms: s.end_ms,
                text: s.text.clone(),
                sentences: vec![sentence],
            }),
        }
    }
    Transcript {
        source: tr.source,
        duration_ms: tr.duration_ms,
        aliases,
        turns,
    }
}


fn to_minutes(doc: engine_minutes::MinutesDoc) -> MinutesDoc {
    MinutesDoc {
        title: doc.title,
        sections: doc
            .sections
            .into_iter()
            .map(|s| MinutesSection {
                kind: to_section_kind(&s.kind),
                heading: s.heading,
                body: s.body,
            })
            .collect(),
    }
}

fn to_section_kind(kind: &engine_minutes::SectionKind) -> SectionKind {
    use engine_minutes::SectionKind as K;
    match kind {
        K::OneLiner => SectionKind::OneLiner,
        K::Discussion => SectionKind::Discussion,
        K::Decisions => SectionKind::Decisions,
        K::Actions => SectionKind::Actions,
        K::OpenQuestions => SectionKind::OpenQuestions,
        K::RootCause => SectionKind::RootCause,
        K::Other(_) => SectionKind::Other,
    }
}

fn to_fishbone(spec: engine_fishbone::Spec) -> Fishbone {
    Fishbone {
        problem: spec.problem,
        branches: spec
            .branches
            .into_iter()
            .map(|b| FishboneBranch {
                title: b.title,
                detail: b.detail,
                refs: b.refs,
            })
            .collect(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn engine_version_is_not_empty() {
        assert!(!engine_version().trim().is_empty());
    }

    #[test]
    fn capabilities_expose_all_six_keys() {
        let caps = capabilities();
        assert!(caps.meeting_storage);
        assert!(caps.transcript_parse);
        assert!(caps.minutes_parse);
        assert!(caps.fishbone_parse);
        assert!(caps.speaker_naming);
        assert!(caps.terms_extract);
    }

    /// 造一个最小会议目录：只有纪要，引擎也认得。
    fn meeting(root: &Path, id: &str) -> PathBuf {
        // 引擎的会议库在 data_root/meetings 下
        let dir = root.join("meetings").join(id);
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(dir.join("纪要.md"), "# 标题\n\n## 决议\n- 先修首屏\n").unwrap();
        dir
    }

    /// 每个用例一个独立根目录：测试并行跑，共用一个目录会互相踩。
    fn temp_root(name: &str) -> PathBuf {
        let root = std::env::temp_dir().join(format!("tingo-ffi-{}-{name}", std::process::id()));
        let _ = std::fs::remove_dir_all(&root);
        std::fs::create_dir_all(&root).unwrap();
        root
    }

    #[test]
    fn set_archived_flips_meta_and_keeps_other_fields() {
        let root = temp_root("archive");
        let dir = meeting(&root, "2026-10-28_1430_复盘");
        let mut meta = meetings::read_meta(&dir);
        meta.title = Some("复盘".to_string());
        meta.tags = vec!["产品".to_string()];
        meetings::write_meta(&dir, &meta).unwrap();

        let engine = TingoEngine::new(root.display().to_string());
        engine.set_archived("2026-10-28_1430_复盘".to_string(), true).unwrap();

        let after = meetings::read_meta(&dir);
        assert!(after.archived);
        assert_eq!(after.title.as_deref(), Some("复盘"));
        assert_eq!(after.tags, vec!["产品".to_string()]);

        engine.set_archived("2026-10-28_1430_复盘".to_string(), false).unwrap();
        assert!(!meetings::read_meta(&dir).archived);
        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn delete_meeting_removes_folder_and_reports_freed_bytes() {
        let root = temp_root("delete");
        let dir = meeting(&root, "2026-10-28_1430_复盘");
        let engine = TingoEngine::new(root.display().to_string());

        let freed = engine.delete_meeting("2026-10-28_1430_复盘".to_string()).unwrap();

        assert!(!dir.exists());
        assert!(freed > 0);
        assert!(engine.scan_library().unwrap().is_empty());
        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn archive_rejects_path_traversal_ids() {
        let root = temp_root("traversal");
        let engine = TingoEngine::new(root.display().to_string());
        assert!(engine.set_archived("../etc".to_string(), true).is_err());
        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn panic_becomes_internal_error() {
        let result: Result<(), EngineError> = guard("boom", || panic!("boom"));
        match result {
            Err(EngineError::Internal { detail }) => assert!(detail.contains("boom")),
            other => panic!("expected internal error, got {other:?}"),
        }
    }

    #[test]
    fn empty_root_scans_to_empty_list() {
        let dir = tempfile::tempdir().unwrap();
        let engine = TingoEngine::new(dir.path().display().to_string());
        assert!(engine.scan_library().unwrap().is_empty());
    }

    #[test]
    fn rejects_ids_that_escape_root() {
        let dir = tempfile::tempdir().unwrap();
        let engine = TingoEngine::new(dir.path().display().to_string());
        for bad in ["", "..", "../etc", "a/b"] {
            match engine.read_meeting(bad.to_string()) {
                Err(EngineError::InvalidInput { .. }) => {}
                other => panic!("expected InvalidInput for {bad:?}, got {other:?}"),
            }
        }
    }

    /// 建一个含指定文件的会议目录，返回 (临时根, 引擎)。
    fn engine_with_meeting(id: &str, files: &[(&str, &str)]) -> (tempfile::TempDir, Arc<TingoEngine>) {
        let dir = tempfile::tempdir().unwrap();
        let meeting = dir.path().join("meetings").join(id);
        std::fs::create_dir_all(&meeting).unwrap();
        for (name, body) in files {
            std::fs::write(meeting.join(name), body).unwrap();
        }
        let engine = TingoEngine::new(dir.path().display().to_string());
        (dir, engine)
    }

    const TRANSCRIPT_ZH: &str = r#"{
        "source": "/x/录音.m4a",
        "duration_ms": 3725000,
        "speaker_names": {"1": "张伟"},
        "sentences": [
            {"speaker": 0, "start_ms": 0, "end_ms": 1500, "text": "我们先过一下进度。"},
            {"speaker": 0, "start_ms": 1500, "end_ms": 3200, "text": "上周的工单清完了。"},
            {"speaker": 1, "start_ms": 3200, "end_ms": 5000, "text": "接口联调还有两天。"}
        ]
    }"#;

    const MINUTES_ZH: &str = "# 上线协调会\n\n## 一句话总结\n定了下周一上线。\n\n## 决议\n- 周一发版\n\n## 原因分析\n```fishbone\n{\"problem\": \"为什么延期\", \"branches\": [{\"title\": \"联调慢\", \"detail\": \"接口变动\", \"refs\": [\"00:05\"]}]}\n```\n";

    #[test]
    fn transcript_roundtrip_keeps_chinese_alias_and_ms() {
        let (_dir, engine) = engine_with_meeting("2026-03-05_1000_上线协调会", &[("transcript.json", TRANSCRIPT_ZH)]);
        let detail = engine.read_meeting("2026-03-05_1000_上线协调会".to_string()).unwrap();
        let tr = detail.transcript.expect("transcript parsed");

        assert_eq!(tr.duration_ms, 3_725_000);
        // 别名优先：说话人 1 有别名，说话人 0 没有
        assert_eq!(tr.aliases.len(), 1);
        assert_eq!(tr.aliases[0].speaker, 1);
        assert_eq!(tr.aliases[0].name, "张伟");

        // 同一说话人的连续两句合并成一段，逐句内容保留
        assert_eq!(tr.turns.len(), 2);
        assert_eq!(tr.turns[0].speaker_name, "说话人1");
        assert_eq!(tr.turns[0].text, "我们先过一下进度。上周的工单清完了。");
        assert_eq!(tr.turns[0].end_ms, 3_200);
        assert_eq!(tr.turns[0].sentences.len(), 2);
        assert_eq!(tr.turns[1].speaker_name, "张伟");
        assert_eq!(tr.turns[1].sentences[0].start_ms, 3_200);
    }

    #[test]
    fn minutes_text_and_fishbone_come_from_disk() {
        let (_dir, engine) = engine_with_meeting(
            "2026-03-05_1000_上线协调会",
            &[("transcript.json", TRANSCRIPT_ZH), ("纪要.md", MINUTES_ZH)],
        );
        let detail = engine.read_meeting("2026-03-05_1000_上线协调会".to_string()).unwrap();
        assert_eq!(detail.minutes_text.as_deref(), Some(MINUTES_ZH));

        let fishbone = detail.fishbone.expect("fishbone parsed");
        assert_eq!(fishbone.problem, "为什么延期");
        assert_eq!(fishbone.branches[0].title, "联调慢");
        assert_eq!(fishbone.branches[0].refs, vec!["00:05".to_string()]);
    }

    #[test]
    fn missing_minutes_is_not_an_error() {
        let (_dir, engine) = engine_with_meeting("2026-03-05_1000_无纪要", &[("transcript.json", TRANSCRIPT_ZH)]);
        let detail = engine.read_meeting("2026-03-05_1000_无纪要".to_string()).unwrap();
        assert!(detail.minutes_text.is_none());
        assert!(detail.fishbone.is_none());
    }

    #[test]
    fn corrupted_transcript_is_malformed() {
        let (_dir, engine) = engine_with_meeting("2026-03-05_1000_损坏", &[("transcript.json", "{not json")]);
        match engine.read_meeting("2026-03-05_1000_损坏".to_string()) {
            Err(EngineError::Malformed { .. }) => {}
            other => panic!("expected Malformed, got {other:?}"),
        }
    }

    /// 规格 meeting-library-read：坏目录仍进列表，条目带 unreadable 标记
    #[test]
    fn scan_library_flags_corrupted_transcript() {
        let (_dir, engine) = engine_with_meeting("2026-03-05_1000_损坏", &[("transcript.json", "{not json")]);
        let items = engine.scan_library().unwrap();
        assert_eq!(items.len(), 1);
        assert!(items[0].unreadable);
        assert!(!items[0].has_transcript);
    }

    #[test]
    fn invalid_fishbone_block_is_malformed() {
        let (_dir, engine) = engine_with_meeting("2026-03-05_1000_坏鱼骨", &[("纪要.md", "## 原因分析\n```fishbone\nnope\n```\n")]);
        match engine.parse_fishbone("## 原因分析\n```fishbone\nnope\n```\n".to_string()) {
            Err(EngineError::Malformed { .. }) => {}
            other => panic!("expected Malformed, got {other:?}"),
        }
    }

    #[test]
    fn minutes_are_parsed_into_typed_sections() {
        let (_dir, engine) = engine_with_meeting("2026-03-05_1000_纪要", &[("纪要.md", MINUTES_ZH)]);
        let doc = engine.parse_minutes(MINUTES_ZH.to_string()).unwrap();
        assert_eq!(doc.title, "上线协调会");
        let kinds: Vec<SectionKind> = doc.sections.iter().map(|s| s.kind.clone()).collect();
        assert_eq!(
            kinds,
            vec![SectionKind::OneLiner, SectionKind::Decisions, SectionKind::RootCause]
        );
        assert_eq!(doc.sections[0].body.trim(), "定了下周一上线。");
        // fishbone 代码块原样留在「原因分析」正文里，由 parse_fishbone 负责解析
        assert!(doc.sections[2].body.contains("```fishbone"));
    }

    #[test]
    fn unknown_section_is_preserved_in_order() {
        let md = "# 周会\n\n## 一句话总结\nok\n\n## 我手加的章节\n内容\n";
        let (_dir, engine) = engine_with_meeting("2026-03-05_1000_未知", &[("纪要.md", md)]);
        let doc = engine.parse_minutes(md.to_string()).unwrap();
        assert_eq!(doc.sections.len(), 2);
        assert_eq!(doc.sections[1].kind, SectionKind::Other);
        assert_eq!(doc.sections[1].heading, "我手加的章节");
    }

    #[test]
    fn empty_minutes_yield_empty_doc() {
        let (_dir, engine) = engine_with_meeting("2026-03-05_1000_空", &[]);
        let doc = engine.parse_minutes(String::new()).unwrap();
        assert_eq!(doc.title, "");
        assert!(doc.sections.is_empty());
    }
}
