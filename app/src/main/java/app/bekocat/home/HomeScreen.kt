package app.bekocat.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.bekocat.R
import app.bekocat.core.timeline.FrameRate
import app.bekocat.core.timeline.TemplateCatalog
import app.bekocat.ui.components.tr
import app.bekocat.ui.components.isArabic
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.unit.sp
import android.widget.Toast
import app.bekocat.core.timeline.Project
import app.bekocat.data.ProjectRepository
import app.bekocat.ui.components.BekocatLogo
import app.bekocat.ui.theme.BkColors
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
fun HomeScreen(onOpen: (String) -> Unit, onSettings: () -> Unit) {
    val ctx = LocalContext.current
    val repo = remember { ProjectRepository(ctx.applicationContext) }
    val scope = rememberCoroutineScope()
    var projects by remember { mutableStateOf<List<Project>>(emptyList()) }
    var creating by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }
    LaunchedEffect(reload) { projects = repo.list() }
    var templates by remember { mutableStateOf(false) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching { repo.importPackage(uri) }
                .onSuccess { onOpen(it.id) }
                .onFailure { Toast.makeText(ctx, "⚠ " + (it.message ?: "invalid package"), Toast.LENGTH_LONG).show() }
        }
    }
    if (templates) TemplatePicker(onDismiss = { templates = false }) { id ->
        templates = false
        scope.launch { val p = TemplateCatalog.build(id, repo.newProjectId(), System.currentTimeMillis()); repo.save(p); onOpen(p.id) }
    }

    Box(Modifier.fillMaxSize().background(BkColors.Background).statusBarsPadding().navigationBarsPadding()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                BekocatLogo(44.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
                    Text(stringResource(R.string.tagline), color = BkColors.Muted, style = MaterialTheme.typography.bodyMedium)
                }
                IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, stringResource(R.string.home_settings)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { templates = true }) { Text(tr("قوالب جاهزة (25)", "Templates (25)")) }
                OutlinedButton(onClick = { importer.launch(arrayOf("*/*")) }) { Text(tr("استيراد ‎.bekocat", "Import .bekocat")) }
            }
            Text(stringResource(R.string.home_recent), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 8.dp))
            if (projects.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.home_empty), color = BkColors.Muted)
                }
            } else {
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(projects, key = { it.id }) { p ->
                        Row(
                            Modifier.fillMaxWidth().background(BkColors.Surface, RoundedCornerShape(14.dp))
                                .clickable { onOpen(p.id) }.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(52.dp).background(BkColors.Raised, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                                Text(if (p.sequence.width < p.sequence.height) "9:16" else if (p.sequence.width == p.sequence.height) "1:1" else "16:9",
                                    color = BkColors.Primary, style = MaterialTheme.typography.labelMedium)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(p.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                                val clips = p.sequence.tracks.sumOf { it.clips.size }
                                Text(stringResource(R.string.home_clips, clips, DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(p.modifiedAtMs))),
                                    color = BkColors.Muted, style = MaterialTheme.typography.bodyMedium)
                            }
                            TextButton(onClick = { scope.launch { repo.duplicate(p); reload++ } }) { Text(tr("نسخ", "Copy"), color = BkColors.Muted) }
                            IconButton(onClick = { scope.launch { repo.delete(p.id); reload++ } }) {
                                Icon(Icons.Filled.Delete, stringResource(R.string.home_delete), tint = BkColors.Muted)
                            }
                        }
                    }
                    item { Spacer(Modifier.height(96.dp)) }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { creating = true },
            icon = { Icon(Icons.Filled.Add, null) },
            text = { Text(stringResource(R.string.home_new_project)) },
            containerColor = BkColors.Primary,
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
        )
    }

    if (creating) {
        val defaultName = stringResource(R.string.default_project_name)
        var name by remember { mutableStateOf("") }
        var aspect by remember { mutableStateOf(0) }
        var rate by remember { mutableStateOf(FrameRate.FPS_30) }
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text(stringResource(R.string.home_new_project)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.new_project_name)) }, singleLine = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(aspect == 0, { aspect = 0 }, { Text(stringResource(R.string.aspect_vertical)) })
                        FilterChip(aspect == 1, { aspect = 1 }, { Text(stringResource(R.string.aspect_horizontal)) })
                    }
                    FilterChip(aspect == 2, { aspect = 2 }, { Text(stringResource(R.string.aspect_square)) })
                    Text(stringResource(R.string.frame_rate), style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(FrameRate.FPS_24, FrameRate.FPS_30, FrameRate.FPS_60).forEach { r ->
                            FilterChip(rate == r, { rate = r }, { Text("%.0f".format(r.fps)) })
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(FrameRate.FPS_23_976, FrameRate.FPS_29_97, FrameRate.FPS_59_94).forEach { r ->
                            FilterChip(rate == r, { rate = r }, { Text("%.3f".format(r.fps)) })
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    val (w, h) = when (aspect) { 0 -> 1080 to 1920; 1 -> 1920 to 1080; else -> 1080 to 1080 }
                    val p = Project.create(repo.newProjectId(), name.ifBlank { defaultName }, System.currentTimeMillis(), w, h, rate)
                    scope.launch { repo.save(p); creating = false; onOpen(p.id) }
                }) { Text(stringResource(R.string.create)) }
            },
            dismissButton = { TextButton(onClick = { creating = false }) { Text(stringResource(R.string.cancel)) } },
            containerColor = BkColors.Surface,
        )
    }
}

@Composable
private fun TemplatePicker(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val ar = isArabic()
    val cats = remember { TemplateCatalog.all.map { it.category }.distinct() }
    var cat by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("اختر قالبًا", "Choose a template")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item { FilterChip(cat == null, { cat = null }, { Text(tr("الكل", "All")) }) }
                    items(cats) { c -> FilterChip(cat == c, { cat = c }, { Text(c) }) }
                }
                LazyVerticalGrid(GridCells.Adaptive(140.dp), Modifier.height(420.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    gridItems(TemplateCatalog.all.filter { cat == null || it.category == cat }) { t ->
                        Column(Modifier.background(BkColors.Raised, RoundedCornerShape(12.dp)).clickable { onPick(t.id) }.padding(10.dp)) {
                            Box(Modifier.fillMaxWidth().height(64.dp).background(BkColors.Background, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                                Text(if (t.width < t.height) "9:16" else if (t.width == t.height) "1:1" else "16:9", color = BkColors.Primary)
                            }
                            Text(if (ar) t.ar else t.en, maxLines = 2, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                            Text(t.category, color = BkColors.Muted, fontSize = 11.sp)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        containerColor = BkColors.Surface,
    )
}
