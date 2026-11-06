//! 会议夹具：桌面 v0.5.2 的真实产出（`src/tingo/sample/` 改造而来，见 tasks 1.5）。
//!
//! 引擎侧的目录发现、字段解析、标题回退都以这两场会议为基准；Android 侧兼容验收（tasks 4.6）
//! 也用同一份夹具，所以这里只读不写，断言的是「桌面写的文件，引擎读得回来」。

use std::path::{Path, PathBuf};

use tingo_engine::{
    fishbone, meetings,
    transcript::{fmt_ts, Transcript},
};

const CHINESE_ID: &str = "2026-10-28_1430_官网注册转化复盘";
const ENGLISH_ID: &str = "2026-10-27_0930_Website_registration_post-mortem";

fn library() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures")
}

fn meeting(id: &str) -> PathBuf {
    library().join(id)
}

#[test]
fn lists_both_meetings_newest_first() {
    let items = meetings::list(&library());
    let ids: Vec<&str> = items.iter().map(|m| m.id.as_str()).collect();
    assert_eq!(ids, vec![CHINESE_ID, ENGLISH_ID]);
}

/// 中文会议：meta.json 的标题与标签、纪要里的一句话总结、转写里的说话人都要读对
#[test]
fn chinese_meeting_keeps_desktop_fields() {
    let m = meetings::summary(&meeting(CHINESE_ID));
    assert_eq!(m.title, "官网注册转化复盘"); // meta.json 里用户改过的标题优先
    assert_eq!((m.date.as_str(), m.time.as_str()), ("2026-10-28", "14:30"));
    assert!(m.summary.starts_with("上个月官网注册转化率从12%降至8.5%"));
    assert_eq!(m.duration, "02:00");
    assert_eq!(m.speakers, vec!["林舟", "陈默", "苏晴", "周远"]);
    assert_eq!(m.tags, vec!["转化", "项目A"]);
    assert_eq!(
        (m.has_minutes, m.has_transcript, m.has_audio, m.archived),
        (true, true, true, false)
    );
    assert!(m.size_bytes > 0);
    // 目录里的 录音.m4a 被认成这场会议的录音
    let audio = meetings::find_audio(&meeting(CHINESE_ID))
        .and_then(|p| p.file_name().map(|n| n.to_string_lossy().into_owned()));
    assert_eq!(audio.as_deref(), Some(meetings::AUDIO));
}

/// 英文会议：纪要语言跟文档走（## Summary），并且这场没有 录音.m4a
#[test]
fn english_meeting_without_audio() {
    let m = meetings::summary(&meeting(ENGLISH_ID));
    assert_eq!(
        m.title,
        "Post-mortem on the Website Registration Conversion Drop"
    ); // 没有 meta.json 时取纪要第一行
    assert_eq!((m.date.as_str(), m.time.as_str()), ("2026-10-27", "09:30"));
    assert!(m
        .summary
        .starts_with("The team reviewed the decline in website registration conversion"));
    assert_eq!(
        (m.has_minutes, m.has_transcript, m.has_audio),
        (true, true, false)
    );
    assert!(meetings::find_audio(&meeting(ENGLISH_ID)).is_none());
}

/// transcript.json 与桌面版格式一致：读回来还是同一份（Android 靠这个字段映射到 Kotlin 类型）
#[test]
fn transcript_json_round_trips() {
    let dir = meeting(CHINESE_ID);
    let tr = Transcript::load(&dir.join(meetings::TRANSCRIPT_JSON)).unwrap();
    assert_eq!(tr.sentences.len(), 21);
    assert_eq!(fmt_ts(tr.duration_ms), "02:00");
    assert_eq!(tr.speakers_in_order(), vec![0, 1, 2, 3]);
    assert_eq!(
        (tr.name_of(0).as_str(), tr.name_of(1).as_str()),
        ("林舟", "陈默")
    );
    assert!(tr.sentences[0].text.contains("官网注册转化"));

    let copy = std::env::temp_dir().join(format!(
        "tingo-fixture-{}-transcript.json",
        std::process::id()
    ));
    tr.save(&copy).unwrap();
    let reloaded = Transcript::load(&copy).unwrap();
    assert_eq!(
        serde_json::to_value(&reloaded).unwrap(),
        serde_json::to_value(&tr).unwrap()
    );
    std::fs::remove_file(copy).ok();
}

/// 纪要用例：英文纪要里的 fishbone 代码块（引擎侧的解析，Kotlin 只做展示模型转换）
#[test]
fn fishbone_block_in_minutes_parses() {
    let minutes = std::fs::read_to_string(meeting(ENGLISH_ID).join(meetings::MINUTES)).unwrap();
    let blocks = fishbone::blocks(&minutes);
    assert_eq!(blocks.len(), 1);
    let spec = blocks[0].1.as_ref().expect("fishbone 块应能解析");
    assert!(spec.problem.contains("12% to 8.5%"));
    assert_eq!(spec.branches.len(), 4);
    assert_eq!(spec.branches[1].title, "SMS verification delay");
    assert_eq!(spec.branches[0].refs, vec!["00:25", "00:37"]);
}
