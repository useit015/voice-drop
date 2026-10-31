package app.voicedrop.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.voicedrop.data.Repository
import app.voicedrop.data.db.MessageEntity
import app.voicedrop.platform.BackHandler
import app.voicedrop.platform.dynamicScheme

sealed interface Screen {
    data object Chats : Screen
    data class Chat(val jid: String) : Screen
    data object NewChat : Screen
    data object Settings : Screen
    data class Viewer(val msg: MessageEntity) : Screen
}

class Nav(val stack: SnapshotStateList<Screen>) {
    val top get() = stack.last()
    fun push(s: Screen) {
        stack.add(s)
    }
    fun pop() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }
    fun replace(s: Screen) {
        pop()
        push(s)
    }
}

@Composable
fun VoiceDropApp(repo: Repository, openChat: String? = null) {
    VoiceDropTheme(dynamic = null) {
        val status by repo.status.collectAsState()
        val me by repo.me.collectAsState()
        val snackbar = remember { SnackbarHostState() }
        LaunchedEffect(Unit) { repo.notices.collect { snackbar.showSnackbar(it) } }

        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            when {
                status == "starting" -> Unit
                me == null || status == "unpaired" || status == "logged-out" -> LinkScreen(repo)
                else -> Main(repo, openChat)
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 72.dp))
        }
    }
}

@Composable
private fun Main(repo: Repository, openChat: String?) {
    val nav = remember { Nav(mutableStateListOf<Screen>(Screen.Chats).also { if (openChat != null) it.add(Screen.Chat(openChat)) }) }
    BackHandler(enabled = nav.stack.size > 1) { nav.pop() }
    val depth = nav.stack.size
    val prevDepth = remember { intArrayOf(depth) }
    AnimatedContent(
        targetState = nav.top,
        transitionSpec = {
            val forward = depth >= prevDepth[0]
            prevDepth[0] = depth
            if (targetState is Screen.Viewer || initialState is Screen.Viewer) fadeIn(tween(180)) togetherWith fadeOut(tween(180))
            else if (forward) (slideInHorizontally(tween(260)) { it / 3 } + fadeIn(tween(200))) togetherWith fadeOut(tween(150))
            else fadeIn(tween(200)) togetherWith (slideOutHorizontally(tween(260)) { it / 3 } + fadeOut(tween(200)))
        },
        label = "nav",
    ) { screen ->
        when (screen) {
            Screen.Chats -> ChatListScreen(
                repo,
                onOpen = { nav.push(Screen.Chat(it)) },
                onNewChat = { nav.push(Screen.NewChat) },
                onSettings = { nav.push(Screen.Settings) },
            )
            is Screen.Chat -> ChatScreen(repo, screen.jid, onBack = { nav.pop() }, onOpenMedia = { nav.push(Screen.Viewer(it)) })
            Screen.NewChat -> NewChatScreen(repo, onBack = { nav.pop() }, onOpen = { nav.replace(Screen.Chat(it)) })
            Screen.Settings -> SettingsScreen(repo, onBack = { nav.pop() })
            is Screen.Viewer -> MediaViewer(repo, screen.msg, onBack = { nav.pop() })
        }
    }
}
