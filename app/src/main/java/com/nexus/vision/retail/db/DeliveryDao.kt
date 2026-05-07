package com.nexus.vision.retail.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface DeliveryDao {

    // ── 挿入 ──────────────────────────────────────────────────
    /**
     * IGNORE: date+janCode+quantity 重複時は何もしない
     * 戻り値: 挿入されたrowId。-1なら重複スキップ
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(record: DeliveryRecord): Long

    // ── 更新・削除 ────────────────────────────────────────────
    @Update
    suspend fun update(record: DeliveryRecord)

    @Delete
    suspend fun delete(record: DeliveryRecord)

    // ── 全件取得 (日付降順) ───────────────────────────────────
    @Query("SELECT * FROM delivery_records ORDER BY date DESC, createdAt DESC")
    fun observeAll(): Flow<List<DeliveryRecord>>

    // ── 企画別 ────────────────────────────────────────────────
    @Query("""
        SELECT * FROM delivery_records
        WHERE projectName = :project
        ORDER BY date DESC
    """)
    fun observeByProject(project: String): Flow<List<DeliveryRecord>>

    // ── 全企画名一覧 ──────────────────────────────────────────
    @Query("SELECT DISTINCT projectName FROM delivery_records ORDER BY projectName")
    fun observeProjects(): Flow<List<String>>

    // ── 検索 (JANコード / 商品名 / 日付 複合) ─────────────────
    @Query("""
        SELECT * FROM delivery_records
        WHERE (:project = '' OR projectName = :project)
          AND (janCode     LIKE '%' || :query || '%'
            OR productName LIKE '%' || :query || '%'
            OR date        LIKE '%' || :query || '%'
            OR note        LIKE '%' || :query || '%')
        ORDER BY date DESC
    """)
    suspend fun search(query: String, project: String = ""): List<DeliveryRecord>

    // ── 重複チェック用 ────────────────────────────────────────
    @Query("""
        SELECT COUNT(*) FROM delivery_records
        WHERE date = :date AND janCode = :jan AND quantity = :qty
    """)
    suspend fun countDuplicate(date: String, jan: String, qty: Int): Int
}
