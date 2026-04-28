// ファイルパス: app/src/main/java/com/nexus/vision/sheets/IndexedRow.kt
package com.nexus.vision.sheets

import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import io.objectbox.annotation.Index

/**
 * インデックス登録された行データ
 * Phase 8.5: NexusSheets
 */
@Entity
data class IndexedRow(
    @Id var id: Long = 0,
    @Index var fileId: Long = 0,      // IndexedFile.id
    var rowIndex: Int = 0,            // 0-based 行番号
    var cellsJson: String = "",       // JSON array: ["東京","1500","800"]
    @Index var searchText: String = "" // 全セル結合テキスト（検索用）: "東京 1500 800"
)
