# -*- coding: utf-8 -*-
"""
Kotlin ClipTokenizer.kt를 그대로 재현한 Python 버전 + text_model.onnx로 새 태그 임베딩을 계산.
seed_tags.json에 이미 있는 항목(예: dog)을 같은 방식으로 재계산해서 저장된 값과 거의 일치하는지
확인한 뒤(템플릿 문구 확정), 새 개념들의 임베딩을 뽑아 CSV/JSON으로 낸다.
"""
import json
import re
import unicodedata
import onnxruntime as ort
import numpy as np

ASSETS = r"D:\AndroidStudioProject\GalleryAI1\app\src\main\assets\mobileclip"

CONTEXT_LENGTH = 77
BOS_ID = 49406
EOS_ID = 49407
PAD_ID = 0

def build_byte_encoder():
    bs = list(range(ord('!'), ord('~') + 1)) + list(range(0xA1, 0xAC + 1)) + list(range(0xAE, 0xFF + 1))
    cs = bs[:]
    n = 0
    for b in range(256):
        if b not in bs:
            bs.append(b)
            cs.append(256 + n)
            n += 1
    return {b: chr(c) for b, c in zip(bs, cs)}

BYTE_ENCODER = build_byte_encoder()
PRETOKENIZE_REGEX = re.compile(r"'s|'t|'re|'ve|'m|'ll|'d|[^\W\d_]+|[0-9]|[^\s\w]+", re.UNICODE)
# 위 패턴은 \p{L}/\p{N} 대체용 근사치. 아래에서 regex 모듈로 원본 그대로(\p{L}\p{N}) 재정의한다.
import regex as pyregex
PRETOKENIZE_REGEX = pyregex.compile(r"'s|'t|'re|'ve|'m|'ll|'d|[\p{L}]+|[\p{N}]|[^\s\p{L}\p{N}]+")

def clip_normalize(text: str) -> str:
    nfc = unicodedata.normalize('NFC', text)
    return re.sub(r"\s+", " ", nfc).lower()

def clip_pretokenize(text: str):
    return PRETOKENIZE_REGEX.findall(clip_normalize(text))

def byte_level_encode(chunk: str) -> str:
    b = chunk.encode('utf-8')
    return ''.join(BYTE_ENCODER[x] for x in b)

def adjacent_pairs(word):
    return set(zip(word, word[1:]))

def bpe_merge(token: str, merge_rank: dict):
    if len(token) < 2:
        return [token + "</w>"]
    word = list(token[:-1]) + [token[-1] + "</w>"]
    pairs = adjacent_pairs(word)
    if not pairs:
        return word
    while True:
        best_pair = min(pairs, key=lambda p: merge_rank.get(p, float('inf')))
        if best_pair not in merge_rank:
            break
        first, second = best_pair
        merged = []
        i = 0
        while i < len(word):
            if i < len(word) - 1 and word[i] == first and word[i + 1] == second:
                merged.append(first + second)
                i += 2
            else:
                merged.append(word[i])
                i += 1
        word = merged
        if len(word) == 1:
            break
        pairs = adjacent_pairs(word)
    return word

class ClipTokenizer:
    def __init__(self, vocab: dict, merge_rank: dict):
        self.vocab = vocab
        self.merge_rank = merge_rank

    @classmethod
    def load(cls):
        with open(f"{ASSETS}\\clip_vocab.txt", encoding='utf-8') as f:
            vocab_list = [l.rstrip('\n') for l in f]
        vocab = {tok: i for i, tok in enumerate(vocab_list)}
        merge_rank = {}
        with open(f"{ASSETS}\\clip_merges.txt", encoding='utf-8') as f:
            for rank, line in enumerate(f):
                line = line.rstrip('\n')
                idx = line.find(' ')
                if idx > 0:
                    merge_rank[(line[:idx], line[idx+1:])] = rank
        return cls(vocab, merge_rank)

    def encode(self, text: str):
        ids = [BOS_ID]
        for chunk in clip_pretokenize(text):
            byte_str = byte_level_encode(chunk)
            for piece in bpe_merge(byte_str, self.merge_rank):
                ids.append(self.vocab.get(piece, EOS_ID))
        ids.append(EOS_ID)
        result = [PAD_ID] * CONTEXT_LENGTH
        copy_count = min(len(ids), CONTEXT_LENGTH)
        result[:copy_count] = ids[:copy_count]
        if len(ids) > CONTEXT_LENGTH:
            result[CONTEXT_LENGTH - 1] = EOS_ID
        return result


def l2norm(v):
    n = np.linalg.norm(v)
    return v if n == 0 else v / n


if __name__ == "__main__":
    tok = ClipTokenizer.load()
    sess = ort.InferenceSession(f"{ASSETS}\\text_model.onnx", providers=["CPUExecutionProvider"])
    input_name = sess.get_inputs()[0].name

    def embed(text: str):
        ids = np.array([tok.encode(text)], dtype=np.int64)
        out = sess.run(None, {input_name: ids})[0][0]
        return l2norm(out)

    # 검증: seed_tags.json의 "dog" 항목과 비교해서 어떤 템플릿이 쓰였는지 확인
    with open(f"{ASSETS}\\seed_tags.json", encoding='utf-8') as f:
        seeds = json.load(f)
    dog_stored = np.array(next(s for s in seeds if s['en'] == 'dog')['emb'], dtype=np.float32)

    for template in ["a photo of {}", "a photo of a {}"]:
        e = embed(template.format("dog"))
        sim = float(np.dot(e, dog_stored))
        print(f"template={template!r:25s} cos_sim_to_stored_dog={sim:.6f}")
