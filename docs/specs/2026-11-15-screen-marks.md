# Spec: numbered screenshot marks for screens without a usable tree (tin/P02)

Date: 2026-11-15. Status: approved wave 2 (proposal tin/P02, borrows android-use/003 and mobilerun/001).

## Goal

When an app gives `screen_text` nothing to work with (a game, a canvas, a custom-drawn view), the agent gets a
screenshot with numbered boxes on it and taps by number. Value hypothesis: covers apps with no AppFunctions and no
good accessibility tree without the agent guessing raw pixels. Cheapest test: three apps whose tree is empty (owner
action, needs the phone); the off-phone checks below cover the logic.

## Behaviour

1. New read verb `system__screen_marks`. It dumps the UI (`uiautomator`), takes a screenshot (`screencap -p` into
   `/data/local/tmp/tin/`, read back as base64, removed after), and returns an `image/png` content item plus a JSON
   text item.
2. **Marks.** Every node that is clickable, long-clickable or checkable and has a non-empty box becomes a mark,
   numbered from 1 in tree order, capped at 60. The JSON lists `n`, `bounds` `[l,t,r,b]`, the centre `x`,`y`, the
   `label` (text or content description, clipped to 60; never a password field's text) and `kind: "node"`.
3. **Grid fallback.** When fewer than 3 node marks exist the tree is sparse: a 4 × 8 grid of numbered cells over the
   whole screen is added (`kind: "grid"`, numbered after the nodes) and the JSON says `"sparse": true`.
4. **Image.** Boxes and number labels are drawn on the screenshot. A screenshot whose long side is over 1280 px is
   shrunk by an integer factor so it is at most that; boxes are scaled with it. Coordinates in the JSON stay in
   full-resolution screen pixels, the ones `system__tap` takes.
5. New write verb `system__tap_mark {marks_id, mark}`: waits for an approvals tap like every write, then taps the
   centre of that mark. `marks_id` is an id a `screen_marks` call returned (the last 8 are kept, each for 120 s). An unknown or
   expired id, a mark number that does not exist, or no `screen_marks` call yet is an error before any approval item
   exists. The id pins the coordinates the owner approved: a later `screen_marks` call cannot redirect a held tap. The approvals title names the
   mark, its label and its coordinates. Before it taps, the foreground app must still be the one the marks were taken
   in; otherwise it refuses and taps nothing.
6. **Money.** `screen_marks` is refused while a banking app is in front or in the dump, exactly like `screen_text`,
   and takes no screenshot then. The marks expire after 120 s.
7. The screenshot is as private as `screen_text`: it shows what is on screen. A secure window (`FLAG_SECURE`)
   comes back black; that is the platform's guard and tin does not bypass it.

## Acceptance checks

Run off the phone (`cargo test`, CI). Tests are in `core/tests/marks.rs`.

| # | Check | Pass when |
|---|---|---|
| K1 | node marks | clickable nodes (also unlabelled ones) get numbers 1.. with bounds, centre, label; a plain text node and a password's text do not appear |
| K2 | grid | a tree with 0 clickable nodes gives 32 grid marks and `sparse: true`; a tree with 3 or more gives none |
| K3 | render | the output is a valid PNG; pixels on a box border change, pixels far from any box stay |
| K4 | shrink | a 1080×2400 screenshot comes back with its long side ≤ 1280; the JSON centres are still full-resolution |
| K5 | tool result | `screen_marks` returns an image item (`image/png`) and a text item with `marks_id` |
| K6 | banking | a banking app in front: refused, `screencap` never ran |
| K7 | tap by number | held, the item title names the mark and its coordinates; after approval `input tap x y` runs with the mark's centre |
| K8 | unknown | unknown `marks_id`, mark out of range, or no marks yet: an error and no approvals item |
| K9 | app changed | foreground differs from the marks' app at run time: refused, no `input` call |
| K10 | cleanup | the screenshot file is removed after a read, also when the read fails |
