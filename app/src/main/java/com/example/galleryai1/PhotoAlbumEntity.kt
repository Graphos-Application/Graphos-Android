package com.example.galleryai1

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * setting.md 기획서의 `PhotoAlbum` 테이블. 사진 한 장 = 한 행.
 *
 * 기획서 원안에는 없지만 [embedding] 컬럼을 추가했다 — 검색 파이프라인 4.2절의 "Tier 2(벡터 폴백):
 * MobileCLIP Text Encoder로 검색어 임베딩 생성 후 **사진 임베딩**과 유사도 비교"를 실제로 구현하려면
 * 사진별 이미지 임베딩을 어딘가에 저장해 둬야 한다(그때그때 원본 이미지를 다시 불러와 재추론하면
 * 검색마다 모든 미분류 사진을 다시 돌려야 해서 "저지연" 요구사항에 어긋난다). [TagMaster.embedding]과
 * 같은 이유·같은 형식(512차원 float32를 그대로 바이트로 packing)으로 저장한다.
 */
@Entity(tableName = "photo_album")
data class PhotoAlbumEntity(
    @PrimaryKey
    @ColumnInfo(name = "photoUri")
    val photoUri: String,

    @ColumnInfo(name = "dateAdded")
    val dateAdded: Long,

    @ColumnInfo(name = "dateModified")
    val dateModified: Long,

    /** MobileCLIP 비전 인코더가 뽑은 512차원 이미지 임베딩(L2 정규화됨). 분석 전이면 null. */
    @ColumnInfo(name = "embedding", typeAffinity = ColumnInfo.BLOB)
    val embedding: ByteArray? = null,

    /**
     * MobileCLIP 태깅을 마지막으로 시도했을 때의 [TagRepository]태깅 로직 버전(0 = 아직 분석 안 함).
     * 단순 boolean이 아니라 버전 번호인 이유: 임계값/후보 태그 사전처럼 태깅 품질에 영향을 주는
     * 값을 튜닝했을 때(예: "돌고래 사진에 거북이 태그" 같은 오탐 신고 이후 임계값 상향), 이미 분석된
     * 사진도 앱 데이터 초기화 없이 자동으로 재분석되게 하려면 "이 사진이 몇 번 버전 로직으로
     * 분석됐는지"를 알아야 한다 — [TagRepository.TAGGING_VERSION]과 다르면 재분석 대상.
     */
    @ColumnInfo(name = "taggingVersion")
    val taggingVersion: Int = 0
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PhotoAlbumEntity) return false
        return photoUri == other.photoUri && dateAdded == other.dateAdded &&
            dateModified == other.dateModified && taggingVersion == other.taggingVersion &&
            (embedding?.contentEquals(other.embedding) ?: (other.embedding == null))
    }

    override fun hashCode(): Int {
        var result = photoUri.hashCode()
        result = 31 * result + dateAdded.hashCode()
        result = 31 * result + dateModified.hashCode()
        result = 31 * result + taggingVersion.hashCode()
        result = 31 * result + (embedding?.contentHashCode() ?: 0)
        return result
    }
}
