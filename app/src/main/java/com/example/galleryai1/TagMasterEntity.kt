package com.example.galleryai1

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * setting.md 기획서의 `TagMaster` 테이블. MobileCLIP 제로샷 태깅의 "후보 태그 사전" 역할을 한다.
 *
 * 씨앗 데이터(약 130여 개)는 기존 [SearchSynonyms]의 한글↔영어 사전을 그대로 재사용해서
 * `assets/mobileclip/seed_tags.json`에 "a photo of a {영어단어}" 템플릿으로 미리 임베딩까지
 * 계산해 번들해 뒀다(앱 최초 실행 시 온디바이스로 다시 계산할 필요 없음 — 기획서 4.1절의
 * "사전 정의된 TagMaster 임베딩 목록"에 대응). 사용자가 라벨 편집에서 새 태그를 추가하면
 * 그 자리에서 텍스트 임베딩을 계산해 새 행으로 추가한다(기획서 3.1절 "Text Embedding (Optional):
 * 사용자 정의 태그 동적 추가 시 텍스트 임베딩 생성" 요구사항 — [TagRepository.addUserTag] 참고).
 */
@Entity(
    tableName = "tag_master",
    indices = [
        Index(value = ["tagNameEn"], unique = true),
        // Tier1 검색(findPhotoUrisByTagTerms)과 findByKo/findByKoOrEn이 한글 태그명으로도 자주
        // 조회하므로, tagNameEn과 대칭으로 인덱스를 둔다.
        Index(value = ["tagNameKo"])
    ]
)
data class TagMasterEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "tagId")
    val tagId: Long = 0,

    /** MobileCLIP 매칭 기준이 되는 영문 태그명(소문자로 정규화해서 저장). */
    @ColumnInfo(name = "tagNameEn")
    val tagNameEn: String,

    @ColumnInfo(name = "tagNameKo")
    val tagNameKo: String,

    /** 이 태그 텍스트의 512차원 MobileCLIP 텍스트 임베딩(L2 정규화됨). */
    @ColumnInfo(name = "embedding", typeAffinity = ColumnInfo.BLOB)
    val embedding: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TagMasterEntity) return false
        return tagId == other.tagId && tagNameEn == other.tagNameEn &&
            tagNameKo == other.tagNameKo && embedding.contentEquals(other.embedding)
    }

    override fun hashCode(): Int {
        var result = tagId.hashCode()
        result = 31 * result + tagNameEn.hashCode()
        result = 31 * result + tagNameKo.hashCode()
        result = 31 * result + embedding.contentHashCode()
        return result
    }
}
