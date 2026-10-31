package app.voicedrop.platform

import android.content.Context
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.voicedrop.data.db.AppDatabase
import coil3.ImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.request.crossfade
import coil3.video.VideoFrameDecoder
import kotlinx.coroutines.Dispatchers

fun buildDatabase(ctx: Context): AppDatabase =
    Room.databaseBuilder<AppDatabase>(ctx, ctx.getDatabasePath("voicedrop.db").absolutePath)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .fallbackToDestructiveMigration(dropAllTables = true)
        .build()

fun buildImageLoader(ctx: Context): ImageLoader = ImageLoader.Builder(ctx)
    .components {
        add(AnimatedImageDecoder.Factory())
        add(VideoFrameDecoder.Factory())
    }
    .crossfade(true)
    .build()

fun installImageLoader(ctx: Context) {
    coil3.SingletonImageLoader.setSafe { buildImageLoader(ctx) }
}
