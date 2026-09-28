package com.example.galleryai1

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** [PhotoTagMapDao.getLabelsForPhoto] 등에서 태그 이름만 뽑아올 때 쓰는 프로젝션. */
data class TagNameRow(val tagNameKo: String, val tagNameEn: String)

/** [PhotoAlbumDao.getAllEmbeddings]에서 Tier2 벡터 검색을 위해 뽑아오는 프로젝션. */
data class PhotoEmbeddingRow(val photoUri: String, val embedding: ByteArray)

@Dao
interface PhotoAlbumDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(photo: PhotoAlbumEntity)

    /** 갤러리 재조회 시 사진 목록을 등록할 때 쓴다 — 이미 있는 사진의 분석 결과(embedding/taggingVersion)를 지우면 안 되므로 REPLACE가 아니라 IGNORE. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertAllIfAbsent(photos: List<PhotoAlbumEntity>)

    @Query("SELECT * FROM photo_album WHERE photoUri = :uri")
    fun get(uri: String): PhotoAlbumEntity?

    @Query("SELECT photoUri FROM photo_album")
    fun getAllUris(): List<String>

    @Query("DELETE FROM photo_album WHERE photoUri IN (:uris)")
    fun deleteByUris(uris: List<String>)

    /** Tier2(벡터 폴백) 검색용 — 분석이 끝나 임베딩이 있는 사진만 대상으로 한다. */
    @Query("SELECT photoUri, embedding FROM photo_album WHERE embedding IS NOT NULL")
    fun getAllEmbeddings(): List<PhotoEmbeddingRow>
}

@Dao
interface TagMasterDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(tag: TagMasterEntity): Long

    @Query("SELECT COUNT(*) FROM tag_master")
    fun count(): Int

    /** 태그 후보 전체를 메모리로 올려 코사인 유사도 매칭에 쓴다 — 수백 개 수준이라 매번 전부 로드해도 가볍다. */
    @Query("SELECT * FROM tag_master")
    fun getAll(): List<TagMasterEntity>

    @Query("SELECT * FROM tag_master WHERE tagNameEn = :en LIMIT 1")
    fun findByEn(en: String): TagMasterEntity?

    /** 자동 태깅 후보에서 제외하기로 한 태그를 정리한다(CASCADE로 PhotoTagMap에 이미 붙은 것도 같이 지워짐). */
    @Query("DELETE FROM tag_master WHERE tagNameEn IN (:names)")
    fun deleteByEnNames(names: List<String>)

    @Query("SELECT * FROM tag_master WHERE tagNameKo = :ko LIMIT 1")
    fun findByKo(ko: String): TagMasterEntity?

    /** [findByKo]+[findByEn]를 한 번의 조회로 합친 것 — 사용자 태그 추가/삭제의 hot path에서 왕복을 줄인다. */
    @Query("SELECT * FROM tag_master WHERE tagNameKo = :ko OR tagNameEn = :en LIMIT 1")
    fun findByKoOrEn(ko: String, en: String): TagMasterEntity?
}

@Dao
interface PhotoTagMapDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(rows: List<PhotoTagMapEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(row: PhotoTagMapEntity)

    @Query("DELETE FROM photo_tag_map WHERE photoUri = :uri AND source = :source")
    fun deleteBySource(uri: String, source: String)

    @Query("DELETE FROM photo_tag_map WHERE photoUri = :uri AND tagId = :tagId AND source = :source")
    fun delete(uri: String, tagId: Long, source: String)

    @Query(
        """
        SELECT t.tagNameKo AS tagNameKo, t.tagNameEn AS tagNameEn
        FROM photo_tag_map m
        INNER JOIN tag_master t ON m.tagId = t.tagId
        WHERE m.photoUri = :uri AND m.source = :source
        ORDER BY m.confidence DESC
        """
    )
    fun getLabelsForPhoto(uri: String, source: String): List<TagNameRow>

    /**
     * Tier1(색인 검색): [terms] 중 하나라도 태그명(한글 또는 영문)과 일치하는 사진들의 URI.
     * MainActivity가 개념 그룹(동의어 OR)마다 이 함수를 호출하고, 그룹 간 AND는 Kotlin에서 교집합으로 처리한다.
     */
    @Query(
        """
        SELECT DISTINCT m.photoUri
        FROM photo_tag_map m
        INNER JOIN tag_master t ON m.tagId = t.tagId
        WHERE t.tagNameKo IN (:terms) OR t.tagNameEn IN (:terms)
        """
    )
    fun findPhotoUrisByTagTerms(terms: List<String>): List<String>
}
