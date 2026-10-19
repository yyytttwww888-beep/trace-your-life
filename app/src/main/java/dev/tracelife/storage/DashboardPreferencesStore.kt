package dev.tracelife.storage

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import dev.tracelife.dashboard.DashboardPreferencesCodec
import dev.tracelife.dashboard.StoredDashboardPreferences

internal object DashboardPreferencesStore {
    fun read(db: SQLiteDatabase): StoredDashboardPreferences? =
        db.rawQuery("SELECT payload FROM dashboard_preferences WHERE singleton = 1", null).use {
            if (it.moveToFirst()) DashboardPreferencesCodec.decode(it.getString(0)) else null
        }

    fun write(db: SQLiteDatabase, value: StoredDashboardPreferences) {
        val payload = DashboardPreferencesCodec.encode(value)
        db.insertWithOnConflict("dashboard_preferences", null, ContentValues().apply {
            put("singleton", 1); put("payload", payload)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }
}
