package com.example.galleryai1

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** [PhotoTagMapEntity.source]: 이 태그가 어디서 왔는지 — AI가 자동으로 붙였는지, 사용자가 직접 추가했는지. */
object TagSource {
    const val AI = "AI"
    const val USER = "USER"
}

/**
 * setting.md 기획서의 `PhotoTagMap` 테이블(사진 M : 태그 N 매핑). [confidence]는 기획서 그대로다.
 *
 * [source] 컬럼은 기획서엔 없지만 추가했다 — 기존 `LabelStore`가 "사용자가 직접 붙인 라벨"과
 * "AI가 자동으로 붙인 라벨"을 구분해서 관리했던 것(AI 라벨은 라벨 편집 다이얼로그에서 읽기 전용으로만
 * 보여주고, 사용자가 지우거나 새로 추가하는 건 사용자 라벨뿐)과 같은 이유다. 이 구분이 없으면
 * 사용자가 편집 다이얼로그를 열 때마다 AI 자동 태그까지 지울 수 있는 것처럼 보여서 혼란스럽다.
 */
@Entity(
    tableName = "photo_tag_map",
    primaryKeys = ["photoUri", "tagId"],
    indices = [Index(value = ["tagId"])],
    foreignKeys = [
        ForeignKey(
            entity = PhotoAlbumEntity::class,
            parentColumns = ["photoUri"],
            childColumns = ["photoUri"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = TagMasterEntity::class,
            parentColumns = ["tagId"],
            childColumns = ["tagId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class PhotoTagMapEntity(
    @ColumnInfo(name = "photoUri")
    val photoUri: String,

    @ColumnInfo(name = "tagId")
    val tagId: Long,

    @ColumnInfo(name = "confidence")
    val confidence: Float,

    @ColumnInfo(name = "source")
    val source: String
)
