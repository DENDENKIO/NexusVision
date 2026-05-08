package com.nexus.vision.retail.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [DeliveryRecord::class, ProductMaster::class],
    version = 4,
    exportSchema = false
)
abstract class RetailDatabase : RoomDatabase() {

    abstract fun deliveryDao(): DeliveryDao
    abstract fun productDao(): ProductDao

    companion object {
        @Volatile private var INSTANCE: RetailDatabase? = null

        /** バージョン1→2: department・maker 列を追加 */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE delivery_records ADD COLUMN department TEXT NOT NULL DEFAULT ''"
                )
                db.execSQL(
                    "ALTER TABLE delivery_records ADD COLUMN maker TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        /** バージョン2→3: lastDeliveryDate 列を削除 */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // SQLiteはALTER TABLE DROP COLUMNが使えないため、テーブルを再作成
                db.execSQL("""
                    CREATE TABLE product_master_new (
                        janCode      TEXT NOT NULL PRIMARY KEY,
                        productName  TEXT NOT NULL,
                        spec         TEXT NOT NULL,
                        registeredAt INTEGER NOT NULL
                    )
                """)
                db.execSQL("""
                    INSERT INTO product_master_new (janCode, productName, spec, registeredAt)
                    SELECT janCode, productName, spec, registeredAt FROM product_master
                """)
                db.execSQL("DROP TABLE product_master")
                db.execSQL("ALTER TABLE product_master_new RENAME TO product_master")
            }
        }

        /** バージョン3→4: product_master に maker 列を追加 */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE product_master ADD COLUMN maker TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        fun getInstance(context: Context): RetailDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    RetailDatabase::class.java,
                    "nexus_retail.db"
                )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()
                .also { INSTANCE = it }
            }
    }
}
