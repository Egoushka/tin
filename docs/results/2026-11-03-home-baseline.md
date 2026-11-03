# Home baseline, 2026-11-03

## 01:09: Pixel 8a, Android 17

- Build: debug 0.1.0, installed 2026-11-03 00:59:06; checkout 65922f3 (the installed build is what was measured).
- Start: 20 launches of `am start -S -W` (LaunchState WARM x20). `TotalTime` ms: min 1350, **median 1398.5**, **p95 1471**, max 1472.
- All launches (ms): 1378 1399 1472 1412 1398 1372 1370 1471 1386 1401 1399 1367 1390 1400 1408 1350 1405 1395 1452 1387
- Drawer: **15.25% janky** of 472 frames over 10 opens (`scripts/home-jank.sh`).

```
Total frames rendered: 472
Janky frames: 72 (15.25%)
Janky frames (legacy): 87 (18.43%)
50th percentile: 11ms
90th percentile: 89ms
95th percentile: 133ms
99th percentile: 150ms
Number Missed Vsync: 26
Number High input latency: 569
Number Slow UI thread: 72
Number Slow bitmap uploads: 0
Number Slow issue draw commands: 1
50th gpu percentile: 4ms
90th gpu percentile: 5ms
95th gpu percentile: 5ms
99th gpu percentile: 6ms
```
