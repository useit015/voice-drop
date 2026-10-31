package app.voicedrop.platform

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
expect fun BackHandler(enabled: Boolean = true, onBack: () -> Unit)

class Pickers(val media: () -> Unit, val documents: () -> Unit, val camera: () -> Unit)

/** System pickers. Picked files are copied into `dir` before `onPicked` runs. */
@Composable
expect fun rememberPickers(dir: String, onPicked: (List<PickedFile>) -> Unit): Pickers

/** Returns a check that is true when the mic may be used; when it isn't, it asks and returns false. */
@Composable
expect fun rememberMicPermission(): () -> Boolean

/** Plays a local video file. `gif` = muted, looping, no controls. */
@Composable
expect fun VideoPlayer(path: String, modifier: Modifier = Modifier, gif: Boolean = false)

/** Wallpaper-based colors where the OS has them (Android 12+). */
@Composable
expect fun dynamicScheme(dark: Boolean): ColorScheme?

expect fun clockTime(ms: Long): String

/** "Today", "Yesterday", "Monday", "12 March 2025". */
expect fun dayLabel(ms: Long, now: Long): String

/** Chat list: time today, "Yesterday", weekday this week, else a short date. */
expect fun listTime(ms: Long, now: Long): String

expect fun sameDay(a: Long, b: Long): Boolean
