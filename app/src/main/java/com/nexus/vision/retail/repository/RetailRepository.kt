package com.nexus.vision.retail.repository

import com.nexus.vision.retail.db.*
import com.nexus.vision.retail.search.FuzzyNormalizer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * 納品・商品データベースの操作窓口
 *
 * 保存ルール:
 *  納品: date + janCode + quantity が全て同じ → スキップ
 *  商品: janCode既存 → スキップ (新JANのみ追加)
 */
class RetailRepository(private val db: RetailDatabase) {

    private val deliveryDao = db.deliveryDao()
    private val productDao  = db.productDao()

    // ────────────────────────────────────────────────
    // 納品データ
    // ────────────────────────────────────────────────

    val allDeliveries: Flow<List<DeliveryRecord>> = deliveryDao.observeAll()
    val allProjects:   Flow<List<String>>         = deliveryDao.observeProjects()

    fun deliveriesByProject(project: String): Flow<List<DeliveryRecord>> =
        deliveryDao.observeByProject(project)

    /**
     * 納品レコードを1件保存
     *
     * @return SaveResult
     */
    suspend fun saveDelivery(record: DeliveryRecord): SaveResult {
        // 重複チェック (date + janCode + quantity)
        val dup = deliveryDao.countDuplicate(record.date, record.janCode, record.quantity)
        if (dup > 0) return SaveResult.Duplicate

        val rowId = deliveryDao.insert(record)
        return if (rowId > 0) SaveResult.Saved else SaveResult.Duplicate
    }

    /**
     * 複数件を一括保存 (OCR読み込み後など)
     *
     * @return Pair(保存件数, スキップ件数)
     */
    suspend fun saveDeliveries(records: List<DeliveryRecord>): Pair<Int, Int> {
        var saved = 0; var skipped = 0
        records.forEach { rec ->
            when (saveDelivery(rec)) {
                SaveResult.Saved     -> saved++
                SaveResult.Duplicate -> skipped++
            }
        }
        return Pair(saved, skipped)
    }

    suspend fun updateDelivery(record: DeliveryRecord) = deliveryDao.update(record)
    suspend fun deleteDelivery(record: DeliveryRecord) = deliveryDao.delete(record)

    /**
     * あいまい検索（カタカナ/ひらがな/全角半角 対応）
     * 複数スペース区切り → AND絞り込み
     */
    suspend fun searchDeliveries(query: String, project: String = ""): List<DeliveryRecord> {
        val tokens = FuzzyNormalizer.tokenize(query)
        if (tokens.isEmpty()) return allDeliveries.first()

        // 各トークンで検索 → AND絞り込み（正規化形 + カタカナ両方で検索）
        var results: List<DeliveryRecord>? = null
        for (token in tokens) {
            val kana = FuzzyNormalizer.hiraganaToKatakana(token)
            val hitH = deliveryDao.searchByToken(token, project)
            val hitK = if (kana != token) deliveryDao.searchByToken(kana, project) else emptyList()
            val merged = (hitH + hitK).distinctBy { it.id }
            results = results?.filter { r -> merged.any { it.id == r.id } } ?: merged
        }
        return results ?: emptyList()
    }

    // ────────────────────────────────────────────────
    // 商品データ
    // ────────────────────────────────────────────────

    val allProducts: Flow<List<ProductMaster>> = productDao.observeAll()

    /**
     * 商品マスターに登録 (新JANのみ)
     * 既存JANはスキップして現データを保持
     */
    suspend fun registerProductIfNew(product: ProductMaster): Boolean {
        val rowId = productDao.insertIfNew(product)
        return rowId > 0   // true=新規登録、false=既存スキップ
    }

    suspend fun findProduct(jan: String): ProductMaster? =
        productDao.findByJan(jan)

    suspend fun searchProducts(query: String): List<ProductMaster> {
        val tokens = FuzzyNormalizer.tokenize(query)
        if (tokens.isEmpty()) return productDao.getAll()

        var results: List<ProductMaster>? = null
        for (token in tokens) {
            val kana = FuzzyNormalizer.hiraganaToKatakana(token)
            val hitH = productDao.searchByToken(token)
            val hitK = if (kana != token) productDao.searchByToken(kana) else emptyList()
            val merged = (hitH + hitK).distinctBy { it.janCode }
            results = results?.filter { r -> merged.any { it.janCode == r.janCode } } ?: merged
        }
        return results ?: emptyList()
    }

    suspend fun updateProduct(product: ProductMaster) =
        productDao.update(product)

    suspend fun deleteProduct(product: ProductMaster) =
        productDao.delete(product)

    // ────────────────────────────────────────────────
    // 納品保存 + 商品DB自動登録 (セット処理)
    // ────────────────────────────────────────────────

    /**
     * 納品レコードを保存しつつ、商品DBにも自動登録
     * - 納品: 重複(date+jan+qty)ならスキップ
     * - 商品: 新JANなら追加、既存JANはそのまま保持
     */
    suspend fun saveDeliveryWithProduct(record: DeliveryRecord): SaveResult {
        // 商品マスターへ登録試行（新JANのみ追加）
        registerProductIfNew(
            ProductMaster(
                janCode     = record.janCode,
                productName = record.productName,
                maker       = record.maker,
                spec        = record.spec
            )
        )
        // 納品レコード保存
        return saveDelivery(record)
    }

    /** 一括保存 + 商品DB自動登録 */
    suspend fun saveAllWithProduct(
        records: List<DeliveryRecord>
    ): Pair<Int, Int> {
        var saved = 0; var skipped = 0
        records.forEach { rec ->
            when (saveDeliveryWithProduct(rec)) {
                SaveResult.Saved     -> saved++
                SaveResult.Duplicate -> skipped++
            }
        }
        return Pair(saved, skipped)
    }

    enum class SaveResult { Saved, Duplicate }
}
