# -*- coding: utf-8 -*-
"""
GalleryAI1 AI 태깅 정확도 측정 하네스.

"체감상 70%" 같은 감으로만 판단하지 않고, 실제로 recall/precision을 재기 위한 스크립트다.
Room DB(gallery_ai.db)를 실기기/에뮬레이터에서 뽑아온 뒤, tools/ground_truth.json에 사람이 직접
눈으로 보고 적어둔 정답 태그와 비교한다. 2026-09-14, YOLO COCO 매핑을 24→70개로 확장하면서 처음
만들었다 — [[galleryai1-yolo-hybrid-tagging]] 메모 참고.

사용법(tools/README.md에 전체 절차 정리):
    python measure_tagging_accuracy.py --db gallery_ai.db --media-ids media_ids.txt

--media-ids 없이 --db만 줘도 동작하지만, 그러면 photoUri의 MediaStore 숫자 id로만 매칭하므로
ground_truth.json에 파일명 대신 id를 적어야 한다. 보통은 다음으로 media_ids.txt를 만든다:
    adb shell content query --uri content://media/external/images/media \
        --projection _id:_display_name > media_ids.txt
"""
import argparse
import json
import re
import sqlite3
import sys
from pathlib import Path


def load_media_id_to_name(path: str) -> dict[str, str]:
    """`adb shell content query ... --projection _id:_display_name` 출력(Row: N _id=ID, _display_name=NAME)을 파싱한다."""
    mapping = {}
    pattern = re.compile(r"_id=(\d+), _display_name=(.+)$")
    with open(path, encoding="utf-8") as f:
        for line in f:
            m = pattern.search(line.strip())
            if m:
                mapping[m.group(1)] = m.group(2)
    return mapping


def extract_media_id(photo_uri: str) -> str | None:
    """`content://media/external/images/media/123` 형태에서 마지막 숫자 id를 뽑는다."""
    m = re.search(r"/(\d+)$", photo_uri)
    return m.group(1) if m else None


def fetch_predictions(db_path: str) -> dict[str, list[tuple[str, float]]]:
    """photoUri -> [(tagNameKo, confidence), ...] (AI 태그만, source='AI')."""
    con = sqlite3.connect(db_path)
    con.text_factory = str
    cur = con.cursor()
    cur.execute("""
        SELECT p.photoUri, t.tagNameKo, m.confidence
        FROM photo_album p
        LEFT JOIN photo_tag_map m ON m.photoUri = p.photoUri AND m.source = 'AI'
        LEFT JOIN tag_master t ON t.tagId = m.tagId
        ORDER BY p.photoUri
    """)
    out: dict[str, list[tuple[str, float]]] = {}
    for uri, tag_ko, conf in cur.fetchall():
        out.setdefault(uri, [])
        if tag_ko is not None:
            out[uri].append((tag_ko, conf))
    con.close()
    return out


def evaluate(predictions_by_name: dict[str, list[tuple[str, float]]], ground_truth: dict) -> None:
    rows = []
    total_required_hit = 0
    total_required = 0
    total_tp = 0  # 예측 태그 중 required/accepted 안에 있는 것(진짜 맞은 것)
    total_predicted = 0  # 예측 태그 전체(오탐 계산용 분모)

    for name, gt in ground_truth.items():
        if name.startswith("_"):
            continue
        required = set(gt.get("required", []))
        accepted = required | set(gt.get("accepted", []))
        preds = predictions_by_name.get(name)
        if preds is None:
            rows.append((name, "★ DB에 없음(사진이 없거나 분석 안 됨)", "-", "-"))
            continue
        pred_names = [p[0] for p in preds]
        pred_set = set(pred_names)

        hit_required = required & pred_set
        recall = len(hit_required) / len(required) if required else 1.0
        tp = len(pred_set & accepted)
        precision = tp / len(pred_set) if pred_set else (1.0 if not required else 0.0)

        total_required_hit += len(hit_required)
        total_required += len(required)
        total_tp += tp
        total_predicted += len(pred_set)

        pred_str = ", ".join(f"{n}({c:.2f})" for n, c in preds) if preds else "(태그 없음)"
        status = "OK" if recall == 1.0 else "MISS"
        rows.append((name, f"{status} recall={recall:.0%} precision={precision:.0%}", pred_str, sorted(required - pred_set)))

    print(f"{'파일명':45s} {'결과':45s} 예측 태그")
    print("-" * 140)
    for name, status, pred_str, *rest in rows:
        print(f"{name:45s} {status:45s} {pred_str}")
        if rest and rest[0]:
            print(f"{'':45s} {'':45s} → 놓친 필수 태그: {rest[0]}")

    print()
    overall_recall = total_required_hit / total_required if total_required else float("nan")
    overall_precision = total_tp / total_predicted if total_predicted else float("nan")
    f1 = (2 * overall_precision * overall_recall / (overall_precision + overall_recall)
          if (overall_precision + overall_recall) > 0 else float("nan"))
    print(f"=== 종합 ({len([r for r in ground_truth if not r.startswith('_')])}장 기준) ===")
    print(f"recall(필수 태그 재현율)   : {overall_recall:.1%}  ({total_required_hit}/{total_required})")
    print(f"precision(예측 태그 정밀도): {overall_precision:.1%}  ({total_tp}/{total_predicted})")
    print(f"F1                        : {f1:.1%}")


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--db", required=True, help="adb로 pull한 gallery_ai.db 경로")
    ap.add_argument("--media-ids", help="adb content query 결과 파일(선택 — 있으면 파일명으로 매칭)")
    ap.add_argument("--ground-truth", default=str(Path(__file__).parent / "ground_truth.json"))
    args = ap.parse_args()

    predictions_by_uri = fetch_predictions(args.db)

    if args.media_ids:
        id_to_name = load_media_id_to_name(args.media_ids)
        predictions_by_name: dict[str, list[tuple[str, float]]] = {}
        for uri, preds in predictions_by_uri.items():
            media_id = extract_media_id(uri)
            name = id_to_name.get(media_id) if media_id else None
            if name:
                predictions_by_name[name] = preds
    else:
        predictions_by_name = predictions_by_uri

    with open(args.ground_truth, encoding="utf-8") as f:
        ground_truth = json.load(f)

    evaluate(predictions_by_name, ground_truth)


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    main()
