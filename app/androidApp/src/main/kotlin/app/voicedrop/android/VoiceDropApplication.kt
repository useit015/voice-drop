package app.voicedrop.android

import android.app.Application
import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.voicedrop.data.Repository
import app.voicedrop.platform.AndroidPlatform
import app.voicedrop.platform.buildDatabase
import app.voicedrop.platform.installImageLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.TimeUnit

class VoiceDropApplication : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var repo: Repository
        private set

    @Volatile
    var foreground = false
        private set

    override fun onCreate() {
        super.onCreate()
        installImageLoader(this)
        val platform = AndroidPlatform(this, BuildConfig.GIPHY_KEY)
        repo = Repository(EngineBridge(File(filesDir, "wa").absolutePath), buildDatabase(this), platform, scope)
        ProcessLifecycleOwner.get().lifecycle.addObserver(Connection())
        SyncWorker.schedule(this)
    }

    /** Online while the app is visible; drops the socket a minute after it goes to the background. */
    private inner class Connection : DefaultLifecycleObserver {
        private var pendingStop: Job? = null

        override fun onStart(owner: LifecycleOwner) {
            foreground = true
            pendingStop?.cancel()
            scope.launch { repo.connect() }
        }

        override fun onStop(owner: LifecycleOwner) {
            foreground = false
            pendingStop = scope.launch {
                delay(60_000)
                if (!foreground) repo.disconnect()
            }
        }
    }
}

/** Every ~15 minutes: connect briefly so offline messages are already stored when the app opens. */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as VoiceDropApplication
        if (app.foreground || app.repo.me.value == null) return Result.success()
        app.repo.connect()
        withTimeoutOrNull(20_000) { app.repo.status.first { it == "open" } } ?: return Result.retry()
        delay(25_000)
        if (!app.foreground) app.repo.disconnect()
        return Result.success()
    }

    companion object {
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("sync", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
