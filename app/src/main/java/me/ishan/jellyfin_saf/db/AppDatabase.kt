package me.ishan.jellyfin_saf.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
	entities = [TrackEntity::class, TrackStateEntity::class, TrackLyricsEntity::class],
	version = 3,
	exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
	abstract fun trackDao(): TrackDao
	
	companion object {
		const val DB_NAME = "jellyfin_media_cache_room.db"
		
		private val MIGRATION_2_3 = object : Migration(2, 3) {
			override fun migrate(db: SupportSQLiteDatabase) {
				db.execSQL("ALTER TABLE track_metadata ADD COLUMN path TEXT")
			}
		}
		
		@Volatile
		private var INSTANCE: AppDatabase? = null
		
		fun getDatabase(context: Context): AppDatabase {
			return INSTANCE ?: synchronized(this) {
				val instance = Room.databaseBuilder(
					context.applicationContext,
					AppDatabase::class.java,
					DB_NAME
				).addMigrations(MIGRATION_2_3).fallbackToDestructiveMigration(true).build()
				INSTANCE = instance
				instance
			}
		}
	}
}