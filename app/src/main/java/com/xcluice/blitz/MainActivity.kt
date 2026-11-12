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
import com.xcluice.blitz.engine.Role
import kotlinx.coroutines.delay
import com.xcluice.blitz.engine.SendItem
import com.xcluice.blitz.engine.Nearby
import com.xcluice.blitz.engine.Peer
import com.xcluice.blitz.ui.DiscoverScreen
import com.xcluice.blitz.ui.HistoryScreen
import com.xcluice.blitz.ui.ProfileScreen
import androidx.compose.runtime.DisposableEffect
import com.xcluice.blitz.ui.AppEntry
import com.xcluice.blitz.ui.buildBundle
import com.xcluice.blitz.ui.isBundle
import com.xcluice.blitz.ui.GlassBg
import com.xcluice.blitz.ui.LocalTilt
import com.xcluice.blitz.ui.rememberTilt
import androidx.compose.runtime.CompositionLocalProvider
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
    if (parts.size == 1 && isBundle(parts[0].name)) {
        scope.launch(Dispatchers.IO) {
            val err = ApkInstaller.installBundle(ctx, parts[0])
            if (err != null) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(ctx, err, Toast.LENGTH_LONG).show()
                }
            }
        }
        return
    }
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

    var update by remember { mutableStateOf<com.xcluice.blitz.engine.UpdateInfo?>(null) }
    var updateBusy by remember { mutableStateOf(false) }
    var updatePct by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        update = withContext(Dispatchers.IO) { com.xcluice.blitz.engine.Updater.check(ctx) }
    }
    var crash by remember { mutableStateOf(readCrash(ctx)) }
    var myName by remember { mutableStateOf(Store.name(ctx)) }
    var myAvatar by remember { mutableStateOf(Store.avatar(ctx)) }
    var profileSet by remember { mutableStateOf(Store.hasProfile(ctx)) }
    var editingProfile by remember { mutableStateOf(false) }
    var draftName by remember { mutableStateOf(myName) }
    var draftAvatar by remember { mutableStateOf(myAvatar) }
    var showHistory by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(Store.history(ctx)) }
    var discovering by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf<Peer?>(null) }
    var discoverMsg by remember { mutableStateOf<String?>(null) }
    var peer by remember { mutableStateOf<Pair<String, Int>?>(null) }

    val nearby by Nearby.peers.collectAsState()
    val request by Nearby.incoming.collectAsState()
    val hostError by Nearby.hostError.collectAsState()
    val scanError by Nearby.scanError.collectAsState()

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
    var bundles by remember { mutableStateOf<Map<String, SendItem>>(emptyMap()) }
    LaunchedEffect(apps, selectedApps) {
        for (a in apps) {
            if (a.pkg in selectedApps && a.paths.size > 1 && a.pkg !in bundles) {
                val b = withContext(Dispatchers.IO) {
                    try {
                        buildBundle(ctx, a)
                    } catch (_: Throwable) {
                        null
                    }
                }
                if (b != null) bundles = bundles + (a.pkg to b)
            }
        }
    }
    val bundling = apps.any { it.pkg in selectedApps && it.paths.size > 1 && it.pkg !in bundles }
    val appItems = remember(apps, selectedApps, bundles) {
        apps.filter { it.pkg in selectedApps }.flatMap { a ->
            if (a.paths.size > 1) listOfNotNull(bundles[a.pkg]) else appSendItems(a)
        }
    }
    val allItems = pickedItems + appItems
    val fileMetas = pickedItems.map { it.meta }
    val summary = run {
        val parts = ArrayList<String>()
        if (picked.isNotEmpty()) parts.add("${picked.size} file(s)")
        if (selectedApps.isNotEmpty()) parts.add("${selectedApps.size} app(s)")
        if (bundling) "Packing apps…"
        else if (allItems.isEmpty()) "Choose files or apps to send"
        else parts.joinToString(", ") + " · " + fmtBytes(allItems.sumOf { it.meta.size })
    }

    SideEffect {
        view.keepScreenOn = st.phase == Phase.TRANSFERRING ||
            st.phase == Phase.CONNECTING ||
            st.phase == Phase.WAITING
    }

    // Receiver: be visible over Bluetooth while waiting for a sender.
    val hostKey = if (st.phase == Phase.WAITING) st.qr else null
    DisposableEffect(hostKey) {
        if (hostKey != null) Nearby.host(ctx, myName, myAvatar, hostKey)
        onDispose { Nearby.stopHost() }
    }
    LaunchedEffect(request) {
        val r = request
        if (r != null) {
            peer = Pair(r.name, r.avatar)
            delay(25_000)
            if (Nearby.incoming.value === r) Nearby.decide(false)
        }
    }

    // Sender: scan while the Nearby screen is open.
    DisposableEffect(discovering) {
        if (discovering) Nearby.startScan(ctx)
        onDispose { Nearby.stopScan() }
    }
    LaunchedEffect(discovering) {
        while (discovering) {
            Nearby.prune(8_000)
            delay(2_000)
        }
    }

    // History: record each finished transfer once.
    LaunchedEffect(st.phase) {
        if (st.phase == Phase.DONE && st.total > 0) {
            val secs = (st.elapsedMs / 1000.0).coerceAtLeast(0.001)
            val p = peer
            Store.addHistory(
                ctx,
                HistoryRec(
                    System.currentTimeMillis(), st.role == Role.SEND, p?.first ?: "Unknown phone",
                    p?.second ?: 0, st.items.size, st.total, st.total / secs,
                    st.items.firstOrNull()?.name ?: ""
                )
            )
            history = Store.history(ctx)
        }
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
        if (Build.VERSION.SDK_INT >= 31) {
            for (bp in arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT
            )) {
                if (!hasPerm(ctx, bp)) need.add(bp)
            }
        }
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
    BackHandler(enabled = st.phase == Phase.IDLE && picking && !discovering && !showHistory && !editingProfile) { picking = false }
    BackHandler(enabled = st.phase == Phase.IDLE && discovering && asking == null) { discovering = false }
    BackHandler(enabled = st.phase == Phase.IDLE && showHistory) { showHistory = false }
    BackHandler(enabled = st.phase == Phase.IDLE && editingProfile && profileSet) { editingProfile = false }

    val tilt = rememberTilt(st.phase == Phase.IDLE || st.phase == Phase.WAITING)
    CompositionLocalProvider(LocalTilt provides tilt) {
    GlassBg(drift = st.phase != Phase.TRANSFERRING, rate = 1f) {
        Box(Modifier.fillMaxSize().systemBarsPadding()) {
            val screen = when {
                crash != null -> 6
                st.phase != Phase.IDLE -> 2
                editingProfile || !profileSet -> 5
                showHistory -> 4
                discovering -> 3
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
                        updateText = update?.let {
                            if (updateBusy) "Downloading ${it.version}… $updatePct%"
                            else if (it.patchUrl != null) "Update ${it.version} · small download (${fmtBytes(it.patchSize)})"
                            else "Update available: ${it.version}"
                        },
                        updateBusy = updateBusy,
                        onUpdate = {
                            val info = update
                            if (info != null && !updateBusy) {
                                updateBusy = true
                                updatePct = 0
                                scope.launch {
                                    val dl = withContext(Dispatchers.IO) {
                                        com.xcluice.blitz.engine.Updater.download(ctx, info) { updatePct = (it * 100).toInt() }
                                    }
                                    val f = dl?.file
                                    updateBusy = false
                                    if (f == null) {
                                        Toast.makeText(ctx, "Download failed. Check your internet.", Toast.LENGTH_LONG).show()
                                    } else {
                                        val err = com.xcluice.blitz.engine.Updater.install(ctx, f)
                                        if (err != null) Toast.makeText(ctx, err, Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        },
                        myName = myName,
                        myAvatar = myAvatar,
                        onProfile = {
                            draftName = myName
                            draftAvatar = myAvatar
                            editingProfile = true
                        },
                        onHistory = { showHistory = true },
                        onSend = { picking = true },
                        onReceive = {
                            peer = null
                            withPerms { Blitz.startReceive() }
                        }
                    )
                    6 -> com.xcluice.blitz.ui.CrashScreen(
                        report = crash ?: "",
                        onCopy = {
                            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("Blitz crash", crash ?: ""))
                            Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
                        },
                        onDismiss = {
                            clearCrash(ctx)
                            crash = null
                        }
                    )
                    5 -> ProfileScreen(
                        name = if (profileSet || editingProfile) draftName else draftName,
                        avatar = draftAvatar,
                        canBack = profileSet,
                        onName = { draftName = it },
                        onAvatar = { draftAvatar = it },
                        onSave = {
                            Store.saveProfile(ctx, draftName, draftAvatar)
                            myName = draftName.trim().take(14)
                            myAvatar = draftAvatar
                            profileSet = true
                            editingProfile = false
                        },
                        onBack = { editingProfile = false }
                    )
                    4 -> HistoryScreen(
                        records = history,
                        onClear = {
                            Store.clearHistory(ctx)
                            history = emptyList()
                        },
                        onBack = { showHistory = false }
                    )
                    3 -> DiscoverScreen(
                        myAvatar = myAvatar,
                        peers = nearby,
                        asking = asking,
                        message = discoverMsg ?: scanError,
                        onPick = { p ->
                            if (asking == null) {
                                asking = p
                                discoverMsg = null
                                scope.launch {
                                    Nearby.stopScan()
                                    val first = pickedItems.firstOrNull()?.meta?.name
                                        ?: appItems.firstOrNull()?.meta?.name ?: ""
                                    val res = Nearby.requestPairing(
                                        ctx, p, myName, myAvatar, allItems.size,
                                        allItems.sumOf { it.meta.size }, first
                                    )
                                    asking = null
                                    val pairing = if (res != null) Pairing.parse(res) else null
                                    if (pairing != null) {
                                        peer = Pair(p.name, p.avatar)
                                        discovering = false
                                        Blitz.startSend(allItems, pairing, encrypt)
                                    } else {
                                        discoverMsg = if (res == "N") "${p.name} declined."
                                        else "Couldn't reach ${p.name}. Try again."
                                        Nearby.startScan(ctx)
                                    }
                                }
                            }
                        },
                        onScan = {
                            discovering = false
                            withPerms {
                                val o = ScanOptions()
                                o.setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                o.setPrompt("Scan the code on the receiving phone")
                                o.setBeepEnabled(false)
                                o.setOrientationLocked(false)
                                scanLauncher.launch(o)
                            }
                        },
                        onBack = { discovering = false }
                    )
                    1 -> SendScreen(
                        fileMetas = fileMetas,
                        summary = summary,
                        canSend = allItems.isNotEmpty() && !bundling,
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
                        onRemoveFile = { idx -> picked = picked.filterIndexed { j, _ -> j != idx } },
                        onClear = {
                            picked = emptyList()
                            selectedApps = emptySet()
                        },
                        onFind = {
                            withPerms {
                                discoverMsg = null
                                discovering = true
                            }
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
                        myName = myName,
                        myAvatar = myAvatar,
                        request = request,
                        hostError = hostError,
                        onAccept = { Nearby.decide(true) },
                        onDecline = { Nearby.decide(false) },
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
}
