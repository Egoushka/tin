//! Numbered marks on a screenshot, for screens whose accessibility tree says too little (games, canvases). The marks
//! come from clickable nodes in the `uiautomator` dump; a screen with fewer than three gets a numbered grid instead.
//! The picture is drawn here (boxes and digits on the decoded PNG), so the agent taps by number, not by pixel guess.

use serde_json::{Value, json};
use std::sync::Mutex;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{Duration, Instant};

const MAX_MARKS: usize = 60;
const MAX_LABEL: usize = 60;
const GRID_COLS: i64 = 4;
const GRID_ROWS: i64 = 8;
/// A screenshot whose long side is over this is shrunk by an integer factor.
const MAX_SIDE: u32 = 1280;
/// How many `screen_marks` results `tap_mark` can still use, and for how long.
const KEEP: usize = 8;
const EXPIRES: Duration = Duration::from_secs(120);

#[derive(Debug, Clone, PartialEq)]
pub struct Mark {
    pub n: usize,
    /// `[left, top, right, bottom]` in full-resolution screen pixels.
    pub bounds: [i64; 4],
    pub label: Option<String>,
    /// `node` (a clickable view) or `grid` (a cell of the fallback grid).
    pub kind: &'static str,
}

impl Mark {
    pub fn center(&self) -> (i64, i64) {
        ((self.bounds[0] + self.bounds[2]) / 2, (self.bounds[1] + self.bounds[3]) / 2)
    }

    pub fn to_json(&self) -> Value {
        let (x, y) = self.center();
        json!({ "n": self.n, "kind": self.kind, "bounds": self.bounds, "x": x, "y": y, "label": self.label })
    }
}

pub struct Parsed {
    pub package: Option<String>,
    pub packages: Vec<String>,
    pub marks: Vec<Mark>,
}

/// Clickable, long-clickable and checkable nodes with a box, in tree order. A password field's text never becomes a label.
pub fn parse_marks(xml: &str) -> Result<Parsed, String> {
    let doc = roxmltree::Document::parse(xml).map_err(|e| format!("uiautomator XML: {e}"))?;
    let mut packages: Vec<String> = Vec::new();
    let mut marks = Vec::new();
    for n in doc.descendants().filter(|n| n.has_tag_name("node")) {
        let attr = |k: &str| n.attribute(k).unwrap_or("");
        let pkg = attr("package");
        if !pkg.is_empty() && !packages.iter().any(|p| p == pkg) {
            packages.push(pkg.to_string());
        }
        let on = |k: &str| attr(k) == "true";
        if !(on("clickable") || on("long-clickable") || on("checkable")) || marks.len() >= MAX_MARKS {
            continue;
        }
        let b: Vec<i64> = attr("bounds").split(|c: char| !c.is_ascii_digit() && c != '-').filter_map(|s| s.parse().ok()).collect();
        let [l, t, r, bt] = b[..] else { continue };
        if r <= l || bt <= t {
            continue;
        }
        let label = if on("password") {
            None
        } else {
            Some(if attr("text").is_empty() { attr("content-desc") } else { attr("text") }).filter(|s| !s.is_empty()).map(clip)
        };
        marks.push(Mark { n: marks.len() + 1, bounds: [l, t, r, bt], label, kind: "node" });
    }
    Ok(Parsed { package: packages.first().cloned(), packages, marks })
}

fn clip(s: &str) -> String {
    if s.chars().count() <= MAX_LABEL { s.to_string() } else { format!("{}…", s.chars().take(MAX_LABEL).collect::<String>()) }
}

/// Fewer than three clickable nodes: the tree is not enough to act on.
pub fn is_sparse(marks: &[Mark]) -> bool {
    marks.iter().filter(|m| m.kind == "node").count() < 3
}

/// A 4 × 8 grid over the screen, numbered after the marks already there.
pub fn add_grid(marks: &mut Vec<Mark>, width: i64, height: i64) {
    for r in 0..GRID_ROWS {
        for c in 0..GRID_COLS {
            let n = marks.len() + 1;
            let bounds = [c * width / GRID_COLS, r * height / GRID_ROWS, (c + 1) * width / GRID_COLS, (r + 1) * height / GRID_ROWS];
            marks.push(Mark { n, bounds, label: None, kind: "grid" });
        }
    }
}

// --- base64 (no crate: `screencap` output comes back through `base64` on the phone) ---

const B64: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

pub fn base64_encode(data: &[u8]) -> String {
    let mut out = String::with_capacity(data.len().div_ceil(3) * 4);
    for chunk in data.chunks(3) {
        let n = (chunk[0] as u32) << 16 | (*chunk.get(1).unwrap_or(&0) as u32) << 8 | *chunk.get(2).unwrap_or(&0) as u32;
        for i in 0..4 {
            if i <= chunk.len() {
                out.push(B64[(n >> (18 - 6 * i) & 63) as usize] as char);
            } else {
                out.push('=');
            }
        }
    }
    out
}

pub fn base64_decode(s: &str) -> Result<Vec<u8>, String> {
    let mut out = Vec::with_capacity(s.len() / 4 * 3);
    let (mut acc, mut bits) = (0u32, 0);
    for b in s.bytes().filter(|b| !b.is_ascii_whitespace() && *b != b'=') {
        let v = B64.iter().position(|c| *c == b).ok_or("base64: bad character")? as u32;
        acc = acc << 6 | v;
        bits += 6;
        if bits >= 8 {
            bits -= 8;
            out.push((acc >> bits) as u8);
            acc &= (1 << bits) - 1;
        }
    }
    Ok(out)
}

// --- the picture ---

/// Width and height of a PNG.
pub fn png_size(data: &[u8]) -> Result<(u32, u32), String> {
    let r = png::Decoder::new(std::io::Cursor::new(data)).read_info().map_err(|e| format!("screenshot: {e}"))?;
    Ok((r.info().width, r.info().height))
}

struct Rgb {
    w: usize,
    h: usize,
    px: Vec<u8>,
}

fn decode(data: &[u8]) -> Result<Rgb, String> {
    let mut r = png::Decoder::new(std::io::Cursor::new(data)).read_info().map_err(|e| format!("screenshot: {e}"))?;
    let mut buf = vec![0; r.output_buffer_size().ok_or("screenshot: too large")?];
    let info = r.next_frame(&mut buf).map_err(|e| format!("screenshot: {e}"))?;
    if info.bit_depth != png::BitDepth::Eight {
        return Err("screenshot: only 8-bit PNGs are supported".into());
    }
    let step = match info.color_type {
        png::ColorType::Rgb => 3,
        png::ColorType::Rgba => 4,
        png::ColorType::Grayscale => 1,
        png::ColorType::GrayscaleAlpha => 2,
        png::ColorType::Indexed => return Err("screenshot: palette PNGs are not supported".into()),
    };
    let gray = step <= 2;
    let px = buf[..info.buffer_size()].chunks(step).flat_map(|p| if gray { [p[0]; 3] } else { [p[0], p[1], p[2]] }).collect();
    Ok(Rgb { w: info.width as usize, h: info.height as usize, px })
}

fn shrink(img: Rgb, f: usize) -> Rgb {
    if f == 1 {
        return img;
    }
    let (w, h) = (img.w / f, img.h / f);
    let mut px = Vec::with_capacity(w * h * 3);
    for y in 0..h {
        for x in 0..w {
            for c in 0..3 {
                let sum: usize = (0..f).flat_map(|dy| (0..f).map(move |dx| (dy, dx))).map(|(dy, dx)| img.px[((y * f + dy) * img.w + x * f + dx) * 3 + c] as usize).sum();
                px.push((sum / (f * f)) as u8);
            }
        }
    }
    Rgb { w, h, px }
}

const COLORS: [[u8; 3]; 6] = [[230, 25, 75], [0, 130, 200], [60, 180, 75], [245, 130, 48], [145, 30, 180], [0, 128, 128]];

/// 3 × 5 digits, one row per byte, the three low bits left to right.
const DIGITS: [[u8; 5]; 10] = [
    [7, 5, 5, 5, 7],
    [2, 6, 2, 2, 7],
    [7, 1, 7, 4, 7],
    [7, 1, 7, 1, 7],
    [5, 5, 7, 1, 1],
    [7, 4, 7, 1, 7],
    [7, 4, 7, 5, 7],
    [7, 1, 1, 2, 2],
    [7, 5, 7, 5, 7],
    [7, 5, 7, 1, 7],
];

impl Rgb {
    fn set(&mut self, x: i64, y: i64, c: [u8; 3]) {
        if x >= 0 && y >= 0 && (x as usize) < self.w && (y as usize) < self.h {
            let i = (y as usize * self.w + x as usize) * 3;
            self.px[i..i + 3].copy_from_slice(&c);
        }
    }

    fn fill(&mut self, l: i64, t: i64, r: i64, b: i64, c: [u8; 3]) {
        for y in t..b {
            for x in l..r {
                self.set(x, y, c);
            }
        }
    }
}

/// Draws every mark's box and number on the screenshot; returns the PNG and the shrink factor (1 when it fits).
pub fn render(png_bytes: &[u8], marks: &[Mark]) -> Result<(Vec<u8>, u32), String> {
    let img = decode(png_bytes)?;
    let factor = (img.w.max(img.h) as u32).div_ceil(MAX_SIDE).max(1);
    let mut img = shrink(img, factor as usize);
    let f = factor as i64;
    for m in marks {
        let color = COLORS[m.n % COLORS.len()];
        let [l, t, r, b] = m.bounds.map(|v| v / f);
        for k in 0..2 {
            img.fill(l, t + k, r, t + k + 1, color);
            img.fill(l, b - 1 - k, r, b - k, color);
            img.fill(l + k, t, l + k + 1, b, color);
            img.fill(r - 1 - k, t, r - k, b, color);
        }
        let digits: Vec<usize> = m.n.to_string().bytes().map(|d| (d - b'0') as usize).collect();
        let s = 4;
        let (lw, lh) = (digits.len() as i64 * 4 * s + s, 5 * s + 2 * s);
        img.fill(l, t, l + lw, t + lh, color);
        for (i, d) in digits.iter().enumerate() {
            for (row, bits) in DIGITS[*d].iter().enumerate() {
                for col in 0..3 {
                    if bits >> (2 - col) & 1 == 1 {
                        let (x, y) = (l + s + i as i64 * 4 * s + col * s, t + s + row as i64 * s);
                        img.fill(x, y, x + s, y + s, [255, 255, 255]);
                    }
                }
            }
        }
    }
    let mut out = Vec::new();
    let mut enc = png::Encoder::new(&mut out, img.w as u32, img.h as u32);
    enc.set_color(png::ColorType::Rgb);
    enc.set_depth(png::BitDepth::Eight);
    enc.write_header().and_then(|mut w| w.write_image_data(&img.px)).map_err(|e| format!("screenshot: {e}"))?;
    Ok((out, factor))
}

// --- what `tap_mark` may tap ---

struct Saved {
    id: u64,
    package: String,
    marks: Vec<Mark>,
    at: Instant,
}

static SAVED: Mutex<Vec<Saved>> = Mutex::new(Vec::new());
static NEXT: AtomicU64 = AtomicU64::new(1);

/// Keeps a `screen_marks` result so `tap_mark` can resolve a number; returns its `marks_id`.
pub fn remember(package: &str, marks: &[Mark]) -> u64 {
    let id = NEXT.fetch_add(1, Ordering::Relaxed);
    let mut saved = SAVED.lock().unwrap();
    saved.push(Saved { id, package: package.to_string(), marks: marks.to_vec(), at: Instant::now() });
    let drop = saved.len().saturating_sub(KEEP);
    saved.drain(..drop);
    id
}

/// The mark `mark` of result `marks_id`, and the app it was taken in.
pub fn lookup(marks_id: u64, mark: u64) -> Result<(Mark, String), String> {
    let saved = SAVED.lock().unwrap();
    let s = saved.iter().find(|s| s.id == marks_id).ok_or("marks_id: no such result (call system__screen_marks first; the last 8 are kept)")?;
    if s.at.elapsed() > EXPIRES {
        return Err("marks_id: these marks are older than 120 s; call system__screen_marks again".into());
    }
    let m = s.marks.iter().find(|m| m.n as u64 == mark).ok_or_else(|| format!("mark: no mark {mark} (1 to {})", s.marks.len()))?;
    Ok((m.clone(), s.package.clone()))
}
