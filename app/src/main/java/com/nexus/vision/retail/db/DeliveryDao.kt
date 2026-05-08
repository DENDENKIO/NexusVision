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

    /**
     * あいまい検索
     * q: FuzzyNormalizer で正規化済みのトークン（1つ）
     * 呼び出し側でトークンごとに絞り込む
     */
    @Query("""
        SELECT * FROM delivery_records
        WHERE (:project = '' OR projectName = :project)
          AND (
               janCode      LIKE '%' || :q || '%'
            OR productName  LIKE '%' || :q || '%'
            OR maker        LIKE '%' || :q || '%'
            OR department   LIKE '%' || :q || '%'
            OR date         LIKE '%' || :q || '%'
            OR spec         LIKE '%' || :q || '%'
            OR note         LIKE '%' || :q || '%'
            OR projectName  LIKE '%' || :q || '%'
          )
        ORDER BY date DESC
    """)
    suspend fun searchByToken(q: String, project: String = ""): List<DeliveryRecord>

    // ── 重複チェック用 ────────────────────────────────────────
    @Query("""
        SELECT COUNT(*) FROM delivery_records
        WHERE date = :date AND janCode = :jan AND quantity = :qty
    """)
    suspend fun countDuplicate(date: String, jan: String, qty: Int): Int

    /**
     * CSVインポート用 upsert
     * date + janCode + quantity が一致 → 全フィールド上書き (REPLACE相当)
     * 一致なし → 新規追加
     */
    @Query("""
        INSERT INTO delivery_records
            (projectName, date, department, janCode, maker, productName, spec, quantity, note, createdAt)
        VALUES
            (:projectName, :date, :department, :janCode, :maker, :productName, :spec, :quantity, :note, :createdAt)
        ON CONFLICT(date, janCode, quantity)
        DO UPDATE SET
            projectName  = excluded.projectName,
            department   = excluded.department,
            maker        = excluded.maker,
            productName  = excluded.productName,
            spec         = excluded.spec,
            note         = excluded.note
    """)
    suspend fun upsert(
        projectName: String,
        date:        String,
        department:  String,
        janCode:     String,
        maker:       String,
        productName: String,
        spec:        String,
        quantity:    Int,
        note:        String,
        createdAt:   Long
    )

    /** インポート結果確認用: 指定日付+JAN+数量のレコードを取得 */
    @Query("SELECT * FROM delivery_records WHERE date=:date AND janCode=:jan AND quantity=:qty LIMIT 1")
    suspend fun findByKey(date: String, jan: String, qty: Int): DeliveryRecord?
}
