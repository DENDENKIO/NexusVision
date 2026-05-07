package com.nexus.vision.retail.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [DeliveryRecord::class, ProductMaster::class],
    version = 1,
    exportSchema = false
)
abstract class RetailDatabase : RoomDatabase() {

    abstract fun deliveryDao(): DeliveryDao
    abstract fun productDao(): ProductDao

    companion object {
        @Volatile private var INSTANCE: RetailDatabase? = null

        fun getInstance(context: Context): RetailDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    RetailDatabase::class.java,
                    "nexus_retail.db"
                )
                .fallbackToDestructiveMigration()
                .build()
                .also { INSTANCE = it }
            }
    }
}
