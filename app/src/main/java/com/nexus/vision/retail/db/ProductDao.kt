package com.nexus.vision.retail.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ProductDao {

    /**
     * IGNORE: janCode(PK)重複時は既存データを保持しスキップ
     * 新JANコードのみ追加
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfNew(product: ProductMaster): Long

    @Update
    suspend fun update(product: ProductMaster)

    @Delete
    suspend fun delete(product: ProductMaster)

    @Query("SELECT * FROM product_master ORDER BY registeredAt DESC")
    fun observeAll(): Flow<List<ProductMaster>>

    /** JANコードで1件取得 */
    @Query("SELECT * FROM product_master WHERE janCode = :jan LIMIT 1")
    suspend fun findByJan(jan: String): ProductMaster?

    /** 商品名・JANコード・メーカー検索 */
    @Query("""
        SELECT * FROM product_master
        WHERE janCode     LIKE '%' || :query || '%'
           OR productName LIKE '%' || :query || '%'
           OR maker       LIKE '%' || :query || '%'
           OR spec        LIKE '%' || :query || '%'
        ORDER BY productName
    """)
    suspend fun search(query: String): List<ProductMaster>

    @Query("SELECT COUNT(*) FROM product_master")
    suspend fun count(): Int

    /**
     * CSVインポート用 upsert
     * janCode が一致 → productName/maker/spec を上書き
     * 一致なし → 新規追加
     */
    @Query("""
        INSERT INTO product_master (janCode, productName, maker, spec, registeredAt)
        VALUES (:janCode, :productName, :maker, :spec, :registeredAt)
        ON CONFLICT(janCode)
        DO UPDATE SET
            productName      = excluded.productName,
            maker            = excluded.maker,
            spec             = excluded.spec
    """)
    suspend fun upsert(
        janCode:          String,
        productName:      String,
        maker:            String,
        spec:             String,
        registeredAt:     Long
    )
}
