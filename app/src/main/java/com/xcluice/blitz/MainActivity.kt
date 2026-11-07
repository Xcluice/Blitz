package com.xcluice.blitz

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.xcluice.blitz.engine.Blitz
import com.xcluice.blitz.engine.Item
import com.xcluice.blitz.engine.Pairing
import com.xcluice.blitz.engine.Phase
import com.xcluice.blitz.engine.SendItem
import com.xcluice.blitz.ui.AppEntry
import com.xcluice.blitz.ui.GlassBg
import com.xcluice.blitz.ui.HomeScreen
import com.xcluice.blitz.ui.RunScreen
import com.xcluice.blitz.ui.SendScreen
import com.xcluice.blitz.ui.appSendItems
import com.xcluice.blitz.ui.fmtBytes
import com.xcluice.blitz.ui.loadApps
import com.xcluice.blitz.engine.ApkInstaller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        if (savedInstanceState == null) handleShare(intent)
        setContent { BlitzRoot() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShare(intent)
    }

    /** Files or apps shared to Blitz from another app's share sheet. */
    private fun handleShare(i: Intent?) {
        if (i == null) return
        val uris = ArrayList<Uri>()
        if (i.action == Intent.ACTION_SEND) {
            val u: Uri? = if (Build.VERSION.SDK_INT >= 33) {
                i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            }
            if (u != null) uris.add(u)
        } else if (i.action == Intent.ACTION_SEND_MULTIPLE) {
            val l: ArrayList<Uri>? = if (Build.VERSION.SDK_INT >= 33) {
                i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
            }
            if (l != null) uris.addAll(l)
        } else {
            return
        }
        if (uris.isEmpty()) {
            val cd = i.clipData
            if (cd != null) {
                for (k in 0 until cd.itemCount) {
                    val u = cd.getItemAt(k).uri
                    if (u != null) uris.add(u)
                }
            }
        }
        if (uris.isNotEmpty()) Incoming.uris.value = uris
    }
}

/** Hand-off from the activity's share intent to the Compose UI. */
object Incoming {
    val uris = MutableStateFlow<List<Uri>>(emptyList())
}

private val WIFI_PERM: String =
    if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES
    else Manifest.permission.ACCESS_FINE_LOCATION

private fun hasPerm(ctx: Context, p: String): Boolean =
    ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

private fun openItem(ctx: Context, item: Item) {
    val u = item.uri ?: return
    try {
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(u, item.mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    } catch (e: Exception) {
        Toast.makeText(ctx, "No app found to open this file", Toast.LENGTH_SHORT).show()
    }
}

/** One file opens normally; several parts (a split app) go through the package installer together. */
private fun openItems(ctx: Context, scope: CoroutineScope, parts: List<Item>) {
    if (parts.size == 1) {
        openItem(ctx, parts[0])
        return
    }
    scope.launch(Dispatchers.IO) {
        val err = ApkInstaller.install(ctx, parts)
        if (err != null) {
            withContext(Dispatchers.Main) {
                Toast.makeText(ctx, err, Toast.LENGTH_LONG).show()
            }
        }
    }
}

@Composable
fun BlitzRoot() {
    val st by Blitz.state.collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val view = LocalView.current

    var picked by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var picking by remember { mutableStateOf(false) }
    var encrypt by remember { mutableStateOf(true) }
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }

    var tab by remember { mutableStateOf(0) }
    var appsRequested by remember { mutableStateOf(false) }
    var appsReady by remember { mutableStateOf(false) }
    var selectedApps by remember { mutableStateOf<Set<String>>(emptySet()) }

    val shared by Incoming.uris.collectAsState()
    LaunchedEffect(shared) {
        if (shared.isNotEmpty()) {
            picked = (picked + shared).distinct()
            tab = 0
            picking = true
            Incoming.uris.value = emptyList()
        }
    }
    val apps by produceState(initialValue = emptyList<AppEntry>(), key1 = appsRequested) {
        if (appsRequested) {
            value = withContext(Dispatchers.Default) { loadApps(ctx) }
            appsReady = true
        }
    }

    val pickedItems = remember(picked) { picked.map { SendItem(it, Blitz.describe(it)) } }
    val appItems = remember(apps, selectedApps) {
        apps.filter { it.pkg in selectedApps }.flatMap { appSendItems(it) }
    }
    val allItems = pickedItems + appItems
    val fileMetas = pickedItems.map { it.meta }
    val summary = run {
        val parts = ArrayList<String>()
        if (picked.isNotEmpty()) parts.add("${picked.size} file(s)")
        if (selectedApps.isNotEmpty()) parts.add("${selectedApps.size} app(s)")
        if (allItems.isEmpty()) "Choose files or apps to send"
        else parts.joinToString(", ") + " · " + fmtBytes(allItems.sumOf { it.meta.size })
    }

    SideEffect {
        view.keepScreenOn = st.phase == Phase.TRANSFERRING ||
            st.phase == Phase.CONNECTING ||
            st.phase == Phase.WAITING
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        val action = pending
        pending = null
        if (hasPerm(ctx, WIFI_PERM)) {
            action?.invoke()
        } else {
            Toast.makeText(
                ctx,
                "Blitz needs Nearby devices (or Location) permission to open a Wi-Fi Direct link.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    fun withPerms(action: () -> Unit) {
        val need = ArrayList<String>()
        if (!hasPerm(ctx, WIFI_PERM)) need.add(WIFI_PERM)
        if (Build.VERSION.SDK_INT >= 33 && !hasPerm(ctx, Manifest.permission.POST_NOTIFICATIONS)) {
            need.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (need.isEmpty()) {
            action()
        } else {
            pending = action
            permLauncher.launch(need.toTypedArray())
        }
    }

    val pickLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) picked = (picked + uris).distinct()
    }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { r ->
        val text = r.contents
        if (text != null) {
            val p = Pairing.parse(text)
            if (p == null) {
                Toast.makeText(ctx, "That isn't a Blitz code", Toast.LENGTH_SHORT).show()
            } else {
                Blitz.startSend(allItems, p, encrypt)
            }
        }
    }

    BackHandler(enabled = st.phase != Phase.IDLE && st.phase != Phase.TRANSFERRING) {
        Blitz.reset()
    }
    BackHandler(enabled = st.phase == Phase.IDLE && picking) { picking = false }

    GlassBg {
        Box(Modifier.fillMaxSize().systemBarsPadding()) {
            val screen = when {
                st.phase != Phase.IDLE -> 2
                picking -> 1
                else -> 0
            }
            AnimatedContent(
                targetState = screen,
                transitionSpec = {
                    (fadeIn(tween(320)) + scaleIn(initialScale = 0.96f, animationSpec = tween(320))) togetherWith
                        fadeOut(tween(200))
                },
                label = "screen"
            ) { s ->
                when (s) {
                    0 -> HomeScreen(
                        onSend = { picking = true },
                        onReceive = { withPerms { Blitz.startReceive() } }
                    )
                    1 -> SendScreen(
                        fileMetas = fileMetas,
                        summary = summary,
                        canSend = allItems.isNotEmpty(),
                        tab = tab,
                        onTab = {
                            tab = it
                            if (it == 1) appsRequested = true
                        },
                        apps = apps,
                        appsLoading = appsRequested && !appsReady,
                        selectedApps = selectedApps,
                        onToggleApp = { pkg ->
                            selectedApps = if (pkg in selectedApps) selectedApps - pkg else selectedApps + pkg
                        },
                        encrypt = encrypt,
                        onEncrypt = { encrypt = it },
                        onAdd = { pickLauncher.launch(arrayOf("*/*")) },
                        onClear = {
                            picked = emptyList()
                            selectedApps = emptySet()
                        },
                        onScan = {
                            withPerms {
                                val o = ScanOptions()
                                o.setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                o.setPrompt("Scan the code on the receiving phone")
                                o.setBeepEnabled(false)
                                o.setOrientationLocked(false)
                                scanLauncher.launch(o)
                            }
                        },
                        onBack = { picking = false }
                    )
                    else -> RunScreen(
                        st = st,
                        onCancel = {
                            val wasDone = st.phase == Phase.DONE
                            Blitz.reset()
                            if (wasDone) {
                                picked = emptyList()
                                selectedApps = emptySet()
                                tab = 0
                                picking = false
                            }
                        },
                        onOpen = { parts -> openItems(ctx, scope, parts) }
                    )
                }
            }
        }
    }
}
