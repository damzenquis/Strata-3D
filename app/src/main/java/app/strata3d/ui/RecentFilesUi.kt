package app.strata3d.ui

import app.strata3d.ui.theme.accentText
import app.strata3d.ui.theme.accentTextButtonColors

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.strata3d.R
import app.strata3d.recent.RecentEntry
import app.strata3d.recent.RecentFiles
import app.strata3d.recent.RecentStatus

/** Loads cached thumbnails for rows, keeping decoded bitmaps in a small in-memory cache. */
class ThumbnailCache(private val loader: suspend (RecentEntry) -> Bitmap?) {
    private val cache = object : android.util.LruCache<String, ImageBitmap>(RecentFiles.MAX_ENTRIES) {}

    fun cached(entry: RecentEntry): ImageBitmap? = entry.file.thumbnail?.let { cache.get(key(entry)) }

    suspend fun load(entry: RecentEntry): ImageBitmap? {
        if (entry.file.thumbnail == null) return null
        cached(entry)?.let { return it }
        val image = loader(entry)?.asImageBitmap() ?: return null
        cache.put(key(entry), image)
        return image
    }

    private fun key(entry: RecentEntry) = "${entry.file.id}/${entry.file.thumbnail}"
}

/** Opens the system picker at the original document's location so the user can select it again. */
class ReopenDocuments : ActivityResultContracts.OpenMultipleDocuments() {
    var initialUri: Uri? = null

    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).apply {
            initialUri?.let { putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) }
        }
}

/** "Recent models" heading with View all, and the last three entries (empty viewer screen). */
@Composable
fun RecentSection(
    entries: List<RecentEntry>,
    thumbnails: ThumbnailCache,
    onOpen: (RecentEntry) -> Unit,
    onReopen: (RecentEntry) -> Unit,
    onViewAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier,
    ) {
        Column(Modifier.padding(vertical = 4.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.recent_models),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(colors = accentTextButtonColors(), onClick = onViewAll) { Text(stringResource(R.string.recent_view_all)) }
            }
            entries.take(3).forEachIndexed { i, entry ->
                if (i > 0) HorizontalDivider(Modifier.padding(start = 72.dp))
                RecentRow(entry, thumbnails, onOpen, onReopen, onRemove = null)
            }
        }
    }
}

@Composable
fun RecentRow(
    entry: RecentEntry,
    thumbnails: ThumbnailCache,
    onOpen: (RecentEntry) -> Unit,
    onReopen: (RecentEntry) -> Unit,
    onRemove: ((RecentEntry) -> Unit)?,
) {
    val context = LocalContext.current
    val available = entry.status == RecentStatus.AVAILABLE
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { if (available) onOpen(entry) else onReopen(entry) }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumbnail(entry, thumbnails)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.file.displayName + if (entry.file.companions.isNotEmpty()) {
                    " " + stringResource(R.string.recent_plus_files, entry.file.companions.size)
                } else "",
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
                color = if (available) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                if (available) metaLine(context, entry) else stringResource(R.string.recent_needs_reopen),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!available) {
            TextButton(colors = accentTextButtonColors(), onClick = { onReopen(entry) }) { Text(stringResource(R.string.recent_reopen)) }
        }
        if (onRemove != null) {
            var menu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.recent_more, entry.file.displayName))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.recent_remove)) },
                        onClick = {
                            menu = false
                            onRemove(entry)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun Thumbnail(entry: RecentEntry, thumbnails: ThumbnailCache) {
    var image by remember(entry.file.id, entry.file.thumbnail) { mutableStateOf(thumbnails.cached(entry)) }
    LaunchedEffect(entry.file.id, entry.file.thumbnail, entry.status) {
        // Unavailable entries show a lock instead, so their thumbnails are never decoded.
        if (image == null && entry.status == RecentStatus.AVAILABLE) image = thumbnails.load(entry)
    }
    Box(
        Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = image
        when {
            entry.status != RecentStatus.AVAILABLE -> Icon(
                Icons.Outlined.Lock, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp),
            )
            bitmap != null -> Image(
                bitmap, contentDescription = null, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(3.dp),
            )
            else -> Icon(
                Icons.Outlined.ViewInAr, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp),
            )
        }
    }
}

private fun metaLine(context: Context, entry: RecentEntry): String {
    val parts = ArrayList<String>(3)
    if (entry.file.format.isNotEmpty()) parts.add(entry.file.format)
    if (entry.file.sizeBytes >= 0) parts.add(Formatter.formatShortFileSize(context, entry.file.sizeBytes))
    parts.add(
        DateUtils.getRelativeTimeSpanString(entry.file.openedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(),
    )
    return parts.joinToString(" · ")
}

/** Full Recent Files list (up to 15 entries) with remove and clear. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecentFilesScreen(
    entries: List<RecentEntry>,
    thumbnails: ThumbnailCache,
    onBack: () -> Unit,
    onOpen: (RecentEntry) -> Unit,
    onReopen: (RecentEntry) -> Unit,
    onRemove: (RecentEntry) -> Unit,
    onClear: () -> Unit,
) {
    BackHandler(onBack = onBack)
    var confirmClear by remember { mutableStateOf(false) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            TopAppBar(
                modifier = Modifier.statusBarsPadding(),
                windowInsets = WindowInsets(0),
                title = { Text(stringResource(R.string.recent_files)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.recent_back))
                    }
                },
                actions = {
                    if (entries.isNotEmpty()) {
                        IconButton(onClick = { confirmClear = true }) {
                            Icon(Icons.Outlined.DeleteSweep, contentDescription = stringResource(R.string.recent_clear))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
            if (entries.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.recent_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize().navigationBarsPadding()) {
                    items(entries, key = { it.file.id }) { entry ->
                        RecentRow(entry, thumbnails, onOpen, onReopen, onRemove)
                        HorizontalDivider(Modifier.padding(start = 72.dp))
                    }
                    item {
                        Text(
                            stringResource(R.string.recent_limit_note, RecentFiles.MAX_ENTRIES),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                        )
                    }
                }
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.recent_clear_title)) },
            text = { Text(stringResource(R.string.recent_clear_body)) },
            confirmButton = {
                TextButton(colors = accentTextButtonColors(), onClick = {
                    confirmClear = false
                    onClear()
                }) { Text(stringResource(R.string.recent_clear)) }
            },
            dismissButton = {
                TextButton(colors = accentTextButtonColors(), onClick = { confirmClear = false }) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }
}

/** Arranges the empty-state call to action and the recent list, side by side in landscape. */
@Composable
fun EmptyLayout(
    isLandscape: Boolean,
    hasRecents: Boolean,
    intro: @Composable (Modifier) -> Unit,
    recents: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (isLandscape && hasRecents) {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.CenterVertically) {
            intro(Modifier.weight(1f))
            recents(Modifier.weight(1f))
        }
    } else {
        Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
            intro(Modifier)
            if (hasRecents) {
                Spacer(Modifier.size(28.dp))
                recents(Modifier.fillMaxWidth())
            }
        }
    }
}
