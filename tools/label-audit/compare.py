#!/usr/bin/env python3
"""
Check that a labelling change fixed what it targeted without losing good labels.

Re-checks every detection audit.py recorded in the baseline against the state after the change.
Item ids are MediaStore ids, so they survive a reset and re-index.

  python3 tools/label-audit/compare.py --baseline base/ --after after/finder.db [--after-metrics after/metrics.json]

Exit code 1 when a regression gate fails, so it can sit in a script like a test.
"""
import argparse, collections, json, sqlite3, sys
from pathlib import Path

OBJECT, SUGGESTED = 1, 8


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--baseline", required=True, help="directory with samples.json + metrics.json from audit.py")
    ap.add_argument("--after", help="finder.db pulled after the change (fully re-indexed items only)")
    ap.add_argument("--after-audit", help="label-probe.txt from PROBE_LABELS '*' run with the changed pipeline")
    ap.add_argument("--after-metrics", help="metrics.json from audit.py run on the after state")
    ap.add_argument("--min-kept", type=float, default=0.85, help="share of unflagged labels that must survive")
    ap.add_argument("--max-suspect-left", type=float, default=0.25, help="share of suspect labels allowed to remain asserted")
    a = ap.parse_args()

    base = Path(a.baseline)
    samples = json.loads((base / "samples.json").read_text())
    tags = collections.defaultdict(dict)
    if a.after_audit:
        # The pipeline re-run directly: asserted unless the box is the whole frame (held for a vote).
        sys.path.insert(0, str(Path(__file__).parent))
        from audit import parse_audit, asserted, WHOLE_FRAME
        after = parse_audit(a.after_audit)
        done = set(after)
        for i, it in after.items():
            for d in it["asis"]:
                if asserted(d):
                    key = (SUGGESTED if d["area"] >= WHOLE_FRAME else OBJECT, d["label"])
                    if (OBJECT, d["label"]) not in tags[i]: tags[i][key] = True
    else:
        c = sqlite3.connect(a.after)
        done = {r[0] for r in c.execute("select item_id from work_unit where pass = 7 and state = 3")}  # OBJECTS completed
        for i, s, l in c.execute("select item_id, source, label from tag where source in (1, 8, 10)"):
            tags[i][(OBJECT if s == 1 else SUGGESTED, l)] = True

    checked = [s for s in samples if s["item"] in done]
    print(f"{len(checked)} of {len(samples)} baseline detections are on items re-indexed since the change")
    if not checked:
        print("nothing to compare yet — let the index run"); return 0

    def outcome(s):
        t = tags.get(s["item"], {})
        if (OBJECT, s["label"]) in t: return "asserted"
        if (SUGGESTED, s["label"]) in t: return "asked"
        return "gone"

    by_reason = collections.defaultdict(collections.Counter)
    suspects = collections.Counter(); clean = collections.Counter()
    for s in checked:
        o = outcome(s)
        (suspects if s["suspect"] else clean)[o] += 1
        for r in s["reasons"] or ["clean"]:
            by_reason[r][o] += 1

    def share(cnt, key): return cnt[key] / max(1, sum(cnt.values()))
    print(f"\nconfirmed-bug detections: {dict(suspects)}  → still asserted {share(suspects, 'asserted'):.0%}")
    print(f"unflagged detections: {dict(clean)}  → kept {share(clean, 'asserted') + share(clean, 'asked'):.0%}")
    print("\nby baseline reason (asserted / asked / gone):")
    for r, cnt in sorted(by_reason.items()):
        print(f"  {r:<26} {cnt['asserted']:>4} / {cnt['asked']:>4} / {cnt['gone']:>4}")

    print("\nsuspect labels still asserted (first 15):")
    for s in [s for s in checked if s["suspect"] and outcome(s) == "asserted"][:15]:
        print(f"  {s['label']:<14} {s['conf']:.2f} {s['folder'][:14]:<14} {s['name'][:34]:<34} {','.join(s['reasons'])}")

    if a.after_metrics:
        before = json.loads((base / "metrics.json").read_text()); after = json.loads(Path(a.after_metrics).read_text())
        print("\nmetric                                before → after")
        for k in ("people_labelled_more_than_once", "screen_captures_with_object_labels", "suspect_detections"):
            print(f"  {k:<36} {before.get(k)} → {after.get(k)}")

    kept = share(clean, "asserted") + share(clean, "asked")
    left = share(suspects, "asserted")
    ok = kept >= a.min_kept and left <= a.max_suspect_left
    print(f"\nGATE kept-unflagged {kept:.0%} (≥ {a.min_kept:.0%}) · suspect-still-asserted {left:.0%} (≤ {a.max_suspect_left:.0%}) → {'PASS' if ok else 'FAIL'}")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
