# -*- coding: utf-8 -*-
"""
검색 파이프라인(Tier1 사전매칭 + Tier2 CLIP 벡터검색) 실패 사례 수집 하네스.
SearchSynonyms.kt/DateExpressionParser.kt의 로직을 파이썬으로 이식해서 Tier1을 시뮬레이션하고,
실제 DB의 사진 임베딩(gallery_ai.db)에 대해 실제 text_model.onnx로 Tier2까지 재현한다.

2026-09-14, 이 하네스로 "Tier2가 한글 원문을 그대로 CLIP에 넣어서, 문장이 조금만 길어져도
서로 무관한 질의끼리 코사인 유사도가 0.93~0.98(사실상 구분 불가)로 수렴한다"는 버그를 찾아
`TagRepository.vectorSearch`에 번역 우선 적용 fix로 이어졌다(실기기 검증 완료 —
[[galleryai1-yolo-hybrid-tagging]] 참고). 이 스크립트 자체는 ML Kit Translate를 재현 못 하므로
Tier2 시뮬레이션은 기본적으로 "번역 없음"(수정 전 상태)이다 — `--translate KO EN`으로 번역
결과를 수동으로 주면 수정 후 동작을 재현해볼 수 있다.

사용법:
    python search_harness.py --db gallery_ai.db --media-ids media_ids.txt --queries search_queries.json
"""
import argparse
import json
import re
import sqlite3
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from clip_embed import ClipTokenizer, l2norm
import onnxruntime as ort
import numpy as np

ASSETS = str(Path(__file__).parent.parent / "app" / "src" / "main" / "assets" / "mobileclip")

SIMILARITY_THRESHOLD = 0.18  # TagRepository.SIMILARITY_THRESHOLD와 동일
VECTOR_SEARCH_TOP_K = 60

# ---------------- SearchSynonyms.kt 파이썬 포팅 ----------------
# koreanToEnglish: SearchSynonyms.kt 원본과 정확히 동기화(2026-09-14 46개 확장분 포함).
KO_TO_EN = {
    "강아지": ["dog", "puppy"], "개": ["dog"], "고양이": ["cat", "kitten"], "동물": ["animal"],
    "새": ["bird"], "물고기": ["fish"], "곤충": ["insect", "bug"], "나비": ["butterfly"],
    "토끼": ["rabbit"], "말": ["horse"], "소": ["cow"], "돼지": ["pig"], "양": ["sheep"],
    "닭": ["chicken"], "돌고래": ["dolphin"], "원숭이": ["monkey"], "기린": ["giraffe"],
    "미어캣": ["meerkat"], "거북이": ["turtle", "terrapin", "tortoise"], "거북": ["turtle", "terrapin", "tortoise"],
    "곰": ["bear"], "여우": ["fox"], "부엉이": ["owl"], "올빼미": ["owl"], "앵무새": ["parrot"],
    "동물원": ["zoo", "safari"], "사파리": ["safari"], "반려동물": ["pet"], "애완동물": ["pet"],
    "수중": ["underwater"], "물속": ["underwater"],
    "사람": ["person", "people", "human"], "아기": ["baby", "child"], "가족": ["family", "people"],
    "음식": ["food", "dish", "cuisine", "meal"], "과일": ["fruit"], "채소": ["vegetable"], "고기": ["meat"],
    "빵": ["bread"], "피자": ["pizza"], "국수": ["noodle", "noodles"], "밥": ["rice"],
    "과자": ["snack", "cookie"], "아이스크림": ["ice cream"], "케이크": ["cake", "dessert"],
    "음료": ["drink", "beverage"], "커피": ["coffee"], "맥주": ["beer"], "와인": ["wine"],
    "식기": ["tableware"], "그릇": ["tableware", "bowl"], "초밥": ["sushi"],
    "바다": ["sea", "ocean", "beach"], "산": ["mountain"], "하늘": ["sky"], "꽃": ["flower", "plant"],
    "꽃잎": ["petal"], "꽃밭": ["flower", "field", "garden"], "들판": ["field"], "바위": ["rock"],
    "돌": ["rock"], "나무": ["tree", "plant"], "가지": ["branch"], "눈": ["snow"], "비": ["rain"],
    "구름": ["cloud"], "별": ["star"], "달": ["moon"], "물": ["water"], "강": ["river"],
    "호수": ["lake"], "석양": ["sunset"], "일몰": ["sunset"], "일출": ["sunrise"], "불": ["fire"],
    "밤": ["night"], "낮": ["day"],
    "책": ["book"], "컴퓨터": ["computer", "laptop"], "휴대폰": ["phone", "smartphone", "mobile phone"],
    "의자": ["chair", "furniture"], "테이블": ["table", "furniture"], "신발": ["shoe", "footwear"],
    "옷": ["clothing", "clothes"], "청바지": ["jeans"], "재킷": ["jacket"], "자켓": ["jacket"],
    "외투": ["outerwear", "jacket"], "군복": ["military uniform"], "유니폼": ["uniform", "military uniform"],
    "제복": ["uniform", "military uniform"], "모자": ["hat"], "안경": ["glasses", "eyewear"],
    "시계": ["clock", "watch"], "악기": ["musical instrument", "guitar", "piano"], "음악": ["music"],
    "금속": ["metal"], "무늬": ["pattern"], "패턴": ["pattern"],
    "트럭": ["truck"], "벤치": ["bench"], "코끼리": ["elephant"], "얼룩말": ["zebra"],
    "백팩": ["backpack"], "배낭": ["backpack"], "우산": ["umbrella"], "핸드백": ["handbag"],
    "넥타이": ["tie"], "캐리어": ["suitcase"], "여행가방": ["suitcase"], "프리스비": ["frisbee"],
    "원반": ["frisbee"], "스노보드": ["snowboard"], "연": ["kite"], "스케이트보드": ["skateboard"],
    "서핑보드": ["surfboard"], "테니스라켓": ["tennis racket"], "병": ["bottle"], "와인잔": ["wine glass"],
    "컵": ["cup"], "포크": ["fork"], "칼": ["knife"], "숟가락": ["spoon"], "바나나": ["banana"],
    "사과": ["apple"], "샌드위치": ["sandwich"], "오렌지": ["orange"], "핫도그": ["hot dog"],
    "도넛": ["donut"], "소파": ["couch"], "화분": ["potted plant"], "침대": ["bed"], "변기": ["toilet"],
    "텔레비전": ["tv"], "티비": ["tv"], "마우스": ["mouse"], "리모컨": ["remote"], "키보드": ["keyboard"],
    "전자레인지": ["microwave"], "오븐": ["oven"], "싱크대": ["sink"], "냉장고": ["refrigerator"],
    "꽃병": ["vase"], "가위": ["scissors"], "곰인형": ["teddy bear"], "드라이기": ["hair drier"],
    "칫솔": ["toothbrush"], "스키": ["skis"],
    "명찰": ["name tag", "badge"], "행사": ["event"], "회의": ["meeting"], "발표": ["presentation"],
    "공부": ["studying"], "시험": ["exam"], "노트북": ["laptop"], "단체사진": ["group photo"],
    "학교": ["school"], "강의실": ["classroom"], "세미나": ["seminar"], "회의실": ["conference room"],
    "자동차": ["car", "vehicle"], "자전거": ["bicycle", "bike"], "비행기": ["airplane", "aircraft"],
    "기차": ["train"], "버스": ["bus"], "배": ["boat", "ship"], "오토바이": ["motorcycle"],
    "길": ["road", "street"], "다리": ["bridge"], "건물": ["building", "architecture"], "벽": ["wall"],
    "타워": ["tower"], "공원": ["park"], "놀이터": ["playground"], "식당": ["restaurant"],
    "레스토랑": ["restaurant"], "카페": ["cafe", "coffee shop"],
    "크리스마스": ["christmas"], "생일": ["birthday"], "파티": ["party"], "결혼식": ["wedding"],
    "불꽃놀이": ["fireworks"], "운동": ["sport", "sports"], "축구": ["soccer", "football"],
    "농구": ["basketball"], "야구": ["baseball"],
    "재미": ["fun"], "즐거움": ["fun", "leisure"], "여가": ["leisure"], "휴식": ["leisure"],
    "서있는": ["standing"], "서 있는": ["standing"], "멋진": ["cool"],
}

TRAILING_PARTICLES = sorted([
    "에서", "으로서", "으로써", "으로", "로써", "로서", "에게서", "한테서", "이랑", "이나",
    "이든", "이라도", "까지", "부터", "밖에", "만큼", "처럼", "마다",
    "에게", "한테", "은", "는", "이", "가", "을", "를", "의", "도", "만", "에", "로", "와", "과", "랑", "나", "든"
], key=len, reverse=True)

SEARCH_STOPWORDS = {
    "사진", "이미지", "찾아줘", "찾아주세요", "보여줘", "보여주세요", "알려줘", "알려주세요",
    "좀", "정도", "쯤", "경", "것", "거", "건", "때",
    "찍은", "찍힌", "찍었던", "있는", "있던", "있었던", "나온", "나오는", "보이는", "포함된"
}


def strip_trailing_particle(token: str) -> str:
    for p in TRAILING_PARTICLES:
        if len(token) > len(p) and token.endswith(p):
            return token[: -len(p)]
    return token


def match_dictionary_terms(variants: set) -> set:
    terms = set()
    for korean, english_list in KO_TO_EN.items():
        if len(korean) <= 1:
            korean_hit = korean in variants
        else:
            korean_hit = any(korean in v or (len(v) >= 2 and v in korean) for v in variants)
        if korean_hit:
            terms.update(english_list)
        if any(v == eng or eng in v for eng in english_list for v in variants):
            terms.add(korean)
    return terms


def extract_meaningful_tokens(query: str):
    q = query.strip().lower()
    if not q:
        return []
    raw_tokens = [t for t in re.split(r"[\s,.!?~]+", q) if t]
    out = []
    for raw in raw_tokens:
        if raw in SEARCH_STOPWORDS:
            continue
        stripped = strip_trailing_particle(raw)
        if not stripped or stripped in SEARCH_STOPWORDS or stripped in TRAILING_PARTICLES:
            continue
        if len(stripped) == 1 and stripped not in KO_TO_EN:
            continue
        out.append(stripped)
    return out


def expand_grouped(query: str):
    if not query.strip():
        return []
    tokens = extract_meaningful_tokens(query)
    if not tokens:
        # 원문 전체를 한 그룹으로(폴백) -- 이번 하네스에선 이 분기 자체가 이미 "결과 안 나옴"을
        # 뜻하므로 그냥 원문 단어들을 그대로 반환(정밀 재현은 생략).
        return [set(re.split(r"[\s,.!?~]+", query.strip().lower()))]
    return [({token} | match_dictionary_terms({token})) for token in tokens]


# ---------------- DateExpressionParser.kt 파이썬 포팅(요약: 날짜 제거만, 범위 계산은 생략) ----------------
DATE_PATTERNS = [
    r"재작년\s*(봄|여름|가을|겨울)", r"작년\s*(봄|여름|가을|겨울)", r"올해\s*(봄|여름|가을|겨울)",
    r"지난\s*(봄|여름|가을|겨울)", r"(\d{4})\s*년\s*(봄|여름|가을|겨울)", r"(\d{2})\s*년\s*(봄|여름|가을|겨울)",
    r"(봄|여름|가을|겨울)", r"(\d{4})\s*년", r"(\d+)\s*년\s*전", r"(\d{2})\s*년",
    r"재작년", r"작년", r"올해", r"(지난|저번)\s*달", r"이번\s*달", r"(지난|저번)\s*주", r"이번\s*주",
    r"어제", r"오늘", r"(\d{1,2})\s*월",
]


def strip_date_expression(query: str):
    for pat in DATE_PATTERNS:
        m = re.search(pat, query)
        if m:
            return query[: m.start()] + " " + query[m.end():], True
    return query, False


# ---------------- Tier1 시뮬레이션(실제 DB의 photo_tag_map 사용) ----------------
def load_photo_tags(db_path: str):
    con = sqlite3.connect(db_path)
    cur = con.cursor()
    cur.execute("""
        SELECT p.photoUri, t.tagNameKo, t.tagNameEn
        FROM photo_album p
        JOIN photo_tag_map m ON m.photoUri = p.photoUri AND m.source = 'AI'
        JOIN tag_master t ON t.tagId = m.tagId
    """)
    tags_by_photo = {}
    for uri, ko, en in cur.fetchall():
        tags_by_photo.setdefault(uri, set()).add(ko.lower())
        tags_by_photo.setdefault(uri, set()).add(en.lower())
    cur.execute("SELECT photoUri, embedding FROM photo_album")
    emb_by_photo = {}
    for uri, blob in cur.fetchall():
        if blob:
            emb_by_photo[uri] = np.frombuffer(blob, dtype="<f4")
    con.close()
    return tags_by_photo, emb_by_photo


def load_media_names(path: str):
    mapping = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            m = re.search(r"_id=(\d+), _display_name=(.+)$", line.strip())
            if m:
                mapping[m.group(1)] = m.group(2)
    return mapping


def uri_to_name(uri: str, id_to_name: dict) -> str:
    m = re.search(r"/(\d+)$", uri)
    return id_to_name.get(m.group(1), uri) if m else uri


def tier1_match(concept_groups, tags_by_photo: dict) -> set:
    result = None
    for group in concept_groups:
        terms = {t.lower() for t in group}
        hit = {uri for uri, tags in tags_by_photo.items() if terms & tags}
        result = hit if result is None else (result & hit)
        if not result:
            return set()
    return result or set()


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--db", required=True, help="adb로 pull한 gallery_ai.db 경로")
    ap.add_argument("--media-ids", required=True, help="adb content query 결과 파일")
    ap.add_argument("--queries", required=True, help="테스트 질의 JSON(예: tools/search_queries.json)")
    ap.add_argument("--translate", action="append", nargs=2, metavar=("KO", "EN"),
                     help="Tier2 번역-우선 수정(2026-09-14) 재현용 — 'KO' 질의를 'EN'으로 번역했다고"
                          " 가정하고 그 번역문으로 임베딩한다(여러 번 지정 가능). 안 주면 원문 그대로"
                          " 넣는 예전 동작(수정 전 상태)을 재현한다.")
    args = ap.parse_args()

    tags_by_photo, emb_by_photo = load_photo_tags(args.db)
    id_to_name = load_media_names(args.media_ids)
    translations = dict(args.translate) if args.translate else {}

    tok = ClipTokenizer.load()
    sess = ort.InferenceSession(ASSETS + r"\text_model.onnx", providers=["CPUExecutionProvider"])
    input_name = sess.get_inputs()[0].name

    def embed_query(text: str):
        ids = tok.encode(text)
        arr = np.array([ids], dtype=np.int64)
        out = sess.run(None, {input_name: arr})[0][0]
        return l2norm(out)

    def tier2_match(raw_query: str):
        embed_text = translations.get(raw_query, raw_query)
        q_emb = embed_query(embed_text)
        sims = [(uri, float(np.dot(q_emb, emb))) for uri, emb in emb_by_photo.items()]
        sims.sort(key=lambda x: -x[1])
        above = [(uri, s) for uri, s in sims if s >= SIMILARITY_THRESHOLD]
        return above[:VECTOR_SEARCH_TOP_K], sims[:5]

    queries = json.load(open(args.queries, encoding="utf-8"))
    for q in queries:
        query = q["query"]
        expected = set(q["expected"])
        remaining, had_date = strip_date_expression(query)
        groups = expand_grouped(remaining)
        t1 = tier1_match(groups, tags_by_photo)
        t1_names = {uri_to_name(u, id_to_name) for u in t1}

        print(f"\n=== {query!r} ===")
        print(f"  date_stripped={had_date!r} remaining={remaining.strip()!r}")
        print(f"  concept_groups={groups}")
        print(f"  Tier1 결과({len(t1_names)}장): {sorted(t1_names)}")

        if not t1_names:
            t2, top5 = tier2_match(query)
            t2_names = {uri_to_name(u, id_to_name) for u, s in t2}
            label = f"(번역:{translations[query]!r})" if query in translations else "(원문 그대로)"
            print(f"  Tier1 비어서 Tier2 시도 {label} → 상위 5개 유사도: " +
                  ", ".join(f'{uri_to_name(u, id_to_name)}={s:.3f}' for u, s in top5))
            print(f"  Tier2 결과({len(t2_names)}장, threshold 0.18): {sorted(t2_names)}")
            final = t2_names
        else:
            final = t1_names

        verdict = "PASS" if final == expected else ("PARTIAL" if final & expected else "FAIL")
        print(f"  기대값: {sorted(expected)}")
        print(f"  === 판정: {verdict} ===")


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    main()
