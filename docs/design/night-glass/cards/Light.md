# Light over time

The light ladder (calm, now, needs you, broken, stale) says what each light means. This card says how it moves: one morning on the Needs-you card and the homelab tile, then every transition with its trigger and timing.

| Transition | Trigger | Duration (Material 3 token) | Curve |
|---|---|---|---|
| Calm → needs you | an item enters the queue | 300 ms (`medium2`) | emphasized decelerate |
| Needs you → calm | the last item is done, on any device | 500 ms (`long2`) | emphasized accelerate |
| Calm → now | an event's start time | 300 ms (`medium2`) | standard |
| Any → broken | two health checks fail in a row | 200 ms (`short4`) | standard |
| Any → stale | three refreshes missed | 1000 ms (`extraLong4`) | standard |
| Stale → fresh | data arrives | 100 ms (`short2`) | standard |

**Why these rules.** Light comes faster than it goes, so what arrived is caught and what cleared is still seen leaving. Broken waits for two failed checks so a single dropped packet never flashes red. Stale is defined by missed refreshes, not minutes, so it scales with each source's poll interval. Home never vibrates for a light change; the notification already did.

The durations are starting values to calibrate on the phone. With reduced motion every transition is instant.
