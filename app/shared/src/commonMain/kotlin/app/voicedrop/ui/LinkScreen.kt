package app.voicedrop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.voicedrop.data.Repository
import kotlinx.coroutines.launch

@Composable
fun LinkScreen(repo: Repository) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val sync by repo.sync.collectAsState()
    val status by repo.status.collectAsState()

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        Box(Modifier.size(72.dp).background(MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.GraphicEq, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(40.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text("Link Voice Drop", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Voice Drop connects as a linked device, like WhatsApp Web. Your phone keeps WhatsApp and its notifications.",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))

        if (sync != null || status == "connecting" && repo.me.value != null) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text("Linked! Loading your chats…")
            return@Column
        }

        SecondaryTabRow(selectedTabIndex = tab, containerColor = Color.Transparent) {
            Tab(tab == 0, { tab = 0 }, text = { Text("Phone number") })
            Tab(tab == 1, { tab = 1 }, text = { Text("QR code") })
        }
        Spacer(Modifier.height(24.dp))
        if (tab == 0) PhoneLink(repo) else QrLink(repo)
    }
}

@Composable
private fun PhoneLink(repo: Repository) {
    val scope = rememberCoroutineScope()
    var phone by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    if (code == null) {
        OutlinedTextField(
            value = phone,
            onValueChange = { phone = it.filter { c -> c.isDigit() || c == '+' || c == ' ' } },
            label = { Text("Your WhatsApp number") },
            placeholder = { Text("+212 6 12 34 56 78") },
            supportingText = { Text("With country code") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                busy = true
                error = null
                scope.launch {
                    try {
                        code = repo.pairWithPhone(phone)
                    } catch (t: Throwable) {
                        error = t.message ?: "Couldn't get a code"
                    }
                    busy = false
                }
            },
            enabled = !busy && phone.count { it.isDigit() } >= 8,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
            else Text("Get linking code")
        }
        error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }
        return
    }

    val c = code!!
    val pretty = if (c.length == 8) c.substring(0, 4) + "-" + c.substring(4) else c
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primaryContainer) {
        Row(Modifier.padding(horizontal = 24.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(pretty, fontFamily = FontFamily.Monospace, fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.width(12.dp))
            FilledTonalButton(onClick = { repo.platform.copyText(c) }) {
                Icon(Icons.Default.ContentCopy, null, Modifier.size(18.dp))
            }
        }
    }
    Spacer(Modifier.height(24.dp))
    Steps(
        "Open WhatsApp on your phone (a notification may already be waiting)",
        "Settings → Linked devices → Link a device",
        "Tap “Link with phone number instead”",
        "Enter the code above",
    )
    Spacer(Modifier.height(16.dp))
    TextButton(onClick = { code = null }) { Text("Use a different number") }
}

@Composable
private fun QrLink(repo: Repository) {
    val qr by repo.qr.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { repo.pairWithQr() }
    Box(Modifier.size(280.dp).background(Color.White, RoundedCornerShape(16.dp)).padding(12.dp), contentAlignment = Alignment.Center) {
        val code = qr
        if (code == null) CircularProgressIndicator()
        else {
            val img = remember(code) { repo.platform.qrImage(code, 600) }
            Image(img, "QR code", Modifier.fillMaxSize())
        }
    }
    Spacer(Modifier.height(20.dp))
    Steps(
        "Show this on another screen, or use it from a second phone",
        "WhatsApp → Settings → Linked devices → Link a device",
        "Scan the code",
    )
    Spacer(Modifier.height(8.dp))
    TextButton(onClick = { scope.launch { repo.pairWithQr() } }) { Text("New code") }
}

@Composable
private fun Steps(vararg steps: String) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        steps.forEachIndexed { i, s ->
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.size(24.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                    Text("${i + 1}", style = MaterialTheme.typography.labelMedium)
                }
                Spacer(Modifier.width(12.dp))
                Text(s, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}
