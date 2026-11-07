package com.xcluice.blitz.ui

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable
import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.xcluice.blitz.engine.FileMeta
import com.xcluice.blitz.engine.Item
import com.xcluice.blitz.engine.SendItem
import java.io.File

const val APK_MIME = "application/vnd.android.package-archive"

class AppEntry(
    val pkg: String,
    val label: String,
    val paths: List<String>,
    val size: Long,
    val system: Boolean,
    val icon: Drawable?
)

/** Launchable apps on this phone, sorted by name. Needs no special permission (see manifest queries). */
fun loadApps(ctx: Context): List<AppEntry> {
    val pm = ctx.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val seen = HashSet<String>()
    val out = ArrayList<AppEntry>()
    for (ri in pm.queryIntentActivities(intent, 0)) {
        val ai = ri.activityInfo?.applicationInfo ?: continue
        if (!seen.add(ai.packageName)) continue
        val paths = ArrayList<String>()
        val base = ai.sourceDir
        if (base != null) paths.add(base)
        val splits = ai.splitSourceDirs
        if (splits != null) paths.addAll(splits)
        if (paths.isEmpty()) continue
        var size = 0L
        for (p in paths) size += File(p).length()
        val label = try {
            ri.loadLabel(pm).toString()
        } catch (e: Throwable) {
            ai.packageName
        }
        val icon = try {
            ri.loadIcon(pm)
        } catch (e: Throwable) {
            null
        }
        out.add(
            AppEntry(
                pkg = ai.packageName,
                label = label,
                paths = paths,
                size = size,
                system = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                icon = icon
            )
        )
    }
    return out.sortedBy { it.label.lowercase() }
}

/** An app becomes one APK file, or base + split APKs when the app is split. */
fun appSendItems(app: AppEntry): List<SendItem> {
    val clean = app.label.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { app.pkg }
    val out = ArrayList<SendItem>()
    for (i in app.paths.indices) {
        val f = File(app.paths[i])
        val name = if (i == 0) "$clean.apk" else "$clean.split$i.apk"
        out.add(SendItem(Uri.fromFile(f), FileMeta(name, f.length(), APK_MIME)))
    }
    return out
}

/** One line in the received list. [parts] is the base APK first, then its splits (or just the file itself). */
class ReceivedRow(val item: Item, val parts: List<Item>)

private val SPLIT_RE = Regex("^(.*)\\.split(\\d+)\\.apk$")

/** Folds "Label.split1.apk", "Label.split2.apk" into the "Label.apk" row so they install together. */
fun groupReceived(items: List<Item>): List<ReceivedRow> {
    val names = HashSet<String>()
    for (it in items) names.add(it.name)
    val byBase = HashMap<String, MutableList<Pair<Int, Item>>>()
    val splitIdx = HashSet<Int>()
    for (idx in items.indices) {
        val m = SPLIT_RE.matchEntire(items[idx].name) ?: continue
        val base = m.groupValues[1] + ".apk"
        if (base !in names) continue
        val n = m.groupValues[2].toIntOrNull() ?: continue
        byBase.getOrPut(base) { ArrayList() }.add(Pair(n, items[idx]))
        splitIdx.add(idx)
    }
    val out = ArrayList<ReceivedRow>()
    for (idx in items.indices) {
        if (idx in splitIdx) continue
        val item = items[idx]
        val sp = byBase[item.name]
        if (sp == null) {
            out.add(ReceivedRow(item, listOf(item)))
        } else {
            val parts = ArrayList<Item>()
            parts.add(item)
            for (p in sp.sortedBy { it.first }) parts.add(p.second)
            out.add(ReceivedRow(item, parts))
        }
    }
    return out
}

@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Glass(modifier, radius = 18.dp) {
        Row(Modifier.padding(4.dp)) {
            for (i in options.indices) {
                val bg by animateColorAsState(
                    targetValue = if (i == selected) Pal.Ink else Color.Transparent,
                    animationSpec = tween(220),
                    label = "seg"
                )
                Box(
                    Modifier
                        .weight(1f)
                        .bounceClick { onSelect(i) }
                        .clip(RoundedCornerShape(14.dp))
                        .background(bg)
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = options[i],
                        color = if (i == selected) Pal.Bg else Pal.Ink2,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun AppIcon(d: Drawable?, modifier: Modifier) {
    val bmp = remember(d) {
        if (d == null) null
        else try {
            d.toBitmap(96, 96).asImageBitmap()
        } catch (e: Throwable) {
            null
        }
    }
    if (bmp != null) {
        Image(bitmap = bmp, contentDescription = null, modifier = modifier)
    } else {
        Box(modifier.background(Color.White.copy(alpha = 0.12f)))
    }
}

@Composable
fun AppsPane(
    apps: List<AppEntry>,
    loading: Boolean,
    selected: Set<String>,
    onToggle: (String) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var showSystem by remember { mutableStateOf(false) }
    val list = remember(apps, query, showSystem) {
        apps.filter { (showSystem || !it.system) && it.label.contains(query, ignoreCase = true) }
    }
    Column(Modifier.fillMaxSize().padding(10.dp)) {
        Glass(Modifier.fillMaxWidth(), radius = 16.dp) {
            Box(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(color = Pal.Ink, fontSize = 15.sp),
                    cursorBrush = SolidColor(Pal.Ink),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner ->
                        Box {
                            if (query.isEmpty()) Text("Search apps", color = Pal.Ink3, fontSize = 15.sp)
                            inner()
                        }
                    }
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Show system apps", color = Pal.Ink2, fontSize = 13.sp, modifier = Modifier.weight(1f))
            GlassSwitch(on = showSystem, onChange = { showSystem = it })
        }
        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Spinner(Modifier.size(52.dp))
            }
        } else {
            if (list.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    FloatingBolt(Modifier.size(40.dp))
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(list, key = { it.pkg }) { a ->
                        AppCell(a, a.pkg in selected, onToggle)
                    }
                }
            }
        }
    }
}

@Composable
private fun AppCell(a: AppEntry, on: Boolean, onToggle: (String) -> Unit) {
    val pop by animateFloatAsState(
        targetValue = if (on) 0.84f else 1f,
        animationSpec = spring(dampingRatio = 0.4f, stiffness = 500f),
        label = "pop"
    )
    val ring by animateFloatAsState(if (on) 1f else 0f, label = "ring")
    Column(
        Modifier
            .fillMaxWidth()
            .bounceClick { onToggle(a.pkg) }
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(contentAlignment = Alignment.TopEnd) {
            AppIcon(
                a.icon,
                Modifier
                    .size(52.dp)
                    .graphicsLayer {
                        scaleX = pop
                        scaleY = pop
                    }
                    .clip(RoundedCornerShape(14.dp))
                    .border(2.dp, Color.White.copy(alpha = ring), RoundedCornerShape(14.dp))
            )
            if (on) {
                Box(
                    Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(Pal.Ink),
                    contentAlignment = Alignment.Center
                ) {
                    CheckIcon(Modifier.size(13.dp))
                }
            }
        }
        Text(
            text = a.label,
            color = Pal.Ink,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp, start = 2.dp, end = 2.dp)
        )
        Text(
            text = fmtBytes(a.size),
            color = Pal.Ink3,
            fontSize = 10.sp,
            maxLines = 1
        )
    }
}
