# 07 · Battery-Friendly Background Indexing

Indexing a whole gallery is the heaviest thing finder+ does. The first on-device runs behaved badly:
the app pinned the CPU, the SoC heated to ~46 °C with the thermal governor throttling, and Android
killed the process — indexing never got past ~6% (1,124 / 19,657 work units) no matter how often it
was restarted.

There were **three independent causes**, all now fixed.

## 1. WorkManager's 10-minute limit (the fatal one)

A `CoroutineWorker` is force-stopped after ~10 minutes. Our drain loop tried to process all 19,657
units in one worker, so **every run was killed mid-flight** — visible on-device as
`ai.dusty.finderplus::timeout-reg` and `::anr` quota entries in `dumpsys jobscheduler`, and as four
`index_run` rows each frozen at a different `done_units`.

**Fix — bounded slices that self-reschedule.** `IndexOrchestrator.runSlice()` drains for a wall-clock
budget (`SLICE_BUDGET_MS` = 4 min, comfortably under the limit) and returns a `SliceOutcome`. If work
remains, `IndexWorker` queues the next slice via `enqueueUniqueWork(APPEND_OR_REPLACE)` with a
cool-down `initialDelay`. Slices never overlap, "Index now" stays a no-op while a chain is pending, and
each slice resumes from the ledger — so the index advances monotonically across as many slices as it
takes.

## 2. No duty cycle (the battery burner)

The loop ran flat out. Now `PowerPolicy` governs two knobs from thermal status, charge state and
battery level:

| Condition | Per-unit pause | Cool-down between slices |
|---|---|---|
| Charging, cool | 8 ms | 10 s |
| Discharging, ≥50%, cool | 30 ms | 40 s |
| Discharging, <50%, cool | 60 ms | 40 s |
| Thermal LIGHT | 40–90 ms | 60 s |
| Thermal MODERATE | 200 ms | 2 min |
| Thermal SEVERE | slice ends | 5 min |
| Battery ≤ 15%, unplugged | slice ends | 15 min |

The per-unit pause is the important one: it caps average CPU so the SoC never reaches the temperature
where the governor (and the OS killer) intervene. `Constraints.setRequiresBatteryNotLow(true)` stops a
burn from starting on a nearly-dead battery, and SEVERE thermal / low battery end the slice early and
report `RunStatus.PAUSED` — surfaced honestly in the widget and notification as
*"Paused 34% · cooling down, resumes automatically"*.

## 3. Wasted work per unit

Two real inefficiencies, both removed:

- **Progress accounting** ran `COUNT(*)` three times over `work_unit` (19,657 rows) **after every
  single unit** — roughly 60k row scans per unit, pure heat. Counters are now held in memory,
  recomputed once per slice, emitted at most every 1.2 s, and persisted every 5 s.
- **Each photo was JPEG-decoded twice** (once for labeling, once for OCR). A single-entry
  `DecodedImageCache` reuses the decode across an item's passes — they're claimed consecutively
  because the cheap passes share a priority tier. Roughly halves decode cost per photo.
- **FTS + profile rebuild** ran once per completed pass (3× per photo). Now it runs once per item,
  when `remainingTextPassesForItem() == 0`.

## Status reporting

Progress is published to two surfaces from one `IndexProgress` flow:

- **Notification** (the foreground service): title reflects phase (`Scanning…` / `Indexing your
  gallery` / `Indexing paused`), text is `1,240 / 19,657 · 6% · recognizing content`, plus a
  determinate progress bar and a **Stop** action wired to `IndexControlReceiver` (cooperative stop,
  then cancel the chain).
- **Widget**: same numbers, percentage and phase — e.g. `Indexing 2,009 / 19,665 · 10% · reading
  details`. Two changes were needed. (a) `IndexStatusListener` (bound in `:app`) pushes rate-limited
  Glance updates as the run progresses, because the widget previously only refreshed when tapped.
  (b) `provideGlance` reads counts from the **`index_run` row**, not the in-memory progress flow:
  slices run in separate worker invocations and the process may die between them, so process-local
  state went stale and the widget showed a count from a finished slice (788 while the real figure was
  1,649). Reading the DB — the design's single source of truth — makes the widget correct even after a
  process restart, lagging only by the 5 s count-persist interval.

## Expected behaviour on the S24+ (4,926 items / 19,657 units)

Cheap passes cost roughly 15–25 ms of compute per unit plus the policy pause. A full first index takes
on the order of 30–60 minutes of wall-clock across ~10–15 slices, at a fraction of the previous power
draw, and — crucially — it **finishes**, because no single worker outlives its window. Search is useful
long before the end: each item is fully text-searchable as soon as its metadata + label + OCR group
completes.


## Power source: plugged in vs charging

The governor keys every decision on **"is a cable attached"**, not **"is the battery gaining charge"**.
They are not the same thing, and conflating them cost hours of throughput on a real device.

Samsung's *Protect battery* caps the charge at 80%. Past that the phone reports
`BATTERY_STATUS_NOT_CHARGING` while the cable still powers the entire device. `BatteryManager.isCharging()`
returns false, so the governor concluded it was on battery and throttled hard — on a phone that had been
plugged in the whole time. Any charge limiter does this, and so does a battery sitting at 100%.

`PowerState.onExternalPower` is therefore derived from `EXTRA_PLUGGED` on the sticky
`ACTION_BATTERY_CHANGED` broadcast, which is the only reliable source for it — `BatteryManager` exposes
no "cable attached" property.

Consequences, all covered by `PowerDecisionTest`:

- A charge-capped phone throttles like a charging one (15 ms/unit), not like one on battery (40-80 ms).
- Low battery **never** stops work while plugged in. A limiter can hold the level anywhere, including
  below the 15% threshold, and that is not a drain.
- Heat is unaffected by any of this. SEVERE thermal still stops the slice on a cable, and the throttle
  still ramps with temperature when plugged in — external power removes the *battery* constraint, never
  the thermal one.

## Slice deadline reaches into a running pass

`PassContext.isStopRequested()` returns true for a user stop **or** an exhausted slice budget.

The drain loop checks the deadline before claiming a unit, but that is not sufficient on its own: one
transcription of a four-minute recording is many 30-second windows and can run far past the deadline —
long enough for JobScheduler to kill the worker. That is not a graceful stop. It burns one of only
**three permitted job timeouts per 24 hours** (`timeout-reg` in `dumpsys jobscheduler`), and after the
third the platform defers the app's work heavily.

Because the transcribe pass checkpoints per window, honouring the deadline costs nothing: it returns
STOPPED with its cursor intact and the next slice resumes exactly there.


## Aggressive on mains

The governor was too conservative and it cost real throughput. Revised policy, on external power only:

| thermal | per-unit pause | cool-down between slices | yields? |
|---|---|---|---|
| NONE | **0 ms** | 1 s | no |
| LIGHT | **0 ms** | 2 s | no |
| MODERATE | 10 ms | 5 s | no |
| SEVERE | 50 ms | 15 s | **no** (was: yield + 5 min) |
| CRITICAL | 800 ms | 60 s | yes |

The reasoning for not stopping at SEVERE: by then the SoC has **already** cut its own clocks. An
app-level sleep on top of hardware throttling buys wall-clock, not degrees — the hardware is the real
governor and it cannot be argued with. Stopping is reserved for CRITICAL, which precedes a thermal
shutdown, where losing the device mid-slice would cost more than the throughput gained.

**Off the cable nothing was relaxed.** SEVERE still yields, the per-unit pause still scales with battery
level, and cool-downs stay in minutes. `PowerDecisionTest` pins both halves, so relaxing the mains path
cannot quietly start draining someone's battery.
