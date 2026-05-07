package com.nexus.vision.retail.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 商品データベース
 *
 * JANコードを主キーとして重複なし管理
 * 新JANコードのみ追加、既存は上書きしない
 */
@Entity(tableName = "product_master")
data class ProductMaster(
    /** JANコード = 主キー (重複物理防止) */
    @PrimaryKey
    val janCode: String,

    val productName: String,
    val spec: String,

    /** 初回登録日時 */
    val registeredAt: Long = System.currentTimeMillis(),

    /** 最終納品日 (更新用) */
    val lastDeliveryDate: String = ""
)
