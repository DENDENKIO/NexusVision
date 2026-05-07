package com.nexus.vision.retail.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Index

/**
 * 納品データベース
 *
 * 重複キー: date + janCode + quantity が全て一致 → 保存しない
 */
@Entity(
    tableName = "delivery_records",
    indices = [Index(
        value = ["date", "janCode", "quantity"],
        unique = true   // ← DB側でも重複を物理的に防ぐ
    )]
)
data class DeliveryRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** 企画名（例: "GW特売"、"通常"） */
    val projectName: String = "通常",

    /** 納品日付 (例: "2026-04-26") */
    val date: String,

    /** JANコード (13桁) */
    val janCode: String,

    /** 商品名 */
    val productName: String,

    /** 規格 (例: "500ml×24") */
    val spec: String,

    /** 納品数量 */
    val quantity: Int,

    /** 備考 (空欄可) */
    val note: String = "",

    /** 登録日時 (System.currentTimeMillis) */
    val createdAt: Long = System.currentTimeMillis()
)
