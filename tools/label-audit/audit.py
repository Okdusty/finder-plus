#!/usr/bin/env python3
"""
Label audit: measure the indexer's labels so a labelling change can be judged against a frozen
baseline instead of by eye.

Inputs (all pulled from the phone; none of it belongs in the repository):
  --db           finder.db (+ -wal/-shm beside it) from  run-as <pkg> cat databases/finder.db*
  --audit        label-probe.txt from the PROBE_LABELS broadcast with  --es labels '*'
  --orientation  `content query --uri content://media/external/images/media --projection _id:orientation`

Writes <out>/metrics.json (aggregate numbers) and <out>/samples.json (every flagged detection, the
regression set compare.py re-checks after a fix) and prints a readable report.

  python3 tools/label-audit/audit.py --db base/finder.db --audit base/label-probe.txt \
      --orientation base/mediastore_orientation.txt --out base/
"""
import argparse, collections, json, re, sqlite3, sys
from pathlib import Path

SOURCES = {0: "LABEL", 1: "OBJECT", 2: "OCR_KEYWORD", 3: "CATEGORY", 4: "USER", 5: "VLM", 6: "LEARNED", 7: "CONCEPT", 8: "SUGGESTED"}
PEOPLE = {"person", "one person", "two people", "group of people", "portrait"}
THRESHOLD = 0.40          # production cut for an asserted object label
WHOLE_FRAME = 0.90        # box covers ≥ 90 % of the image: "the entire photo is X"
SPECK = 0.02              # box under 2 % of the image (~10×10 px at the detector's 416 px input)


def asserted(d):
    """Detector boxes count from the production threshold; a describe model reports only what it names."""
    return d["obj"] is None or d["conf"] >= THRESHOLD


def is_screen_capture(name, folder):
    n, f = (name or "").lower(), (folder or "").lower()
    return n.startswith(("screenshot", "screen_recording", "screen-recording", "screenrecord")) or \
        "screenshot" in f or "screen record" in f


def parse_audit(path):
    items, cur, mode = {}, None, None
    item_re = re.compile(r'ITEM (\d+) "(.*)" folder=(.*?) (photo|video-thumbnail) stored=(\S+) decoded=(\S+) exif=(\d+)')
    # Detector probes report objectness × class probability and the classes weighed; describe-model
    # probes report one confidence and the doubts the review raised. Both share the rest of the line.
    det_re = re.compile(r'(LABEL|below) (.+?)\s+conf ([\d.]+)(?: = obj ([\d.]+) × class ([\d.]+))? · box \[([\d.,]+)\] · ([\d.]+)% of image(?: · (?:weighed|doubts): (.*))?')
    for line in open(path, encoding="utf-8"):
        if line.startswith("ITEM "):
            m = item_re.match(line)
            cur = dict(id=int(m[1]), name=m[2], folder=m[3], kind=m[4], exif=int(m[7]), asis=[], upright=[], has_upright=False)
            items[cur["id"]] = cur
        elif "[as indexed]" in line: mode = "asis"
        elif "[upright" in line: mode = "upright"; cur["has_upright"] = True
        elif cur and line.strip().startswith(("LABEL", "below")):
            m = det_re.search(line)
            if m:
                cur[mode].append(dict(label=m[2].strip(), conf=float(m[3]),
                                      obj=float(m[4]) if m[4] else None, cls=float(m[5]) if m[5] else None,
                                      box=[float(x) for x in m[6].split(",")], area=float(m[7]) / 100,
                                      alts=(m[8] or "").strip()))
    return items


def iou(a, b):
    ix = max(0.0, min(a[2], b[2]) - max(a[0], b[0])); iy = max(0.0, min(a[3], b[3]) - max(a[1], b[1]))
    inter = ix * iy
    union = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter
    return inter / union if union > 0 else 0.0


def parse_orientation(path):
    out = {}
    for line in open(path, encoding="utf-8", errors="replace"):
        m = re.search(r"_id=(\d+), orientation=(\d*)", line)
        if m: out[int(m[1])] = int(m[2]) if m[2] else None
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--db", required=True); ap.add_argument("--audit"); ap.add_argument("--orientation")
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    out = Path(a.out); out.mkdir(parents=True, exist_ok=True)
    c = sqlite3.connect(a.db); q = lambda s, p=(): c.execute(s, p).fetchall()
    M = {}

    items = {r[0]: dict(name=r[1], folder=r[2], kind=r[3]) for r in q("select id, display_name, bucket_name, kind from media_item")}
    M["items"] = len(items)
    M["items_by_kind"] = dict(collections.Counter({0: "photo", 1: "video", 2: "audio"}[v["kind"]] for v in items.values()))
    M["tags_by_source"] = {SOURCES.get(s, s): n for s, n in q("select source, count(*) from tag group by source")}

    obj = q("select item_id, label, confidence from tag where source = 1")
    per_label = collections.defaultdict(list)
    for i, l, cf in obj: per_label[l].append(cf)
    M["object_labels"] = {l: dict(n=len(v), mean=round(sum(v) / len(v), 3),
                                  band_40_60=sum(x < .6 for x in v), band_60_80=sum(.6 <= x < .8 for x in v), band_80=sum(x >= .8 for x in v))
                          for l, v in sorted(per_label.items(), key=lambda kv: -len(kv[1]))}

    by_item = collections.defaultdict(set)
    for i, l, _ in obj: by_item[i].add(l)
    M["object_labels_per_item"] = dict(sorted(collections.Counter(len(v) for v in by_item.values()).items()))
    M["people_labelled_more_than_once"] = sum(len(v & PEOPLE) >= 2 for v in by_item.values())
    M["items_with_people_label"] = sum(bool(v & PEOPLE) for v in by_item.values())
    screen = [i for i in by_item if i in items and is_screen_capture(items[i]["name"], items[i]["folder"])]
    M["screen_captures_with_object_labels"] = len(screen)
    M["object_labels_on_screen_captures"] = dict(collections.Counter(l for i in screen for l in by_item[i]).most_common())

    samples = []
    if a.orientation:
        orient = parse_orientation(a.orientation)
        photos = [i for i, v in items.items() if v["kind"] == 0]
        known = [orient.get(i) for i in photos if i in orient]
        M["photos_rotation_flag"] = dict(collections.Counter("none" if o is None else f"{o}°" for o in known))
        M["labelled_photos_needing_rotation"] = sum(1 for i in by_item if orient.get(i) not in (None, 0))

    if a.audit:
        audit = parse_audit(a.audit)
        flags = collections.Counter(); per_label_flags = collections.defaultdict(collections.Counter)
        for it in audit.values():
            labelled = [d for d in it["asis"] if asserted(d)]
            screen_cap = is_screen_capture(it["name"], it["folder"])
            # One region, several names: overlapping boxes with different labels, which per-class NMS keeps.
            seen_pairs = set()
            for x in range(len(labelled)):
                for y in range(x + 1, len(labelled)):
                    d1, d2 = labelled[x], labelled[y]
                    if d1["label"] != d2["label"] and iou(d1["box"], d2["box"]) > 0.45:
                        weaker = d1 if d1["conf"] < d2["conf"] else d2
                        seen_pairs.add(id(weaker))
            for d in labelled:
                reasons = []
                if d["area"] >= WHOLE_FRAME: reasons.append("whole_frame")
                if d["area"] < SPECK: reasons.append("speck")
                if id(d) in seen_pairs: reasons.append("same_region_second_name")
                if screen_cap: reasons.append("screen_capture")
                if it["exif"] and it["has_upright"]:
                    upright = [u for u in it["upright"] if u["label"] == d["label"] and asserted(u)]
                    reasons.append("rotated_kept_upright" if upright else "rotated_gone_upright")
                if d["conf"] < 0.60: reasons.append("low_confidence")
                for r in reasons:
                    flags[r] += 1; per_label_flags[d["label"]][r] += 1
                flags["detections"] += 1; per_label_flags[d["label"]]["detections"] += 1
                # Suspect: wrong for a reason the pipeline controls.
                bad = {"whole_frame", "same_region_second_name", "rotated_gone_upright"} & set(reasons)
                # Vote candidates: plausible errors with no evidence either way; the user's answer decides.
                ask = {"speck", "screen_capture", "low_confidence"} & set(reasons)
                samples.append(dict(item=it["id"], name=it["name"], folder=it["folder"], label=d["label"],
                                    conf=d["conf"], obj=d["obj"], cls=d["cls"], area=round(d["area"], 4),
                                    exif=it["exif"], reasons=reasons, suspect=bool(bad), vote_candidate=bool(ask) and not bad))
        M["audit_items"] = len(audit)
        M["audit_flags"] = dict(flags)
        M["audit_flags_by_label"] = {l: dict(v) for l, v in sorted(per_label_flags.items(), key=lambda kv: -kv[1]["detections"])}
        M["suspect_detections"] = sum(s["suspect"] for s in samples)
        M["vote_candidates"] = sum(s.get("vote_candidate", False) for s in samples)

    (out / "metrics.json").write_text(json.dumps(M, indent=1, ensure_ascii=False))
    (out / "samples.json").write_text(json.dumps(samples, indent=1, ensure_ascii=False))
    report(M)


def report(M):
    print(f"items {M['items']} {M['items_by_kind']}   tags by source {M['tags_by_source']}")
    print("\nOBJECT labels (n · mean conf · 0.40-0.60 / 0.60-0.80 / 0.80+):")
    for l, v in list(M["object_labels"].items())[:20]:
        print(f"  {l:<18} {v['n']:>4} · {v['mean']:.2f} · {v['band_40_60']:>3} / {v['band_60_80']:>3} / {v['band_80']:>3}")
    print(f"\nobject labels per item: {M['object_labels_per_item']}")
    print(f"people labelled 2+ ways: {M['people_labelled_more_than_once']} of {M['items_with_people_label']} items with a people label")
    print(f"screen captures carrying object labels: {M['screen_captures_with_object_labels']} → {M['object_labels_on_screen_captures']}")
    if "photos_rotation_flag" in M:
        print(f"photo rotation flags: {M['photos_rotation_flag']}   labelled photos needing rotation: {M['labelled_photos_needing_rotation']}")
    if "audit_flags" in M:
        print(f"\naudit of {M['audit_items']} items: {M['audit_flags']}")
        print(f"confirmed-bug detections (whole-frame / 2nd name / gone-upright): {M['suspect_detections']} of {M['audit_flags'].get('detections', 0)}"
              f" · vote candidates (small / screen capture / low confidence): {M['vote_candidates']}")
        print("by label (detections · whole_frame · speck · 2nd name · screen · gone upright · kept upright):")
        for l, v in list(M["audit_flags_by_label"].items())[:16]:
            print(f"  {l:<16} {v.get('detections',0):>4} · {v.get('whole_frame',0):>3} · {v.get('speck',0):>3} · {v.get('same_region_second_name',0):>3}"
                  f" · {v.get('screen_capture',0):>3} · {v.get('rotated_gone_upright',0):>3} · {v.get('rotated_kept_upright',0):>3}")


if __name__ == "__main__":
    sys.exit(main())
