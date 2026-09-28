package com.example.galleryai1

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * setting.md 기획서의 Room 3테이블 구조(`PhotoAlbum`/`TagMaster`/`PhotoTagMap`). 기존 `LabelStore`의
 * SharedPreferences JSON blob 저장 방식을 대체한다.
 *
 * `version = 2`: `PhotoAlbum.analyzed`(Boolean) → `taggingVersion`(Int)로 컬럼을 바꿨다(태깅 임계값
 * 튜닝 시 재분석을 자동으로 유도하기 위함, [TagRepository.TAGGING_VERSION] 참고). 아직 출시 전이라
 * 실기기에 남아있는 구버전 로컬 데이터를 보존할 필요가 없어서 정식 Migration 대신
 * `fallbackToDestructiveMigration()`을 씀 — 스키마가 바뀌면 그냥 새로 만든다(사진 자체가 아니라
 * 태깅 캐시일 뿐이라 다시 분석하면 그만).
 */
@Database(
    entities = [PhotoAlbumEntity::class, TagMasterEntity::class, PhotoTagMapEntity::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun photoAlbumDao(): PhotoAlbumDao
    abstract fun tagMasterDao(): TagMasterDao
    abstract fun photoTagMapDao(): PhotoTagMapDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            instance?.let { return it }
            synchronized(this) {
                instance?.let { return it }
                val db = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "gallery_ai.db"
                ).fallbackToDestructiveMigration(true).build()
                instance = db
                return db
            }
        }
    }
}
