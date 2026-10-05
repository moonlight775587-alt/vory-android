package dev.vory.android

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dev.vory.android.data.AppRepository
import dev.vory.android.data.GatewayStore
import dev.vory.android.notifications.VoryNotifications

class VoryApp : Application() {

    lateinit var store: GatewayStore
        private set
    lateinit var repository: AppRepository
        private set

    override fun onCreate() {
        super.onCreate()
        store = GatewayStore(this)
        repository = AppRepository(this, store, AppRepository.defaultHttp())
        VoryNotifications(this).ensureChannels()

        // Socket-driven only: pause aggressive reconnect while backgrounded (Doze),
        // resume on user return. No polling loops anywhere.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                repository.backgrounded = true
            }

            override fun onStart(owner: LifecycleOwner) {
                repository.backgrounded = false
            }
        })
    }
}
