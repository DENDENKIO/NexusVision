// ファイルパス: app/src/main/java/com/nexus/vision/sheets/IndexedFile.kt
package com.nexus.vision.sheets

import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import io.objectbox.annotation.Index

/**
 * インデックス登録されたファイルのメタデータ
 * Phase 8.5: NexusSheets
 */
@Entity
data class IndexedFile(
    @Id var id: Long = 0,
    @Index var fileName: String = "",
    var fileType: String = "",        // "csv", "xlsx", "pdf", "code"
    var importedAt: Long = 0,         // System.currentTimeMillis()
    var rowCount: Int = 0,
    var colCount: Int = 0,
    var headerJson: String = "",      // JSON array of column header names: ["地域","売上","利益"]
    var summary: String = ""          // 自動生成サマリ
)
