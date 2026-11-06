//! 纪要章节解析：把 [`crate::summarize`] 产出的 Markdown 切成结构化章节，供桥接层交给界面。
//!
//! 只切分、不渲染：章节正文原样保留（`###` 子标题、表格、列表、fishbone / terms 代码块都不动），
//! 代码块的内容交给 [`crate::fishbone`] / [`crate::terms`] 解析。未识别的 `##` 章节也保留——
//! 用户会手改纪要，丢掉不认识的章节等于丢内容。

/// 章节种类。中英文标题归一到这里，界面就不用按语言匹配标题。
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum SectionKind {
    OneLiner,
    Discussion,
    Decisions,
    Actions,
    OpenQuestions,
    RootCause,
    /// 纪要里手改出来或将来新增的章节，保留原文标题
    Other(String),
}

impl SectionKind {
    /// 章节标题（去掉两侧空白、不含 `## `）→ 种类；不认识的就是 [`SectionKind::Other`]。
    pub fn of(heading: &str) -> SectionKind {
        match heading.trim() {
            "一句话总结" | "Summary" => SectionKind::OneLiner,
            "议题与讨论" | "Discussion" => SectionKind::Discussion,
            "决议" | "Decisions" => SectionKind::Decisions,
            "待办" | "Action Items" => SectionKind::Actions,
            "待定问题与风险" | "Open Questions & Risks" => SectionKind::OpenQuestions,
            "原因分析" | "Root Cause Analysis" => SectionKind::RootCause,
            other => SectionKind::Other(other.to_string()),
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Section {
    pub kind: SectionKind,
    /// 原文标题，去掉两侧空白
    pub heading: String,
    /// 原样正文（首尾空行已去掉，内部空行、缩进、代码块保持不变）
    pub body: String,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Doc {
    /// H1 标题；纪要没有 H1 时为空串
    pub title: String,
    pub sections: Vec<Section>,
}

/// 解析纪要。纯函数，不读写文件。
pub fn parse(md: &str) -> Doc {
    let mut title = String::new();
    let mut sections: Vec<Section> = Vec::new();
    let mut current: Option<(String, Vec<&str>)> = None;

    for line in md.lines() {
        if let Some(h1) = line.strip_prefix("# ") {
            // 只取第一个 H1 当标题；章节里的 `### 1. 议题` 不是 H1
            if title.is_empty() && current.is_none() {
                title = h1.trim().to_string();
            }
        } else if let Some(h2) = line.strip_prefix("## ") {
            if let Some((heading, body)) = current.take() {
                sections.push(section(heading, &body));
            }
            current = Some((h2.trim().to_string(), Vec::new()));
        } else if let Some((_, body)) = current.as_mut() {
            body.push(line);
        }
    }
    if let Some((heading, body)) = current {
        sections.push(section(heading, &body));
    }
    Doc { title, sections }
}

fn section(heading: String, lines: &[&str]) -> Section {
    let body = lines.join("\n").trim_matches('\n').trim_end().to_string();
    Section { kind: SectionKind::of(&heading), heading, body }
}

#[cfg(test)]
mod tests {
    use super::*;

    const CHINESE: &str = "\
# 官网注册转化率下降复盘会

- 日期：2026-10-28（星期三）
- 时长：02:00

## 一句话总结
上个月转化率从12%降至8.5%。

## 议题与讨论
### 1. 转化率下降的数据表现
- 注册转化率从12%降至8.5%（[00:11]）

### 2. 注册页改版
- 多了一个必填项（[00:25]）

## 决议
- 邮箱改为选填（[01:23]）

## 待办
| 事项 | 负责人 | 截止时间 | 出处 |
|---|---|---|---|
| 上线改版 | 苏晴 | 11月4日 | [01:23] |

## 待定问题与风险
- 竞品活动暂无数据支撑（[01:16]）

## 原因分析
```fishbone
{\"problem\": \"为什么转化率下降？\", \"branches\": [{\"title\": \"改版\", \"detail\": \"多一个必填项\", \"refs\": [\"00:25\"]}]}
```

```terms
{\"terms\": [{\"term\": \"转化率\", \"variants\": []}]}
```
";

    const ENGLISH: &str = "\
# Post-mortem on the Website Registration Conversion Drop

- Date: 2026-10-28

## Summary
Conversion fell from 12% to 8.5%.

## Discussion
### 1. Funnel data
- The drop is at step two ([00:11])

## Decisions
- Make email optional ([01:23])

## Action Items
| Item | Owner | Due | Source |
|---|---|---|---|
| Ship the change | Su Qing | Nov 4 | [01:23] |

## Open Questions & Risks
- Competitor promotion is unconfirmed ([01:16])

## Root Cause Analysis
```fishbone
{\"problem\": \"Why did conversion fall?\", \"branches\": [{\"title\": \"Redesign\", \"detail\": \"Extra required field\", \"refs\": [\"00:25\"]}]}
```
";

    #[test]
    fn chinese_minutes_sections_in_order() {
        let doc = parse(CHINESE);
        assert_eq!(doc.title, "官网注册转化率下降复盘会");
        let kinds: Vec<SectionKind> = doc.sections.iter().map(|s| s.kind.clone()).collect();
        assert_eq!(
            kinds,
            vec![
                SectionKind::OneLiner,
                SectionKind::Discussion,
                SectionKind::Decisions,
                SectionKind::Actions,
                SectionKind::OpenQuestions,
                SectionKind::RootCause,
            ]
        );
        let headings: Vec<&str> = doc.sections.iter().map(|s| s.heading.as_str()).collect();
        assert_eq!(headings, vec!["一句话总结", "议题与讨论", "决议", "待办", "待定问题与风险", "原因分析"]);
        // H1 与列表之间那几行元信息不属于任何 `##` 章节
        assert!(!doc.sections[0].body.contains("日期"));
        assert_eq!(doc.sections[0].body, "上个月转化率从12%降至8.5%。");
        // `###` 子标题与列表原样留在正文里
        assert!(doc.sections[1].body.starts_with("### 1. 转化率下降的数据表现\n- 注册转化率从12%降至8.5%（[00:11]）"));
        assert!(doc.sections[1].body.contains("\n\n### 2. 注册页改版"));
        // 表格原样保留
        assert!(doc.sections[3].body.starts_with("| 事项 | 负责人 | 截止时间 | 出处 |"));
    }

    #[test]
    fn english_minutes_map_to_the_same_kinds() {
        let doc = parse(ENGLISH);
        assert_eq!(doc.title, "Post-mortem on the Website Registration Conversion Drop");
        let kinds: Vec<SectionKind> = doc.sections.iter().map(|s| s.kind.clone()).collect();
        assert_eq!(
            kinds,
            vec![
                SectionKind::OneLiner,
                SectionKind::Discussion,
                SectionKind::Decisions,
                SectionKind::Actions,
                SectionKind::OpenQuestions,
                SectionKind::RootCause,
            ]
        );
        assert_eq!(doc.sections[4].heading, "Open Questions & Risks");
        assert!(doc.sections[3].body.contains("Ship the change"));
    }

    /// 「原因分析」里的 fishbone / terms 代码块原样留在正文，解析交给 fishbone / terms 模块
    #[test]
    fn code_blocks_stay_in_root_cause_body() {
        let doc = parse(CHINESE);
        let root = doc.sections.iter().find(|s| s.kind == SectionKind::RootCause).unwrap();
        assert!(root.body.starts_with("```fishbone"));
        assert!(root.body.contains("```terms"));
        assert!(root.body.ends_with("```"));
        // 交给既有解析器能认出鱼骨图与术语表
        let blocks = crate::fishbone::blocks(&root.body);
        assert_eq!(blocks.len(), 1);
        assert_eq!(blocks[0].1.as_ref().unwrap().problem, "为什么转化率下降？");
        assert_eq!(crate::terms::parse(&root.body).len(), 1);
    }

    #[test]
    fn missing_h1_leaves_title_empty() {
        let doc = parse("## 一句话总结\n定了上线时间。\n");
        assert_eq!(doc.title, "");
        assert_eq!(doc.sections.len(), 1);
        assert_eq!(doc.sections[0].kind, SectionKind::OneLiner);
        assert_eq!(doc.sections[0].body, "定了上线时间。");
    }

    /// 用户手改出来的章节不能丢，顺序保持原样
    #[test]
    fn unknown_sections_are_kept_in_order() {
        let md = "## 一句话总结\n好\n\n## 我加的章节\n手写内容\n\n## 待办\n- 跟进\n";
        let doc = parse(md);
        let kinds: Vec<SectionKind> = doc.sections.iter().map(|s| s.kind.clone()).collect();
        assert_eq!(
            kinds,
            vec![
                SectionKind::OneLiner,
                SectionKind::Other("我加的章节".into()),
                SectionKind::Actions,
            ]
        );
        assert_eq!(doc.sections[1].heading, "我加的章节");
        assert_eq!(doc.sections[1].body, "手写内容");
    }

    #[test]
    fn empty_input_gives_empty_doc() {
        assert_eq!(parse(""), Doc { title: String::new(), sections: Vec::new() });
        assert_eq!(parse("\n\n   \n"), Doc { title: String::new(), sections: Vec::new() });
        // 只有 H1 的纪要
        assert_eq!(parse("# 只有标题\n"), Doc { title: "只有标题".into(), sections: Vec::new() });
    }

    /// 标题两侧的空格不影响识别（手工编辑很容易带出来）
    #[test]
    fn headings_tolerate_surrounding_spaces() {
        let md = "#   周会  \n\n##   一句话总结   \n  定了上线时间。  \n\n##   Action Items  \n| a |\n";
        let doc = parse(md);
        assert_eq!(doc.title, "周会");
        assert_eq!(doc.sections[0].heading, "一句话总结");
        assert_eq!(doc.sections[0].kind, SectionKind::OneLiner);
        assert_eq!(doc.sections[1].kind, SectionKind::Actions);
        assert_eq!(doc.sections[1].heading, "Action Items");
        assert_eq!(doc.sections[1].body, "| a |");
    }

    /// 桌面 v0.5.2 的真实产出（tests/fixtures）能直接解析
    #[test]
    fn parses_v052_fixture_minutes() {
        let zh = include_str!("../tests/fixtures/2026-10-28_1430_官网注册转化复盘/纪要.md");
        let en = include_str!("../tests/fixtures/2026-10-27_0930_Website_registration_post-mortem/纪要.md");
        assert_eq!(parse(zh).title, "官网注册转化率下降复盘会");
        let doc = parse(en);
        assert_eq!(doc.title, "Post-mortem on the Website Registration Conversion Drop");
        assert!(doc.sections.iter().any(|s| s.kind == SectionKind::OneLiner));
        assert!(doc.sections.iter().any(|s| s.kind == SectionKind::Actions));
        assert!(doc.sections.iter().any(|s| s.kind == SectionKind::RootCause));
    }
}
