//! Acceptance checks K1–K10 from docs/specs/2026-11-15-screen-marks.md, off the phone: a fake backend answers the shell
//! programs `screen_marks` runs, and a fake approvals inbox records held calls.

mod common;

use common::{Inbox, call, text};
use serde_json::{Value, json};
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tin_core::backend::Backend;
use tin_core::marks::{self, Mark};
use tin_core::mcp::Server;
use tin_core::policy::Policy;

fn png(w: u32, h: u32) -> Vec<u8> {
    let mut out = Vec::new();
    let mut enc = png::Encoder::new(&mut out, w, h);
    enc.set_color(png::ColorType::Rgb);
    enc.set_depth(png::BitDepth::Eight);
    enc.write_header().unwrap().write_image_data(&vec![200u8; (w * h * 3) as usize]).unwrap();
    out
}

fn node(clickable: bool, text: &str, desc: &str, bounds: &str, password: bool) -> String {
    format!(
        "<node index=\"0\" text=\"{text}\" resource-id=\"\" class=\"android.widget.Button\" package=\"com.game\" content-desc=\"{desc}\" checkable=\"false\" checked=\"false\" clickable=\"{clickable}\" enabled=\"true\" focusable=\"true\" focused=\"false\" scrollable=\"false\" long-clickable=\"false\" password=\"{password}\" selected=\"false\" bounds=\"{bounds}\" />"
    )
}

fn tree(nodes: &[String]) -> String {
    format!("<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><hierarchy rotation=\"0\"><node index=\"0\" text=\"\" resource-id=\"\" class=\"android.widget.FrameLayout\" package=\"com.game\" content-desc=\"\" checkable=\"false\" checked=\"false\" clickable=\"false\" enabled=\"true\" focusable=\"false\" focused=\"false\" scrollable=\"false\" long-clickable=\"false\" password=\"false\" selected=\"false\" bounds=\"[0,0][1080,2400]\">{}</node></hierarchy>", nodes.concat())
}

fn four_buttons() -> String {
    tree(&[
        node(true, "Play", "", "[100,200][500,400]", false),
        node(true, "", "Settings", "[600,200][1000,400]", false),
        node(true, "", "", "[100,1000][300,1200]", false),
        node(false, "Just text", "", "[100,1500][900,1600]", false),
        node(true, "hunter2", "", "[100,1700][900,1800]", true),
    ])
}

#[derive(Clone)]
struct Phone {
    calls: Arc<Mutex<Vec<Vec<String>>>>,
    ui: Arc<Mutex<String>>,
    package: Arc<Mutex<&'static str>>,
    size: (u32, u32),
}

impl Phone {
    fn new(ui: String, size: (u32, u32)) -> Self {
        Self { calls: Default::default(), ui: Arc::new(Mutex::new(ui)), package: Arc::new(Mutex::new("com.game")), size }
    }
    fn ran(&self, program: &str) -> bool {
        self.calls.lock().unwrap().iter().any(|c| c[0] == program)
    }
    fn calls_of(&self, program: &str) -> Vec<Vec<String>> {
        self.calls.lock().unwrap().iter().filter(|c| c[0] == program).cloned().collect()
    }
}

impl Backend for Phone {
    fn list(&self) -> Result<Value, String> {
        Err("no AppFunctions on this fake".into())
    }
    fn execute(&self, _: &str, _: &str, _: &str) -> Result<String, String> {
        Err("no AppFunctions on this fake".into())
    }
    fn shell(&self, program: &str, args: &[String]) -> Result<String, String> {
        let mut call = vec![program.to_string()];
        call.extend(args.iter().cloned());
        self.calls.lock().unwrap().push(call);
        let first = args.first().map(String::as_str).unwrap_or("");
        Ok(match (program, first) {
            ("dumpsys", "activity") => {
                let p = *self.package.lock().unwrap();
                format!("    topResumedActivity=ActivityRecord{{5e6f7a8 u0 {p}/{p}.Main t123}}\n")
            }
            ("uiautomator", _) => format!("UI hierchary dumped to: {}\n", args.last().cloned().unwrap_or_default()),
            ("cat", _) => self.ui.lock().unwrap().clone(),
            ("screencap", _) => String::new(),
            ("base64", _) => marks::base64_encode(&png(self.size.0, self.size.1)),
            _ => String::new(),
        })
    }
}

const POLICY: &str = "[[allow]]\npackage = \"system\"\nfunction = \"screen_marks\"\n[[approval]]\npackage = \"system\"\nfunction = \"tap_mark\"\n";

fn ungated(phone: Phone) -> Server {
    Server::new(Box::new(phone), Policy::from_toml(POLICY).unwrap())
}

fn marks_json(r: &Value) -> Value {
    let t = r["content"].as_array().unwrap().iter().find(|c| c["type"] == "text").unwrap();
    serde_json::from_str(t["text"].as_str().unwrap()).unwrap()
}

// K1

#[test]
fn k1_clickable_nodes_become_numbered_marks() {
    let parsed = marks::parse_marks(&four_buttons()).unwrap();
    let m = &parsed.marks;
    assert_eq!(m.len(), 4, "three buttons and the password field; the plain text is not a mark");
    assert_eq!(m.iter().map(|m| m.n).collect::<Vec<_>>(), [1, 2, 3, 4]);
    assert_eq!(m[0].bounds, [100, 200, 500, 400]);
    assert_eq!(m[0].center(), (300, 300));
    assert_eq!(m[0].label.as_deref(), Some("Play"));
    assert_eq!(m[1].label.as_deref(), Some("Settings"));
    assert_eq!(m[2].label, None, "an unlabelled button is still a mark");
    assert_eq!(m[3].label, None, "a password field's text never leaves");
    assert!(m.iter().all(|m| m.kind == "node"));
    assert_eq!(parsed.package.as_deref(), Some("com.game"));
}

// K2

#[test]
fn k2_sparse_tree_gets_a_grid_and_a_full_one_does_not() {
    let mut sparse = marks::parse_marks(&tree(&[node(true, "Only", "", "[0,0][100,100]", false)])).unwrap().marks;
    assert!(marks::is_sparse(&sparse));
    marks::add_grid(&mut sparse, 1080, 2400);
    assert_eq!(sparse.len(), 1 + 32);
    let grid: Vec<&Mark> = sparse.iter().filter(|m| m.kind == "grid").collect();
    assert_eq!(grid.len(), 32);
    assert_eq!(grid[0].n, 2);
    assert_eq!(grid[0].bounds, [0, 0, 270, 300]);
    assert_eq!(grid[31].bounds, [810, 2100, 1080, 2400]);
    let full = marks::parse_marks(&four_buttons()).unwrap().marks;
    assert!(!marks::is_sparse(&full));
}

// K3, K4

#[test]
fn k3_render_draws_boxes_and_keeps_the_rest() {
    let original = png(200, 400);
    let marks = vec![Mark { n: 1, bounds: [20, 40, 120, 140], label: None, kind: "node" }];
    let (out, factor) = marks::render(&original, &marks).unwrap();
    assert_eq!(factor, 1);
    let decode = |b: &[u8]| {
        let mut r = png::Decoder::new(std::io::Cursor::new(b)).read_info().unwrap();
        let mut buf = vec![0; r.output_buffer_size().unwrap()];
        let info = r.next_frame(&mut buf).unwrap();
        (info.width, info.height, buf)
    };
    let (w, h, px) = decode(&out);
    assert_eq!((w, h), (200, 400));
    let at = |x: usize, y: usize| px[(y * 200 + x) * 3..(y * 200 + x) * 3 + 3].to_vec();
    assert_ne!(at(70, 40), vec![200, 200, 200], "top edge of the box");
    assert_eq!(at(180, 380), vec![200, 200, 200], "far from every box");
}

#[test]
fn k4_big_screenshots_shrink_and_json_stays_full_resolution() {
    let (out, factor) = marks::render(&png(1080, 2400), &[Mark { n: 1, bounds: [100, 200, 500, 400], label: None, kind: "node" }]).unwrap();
    assert_eq!(factor, 2);
    let r = png::Decoder::new(std::io::Cursor::new(&out[..])).read_info().unwrap();
    let info = r.info();
    assert_eq!((info.width, info.height), (540, 1200));
    let s = ungated(Phone::new(four_buttons(), (1080, 2400)));
    let m = marks_json(&call(&s, "system__screen_marks", json!({})));
    assert_eq!(m["marks"][0]["x"], 300);
    assert_eq!(m["marks"][0]["y"], 300);
    assert_eq!(m["screen"], json!({ "width": 1080, "height": 2400 }));
}

// K5

#[test]
fn k5_result_has_an_image_and_the_marks() {
    let s = ungated(Phone::new(four_buttons(), (400, 800)));
    let r = call(&s, "system__screen_marks", json!({}));
    assert_eq!(r["isError"], false, "{r}");
    let img = r["content"].as_array().unwrap().iter().find(|c| c["type"] == "image").unwrap();
    assert_eq!(img["mimeType"], "image/png");
    let bytes = marks::base64_decode(img["data"].as_str().unwrap()).unwrap();
    assert_eq!(&bytes[1..4], b"PNG");
    let m = marks_json(&r);
    assert!(m["marks_id"].as_u64().is_some());
    assert_eq!(m["sparse"], false);
    assert_eq!(m["marks"].as_array().unwrap().len(), 4);
    assert!(!r.to_string().contains("hunter2"));
}

// K6

#[test]
fn k6_banking_app_in_front_takes_no_screenshot() {
    let phone = Phone::new(four_buttons(), (400, 800));
    *phone.package.lock().unwrap() = "com.ftband.mono";
    let s = ungated(phone.clone());
    let r = call(&s, "system__screen_marks", json!({}));
    assert_eq!(r["isError"], true);
    assert!(text(&r).contains("banking app"));
    assert!(!phone.ran("screencap"));
}

// K7–K9

fn gated(phone: &Phone) -> (Arc<Server>, Inbox) {
    common::gated_server(Box::new(phone.clone()), POLICY, Duration::from_secs(5), Duration::from_secs(60))
}

#[test]
fn k7_tap_mark_taps_the_marks_centre_after_approval() {
    let phone = Phone::new(four_buttons(), (400, 800));
    let (s, inbox) = gated(&phone);
    let id = marks_json(&call(&s, "system__screen_marks", json!({})))["marks_id"].clone();
    let (item, caller) = common::held(&s, &inbox, "system__tap_mark", json!({ "marks_id": id, "mark": 2 }));
    let title = inbox.nth(0)["title"].as_str().unwrap().to_string();
    assert!(title.contains("mark 2") && title.contains("Settings") && title.contains("(800, 300)"), "{title}");
    assert!(!phone.ran("input"), "nothing runs before the tap");
    s.gate().unwrap().decide(&common::signed_at(&item, "run", "2026-11-01T12:00:00.000Z"));
    let r = caller.join().unwrap();
    assert_eq!(r["isError"], false, "{r}");
    assert_eq!(phone.calls_of("input"), [vec!["input", "tap", "800", "300"]]);
}

#[test]
fn k8_unknown_marks_fail_before_an_approval_item() {
    let phone = Phone::new(four_buttons(), (400, 800));
    let (s, inbox) = gated(&phone);
    let none = call(&s, "system__tap_mark", json!({ "marks_id": 9_999_999_999u64, "mark": 1 }));
    assert_eq!(none["isError"], true);
    let id = marks_json(&call(&s, "system__screen_marks", json!({})))["marks_id"].as_u64().unwrap();
    for args in [json!({ "marks_id": 9_999_999_998u64, "mark": 1 }), json!({ "marks_id": id, "mark": 99 }), json!({ "marks_id": id, "mark": 0 })] {
        let r = call(&s, "system__tap_mark", args);
        assert_eq!(r["isError"], true, "{r}");
    }
    assert!(inbox.items.lock().unwrap().is_empty());
    assert!(!phone.ran("input"));
}

#[test]
fn k9_tap_mark_refuses_when_the_app_changed() {
    let phone = Phone::new(four_buttons(), (400, 800));
    let (s, inbox) = gated(&phone);
    let id = marks_json(&call(&s, "system__screen_marks", json!({})))["marks_id"].clone();
    let (item, caller) = common::held(&s, &inbox, "system__tap_mark", json!({ "marks_id": id, "mark": 1 }));
    *phone.package.lock().unwrap() = "com.other";
    s.gate().unwrap().decide(&common::signed_at(&item, "run", "2026-11-01T12:00:00.000Z"));
    let r = caller.join().unwrap();
    assert_eq!(r["isError"], true);
    assert!(!phone.ran("input"));
}

// K10

#[test]
fn k10_the_screenshot_file_is_removed_even_when_the_read_fails() {
    let phone = Phone::new(four_buttons(), (400, 800));
    let s = ungated(phone.clone());
    call(&s, "system__screen_marks", json!({}));
    let shot = phone.calls_of("screencap")[0].last().unwrap().clone();
    let rm: Vec<Vec<String>> = phone.calls_of("rm");
    assert!(rm.iter().any(|c| c.contains(&shot)), "{rm:?}");

    let bad = Phone::new("not xml".into(), (400, 800));
    let s = ungated(bad.clone());
    assert_eq!(call(&s, "system__screen_marks", json!({}))["isError"], true);
    let shot = bad.calls_of("screencap").first().map(|c| c.last().unwrap().clone());
    if let Some(shot) = shot {
        assert!(bad.calls_of("rm").iter().any(|c| c.contains(&shot)));
    }
}
