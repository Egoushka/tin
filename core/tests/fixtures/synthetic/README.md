# Synthetic dumps

Written by hand from the formats of AOSP's `dumpsys` and `uiautomator` (Android 14–17), not captured from a phone.
Device check D1 (`uv run scripts/system-check.py --read`) replaces them with real dumps from the phone in
`../device/`; the parser tests then run on both.
