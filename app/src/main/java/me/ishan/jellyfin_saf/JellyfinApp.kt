package me.ishan.jellyfin_saf

import android.app.Application
import android.content.Context

class JellyfinApp : Application() {
	override fun onCreate() {
		super.onCreate()
		context = applicationContext
	}
	
	companion object {
		@Volatile
		var context: Context? = null
	}
}
