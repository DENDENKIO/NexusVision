package com.nexus.vision.retail.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [DeliveryRecord::class, ProductMaster::class],
    version = 2,
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

        fun getInstance(context: Context): RetailDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    RetailDatabase::class.java,
                    "nexus_retail.db"
                )
                .addMigrations(MIGRATION_1_2)
                .build()
                .also { INSTANCE = it }
            }
    }
}
