// ファイルパス: app/src/main/java/com/nexus/vision/cache/CacheEntities.kt

package com.nexus.vision.cache

import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import io.objectbox.annotation.Index

/**
 * L1 キャッシュエンティティ: pHash → 推論結果
 *
 * 最大500エントリ。ハミング距離 ≤ 8 でヒット。
 *
 * Phase 3: 基本実装
 * Phase 6: EASS パイプラインでタイル単位キャッシュに拡張
 */
@Entity
data class PHashCacheEntry(
    @Id var id: Long = 0,

    /** 64bit pHash 値 */
    @Index var pHash: Long = 0,

    /** 推論結果テキスト */
    var resultText: String = "",

    /** 推論カテゴリ（classify, enhance, ocr 等） */
    @Index var category: String = "",

    /** エントロピー値（DEOR 参照用） */
    var entropy: Double = 0.0,

    /** FECS スコア（EASS 参照用） */
    var fecsScore: Double = 0.0,

    /** 作成タイムスタンプ (epoch ms) */
    var createdAt: Long = System.currentTimeMillis(),

    /** 最終アクセスタイムスタンプ (epoch ms) */
    var lastAccessedAt: Long = System.currentTimeMillis(),

    /** アクセス回数 */
    var accessCount: Int = 0
)

/**
 * L2 キャッシュエンティティ: テキストクエリ → 応答
 *
 * 最大200エントリ。完全一致で検索。
 *
 * Phase 3: 基本実装
 * Phase 11: 長期記憶（HNSW ベクトル検索）に拡張
 */
@Entity
data class InferenceCacheEntry(
    @Id var id: Long = 0,

    /** クエリテキスト */
    @Index var queryText: String = "",

    /** クエリのハッシュ（高速検索用） */
    @Index var queryHash: Int = 0,

    /** 応答テキスト */
    var responseText: String = "",

    /** 作成タイムスタンプ */
    var createdAt: Long = System.currentTimeMillis(),

    /** 最終アクセスタイムスタンプ */
    var lastAccessedAt: Long = System.currentTimeMillis(),

    /** アクセス回数 */
    var accessCount: Int = 0
)

// TODO Phase 11: ベクトルエンティティ
// @Entity
// data class MemoryVectorEntry(
//     @Id var id: Long = 0,
//     @HnswIndex(dimensions = 384) var embedding: FloatArray = floatArrayOf(),
//     var text: String = "",
//     var createdAt: Long = System.currentTimeMillis()
// )
