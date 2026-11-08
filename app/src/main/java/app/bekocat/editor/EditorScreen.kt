package app.bekocat.editor

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.FirstPage
import androidx.compose.material.icons.filled.FormatShapes
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LastPage
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Transform
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.bekocat.core.timeline.ClipKind
import app.bekocat.core.timeline.Geometry
import app.bekocat.core.timeline.Rational
import app.bekocat.core.timeline.RenderPlanner
import app.bekocat.editor.panels.PanelHost
import app.bekocat.ui.components.tr
import app.bekocat.ui.theme.BkColors

@Composable
fun EditorScreen(projectId: String, onBack: () -> Unit) {
    val vm: EditorViewModel = viewModel(key = projectId)
    LaunchedEffect(projectId) { vm.open(projectId) }
    DisposableEffect(Unit) { onDispose { vm.preview.pause() } }
    val st by vm.state.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(st.message) { st.message?.let { snack.showSnackbar(it); vm.message(null) } }
    val project = st.project

    BoxWithConstraints(Modifier.fillMaxSize().background(BkColors.Background).statusBarsPadding().navigationBarsPadding().imePadding()) {
        val tablet = maxWidth >= 840.dp
        Column(Modifier.fillMaxSize()) {
            TopBar(st, vm, onBack)
            Row(Modifier.weight(1f).fillMaxWidth()) {
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Preview(st, vm, Modifier.weight(1f).fillMaxWidth())
                    Transport(st, vm)
                    if (project != null) {
                        TimelineView(
                            project = project, playhead = st.playhead, playing = st.playing, selection = st.selection,
                            pxPerSecond = st.pxPerSecond, thumbs = { vm.thumbs.peek(it) }, peaks = { vm.peaks[it] },
                            version = st.thumbsVersion + st.waveVersion, inPoint = st.inPoint, outPoint = st.outPoint,
                            onSeek = vm::seek, onSelect = vm::select, onZoom = vm::setZoom,
                            onMove = vm::moveToTrack, onTrim = vm::trimEdge,
                            onTrackHeader = { id -> vm.toggle { it.copy(primary = null, selection = emptySet()) }; vm.trackPanelFor = id; vm.openPanel(Panel.TRACKS) },
                            modifier = Modifier.height(if (tablet) 300.dp else st.timelineHeightDp.dp),
                        )
                    }
                    st.busy?.let { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    if (!tablet) {
                        if (st.panel != Panel.NONE) PanelHost(st, vm, Modifier.fillMaxWidth().height(320.dp)) else ToolRail(st, vm)
                    } else ToolRail(st, vm)
                }
                if (tablet && st.panel != Panel.NONE) PanelHost(st, vm, Modifier.width(380.dp).fillMaxHeight())
            }
        }
        SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).padding(bottom = 90.dp))
    }
}

@Composable
private fun TopBar(st: EditorState, vm: EditorViewModel, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("رجوع", "Back")) }
        Column(Modifier.weight(1f)) {
            Text(st.project?.name ?: "", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(st.stats, color = BkColors.Muted, fontSize = 10.sp, maxLines = 1)
        }
        IconButton(onClick = vm::undo, enabled = st.canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, tr("تراجع", "Undo")) }
        IconButton(onClick = vm::redo, enabled = st.canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, tr("إعادة", "Redo")) }
        IconButton(onClick = { vm.openPanel(Panel.PROJECT) }) { Icon(Icons.Filled.Settings, tr("المشروع", "Project")) }
        Button(onClick = { vm.openPanel(Panel.EXPORT) }, enabled = st.project != null, modifier = Modifier.padding(end = 4.dp)) {
            Icon(Icons.Filled.FileUpload, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text(tr("تصدير", "Export"))
        }
    }
}

/** Preview: SurfaceView rendered by the GPU engine + on-canvas transform gizmo for the selected layer. */
@Composable
private fun Preview(st: EditorState, vm: EditorViewModel, modifier: Modifier) {
    val project = st.project
    var showGuides by remember { mutableStateOf(true) }
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        if (project == null) return@Box
        val seq = project.sequence
        val ratio = seq.width.toFloat() / seq.height
        Box(Modifier.aspectRatio(ratio, matchHeightConstraintsFirst = ratio < 1.3f)) {
            AndroidView(factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(h: SurfaceHolder) {}
                        override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) { vm.preview.setSurface(h.surface, w, hh) }
                        override fun surfaceDestroyed(h: SurfaceHolder) { vm.preview.setSurface(null, 0, 0) }
                    })
                }
            }, modifier = Modifier.fillMaxSize())
            // Gizmo + gestures (move / pinch-scale / rotate the selected layer; tap to select topmost layer).
            val sel = st.selected
            Canvas(Modifier.fillMaxSize()
                .pointerInput(project, st.primary) {
                    detectTransformGestures { _, pan, zoom, rot ->
                        val c = vm.state.value.selected ?: return@detectTransformGestures
                        if (c.kind == ClipKind.ADJUSTMENT) return@detectTransformGestures
                        vm.previewSelected { cl ->
                            val lt = vm.localTime(cl)
                            val tf = cl.transform
                            fun app.bekocat.core.timeline.Animatable.add(d: Double) = set(valueAt(lt) + d, lt, vm.state.value.autoKey || keyframes.isNotEmpty())
                            fun app.bekocat.core.timeline.Animatable.mul(m: Double) = set(valueAt(lt) * m, lt, vm.state.value.autoKey || keyframes.isNotEmpty())
                            cl.copy(transform = tf.copy(
                                positionX = tf.positionX.add(pan.x / size.width * 2.0), positionY = tf.positionY.add(pan.y / size.height * 2.0),
                                scale = if (zoom != 1f) tf.scale.mul(zoom.toDouble()) else tf.scale,
                                rotation = if (rot != 0f) tf.rotation.add(rot.toDouble()) else tf.rotation,
                            ))
                        }
                        vm.gizmoDirty = true
                    }
                }
                .pointerInput(project) {
                    detectTapGestures(onTap = { pos ->
                        if (vm.gizmoDirty) return@detectTapGestures
                        val p = vm.state.value.project ?: return@detectTapGestures
                        val plan = RenderPlanner.plan(p, vm.playheadTime)
                        val x = pos.x / size.width * p.sequence.width; val y = pos.y / size.height * p.sequence.height
                        val hit = plan.items.filterIsInstance<app.bekocat.core.timeline.PlanItem.Layer>().reversed().firstOrNull { item ->
                            val l = item.layer
                            val w = if (l.contentW > 0) l.contentW else p.sequence.width / 3; val h = if (l.contentH > 0) l.contentH else p.sequence.height / 10
                            val inv = Geometry.invert3(Geometry.homography(Geometry.corners(l.transform, l.parents, w, h, p.sequence.width, p.sequence.height, plan.camera, l.contentW == 0)))
                            val (u, v) = Geometry.apply(inv, x.toDouble(), y.toDouble())
                            u in 0.0..1.0 && v in 0.0..1.0
                        }
                        vm.select(hit?.layer?.clipId)
                    }, onDoubleTap = { vm.togglePlay() })
                }
            ) {
                if (showGuides) {
                    val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 10f))
                    drawRect(Color.White.copy(alpha = 0.25f), Offset(size.width * 0.05f, size.height * 0.05f), androidx.compose.ui.geometry.Size(size.width * 0.9f, size.height * 0.9f), style = Stroke(1.5f, pathEffect = dash))
                    drawRect(Color.White.copy(alpha = 0.15f), Offset(size.width * 0.1f, size.height * 0.1f), androidx.compose.ui.geometry.Size(size.width * 0.8f, size.height * 0.8f), style = Stroke(1f, pathEffect = dash))
                    drawLine(Color.White.copy(alpha = 0.12f), Offset(size.width / 2, 0f), Offset(size.width / 2, size.height))
                    drawLine(Color.White.copy(alpha = 0.12f), Offset(0f, size.height / 2), Offset(size.width, size.height / 2))
                }
                if (sel != null) {
                    val plan = runCatching { RenderPlanner.layerFor(project, seq, sel, vm.playheadTime, 0, false) }.getOrNull()
                    if (plan != null) {
                        val w = if (plan.contentW > 0) plan.contentW else seq.width / 3; val h = if (plan.contentH > 0) plan.contentH else seq.height / 10
                        val c = Geometry.corners(plan.transform, plan.parents, w, h, seq.width, seq.height, null, plan.contentW == 0)
                        val sx = size.width / seq.width; val sy = size.height / seq.height
                        for (i in 0 until 4) {
                            val a = Offset((c[i * 2] * sx).toFloat(), (c[i * 2 + 1] * sy).toFloat())
                            val b = Offset((c[((i + 1) % 4) * 2] * sx).toFloat(), (c[((i + 1) % 4) * 2 + 1] * sy).toFloat())
                            drawLine(BkColors.Primary, a, b, 2.5f)
                            drawCircle(BkColors.Accent, 7f, a)
                        }
                    }
                }
            }
        }
        Row(Modifier.align(Alignment.TopEnd).padding(4.dp)) {
            TextButton(onClick = { showGuides = !showGuides }) { Text(if (showGuides) "▦" else "▢", color = Color.White) }
            if (vm.gizmoDirty) TextButton(onClick = { vm.commitGizmo() }) { Text("✓", color = BkColors.Accent, fontSize = 18.sp) }
        }
    }
}

@Composable
private fun Transport(st: EditorState, vm: EditorViewModel) {
    Row(Modifier.fillMaxWidth().background(BkColors.Surface).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        val tc = st.project?.sequence?.frameRate?.timecode(Rational.fromDouble(st.playhead, 1_000_000)) ?: "00:00:00:00"
        Text(tc, style = MaterialTheme.typography.labelLarge, color = BkColors.Accent, modifier = Modifier.width(100.dp))
        TextButton(onClick = { vm.setInOut(true) }) { Text("I", color = if (st.inPoint != null) BkColors.Accent else BkColors.Muted) }
        TextButton(onClick = { vm.setInOut(false) }) { Text("O", color = if (st.outPoint != null) BkColors.Accent else BkColors.Muted) }
        IconButton(onClick = { vm.toggle { it.copy(loop = !it.loop) } }) { Icon(Icons.Filled.Loop, "Loop", tint = if (st.loop) BkColors.Accent else BkColors.Muted) }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = { vm.stepFrame(-1) }) { Icon(Icons.Filled.SkipPrevious, tr("الإطار السابق", "Previous frame")) }
        IconButton(onClick = vm::togglePlay) { Icon(if (st.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (st.playing) tr("إيقاف", "Pause") else tr("تشغيل", "Play")) }
        IconButton(onClick = { vm.stepFrame(1) }) { Icon(Icons.Filled.SkipNext, tr("الإطار التالي", "Next frame")) }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = { vm.setZoom(st.pxPerSecond / 1.6f) }) { Icon(Icons.Filled.ZoomOut, tr("تصغير", "Zoom out")) }
        IconButton(onClick = { vm.setZoom(st.pxPerSecond * 1.6f) }) { Icon(Icons.Filled.ZoomIn, tr("تكبير", "Zoom in")) }
    }
}

private data class Tool(val icon: ImageVector, val ar: String, val en: String, val highlighted: Boolean = false, val action: () -> Unit)

@Composable
private fun ToolRail(st: EditorState, vm: EditorViewModel) {
    val sel = st.selected
    val tools = buildList {
        add(Tool(Icons.Filled.Add, "وسائط", "Media") { vm.openPanel(Panel.MEDIA) })
        if (sel == null) {
            add(Tool(Icons.Filled.TextFields, "نص", "Text") { vm.openPanel(Panel.TEXT) })
            add(Tool(Icons.Filled.FormatShapes, "أشكال", "Shapes") { vm.openPanel(Panel.SHAPES) })
            add(Tool(Icons.Filled.ClosedCaption, "كابشن", "Captions") { vm.openPanel(Panel.CAPTIONS) })
            add(Tool(Icons.Filled.AutoAwesome, "الذكاء", "AI") { vm.openPanel(Panel.AI) })
            add(Tool(Icons.Filled.GraphicEq, "المكسر", "Mixer") { vm.openPanel(Panel.MIXER) })
            add(Tool(Icons.Filled.ContentCut, "قص الكل", "Split all") { vm.split() })
            add(Tool(Icons.Filled.ContentPaste, "لصق", "Paste") { vm.paste(false) })
        } else {
            add(Tool(Icons.Filled.ContentCut, "قص", "Split") { vm.split() })
            add(Tool(Icons.Filled.FirstPage, "قص البداية", "Trim in") { vm.trimStartToPlayhead() })
            add(Tool(Icons.Filled.LastPage, "قص النهاية", "Trim out") { vm.trimEndToPlayhead() })
            add(Tool(Icons.Filled.Delete, "حذف", "Delete") { vm.deleteSelected() })
            add(Tool(Icons.Filled.Transform, "التحويل", "Transform") { vm.openPanel(Panel.INSPECTOR) })
            add(Tool(Icons.Filled.ShowChart, "المنحنيات", "Graph") { vm.openPanel(Panel.KEYFRAMES) })
            if (sel.kind == ClipKind.TEXT) add(Tool(Icons.Filled.TextFields, "النص", "Text") { vm.openPanel(Panel.TEXT) })
            if (sel.kind == ClipKind.SHAPE) add(Tool(Icons.Filled.FormatShapes, "الشكل", "Shape") { vm.openPanel(Panel.SHAPES) })
            add(Tool(Icons.Filled.Speed, "السرعة", "Speed") { vm.openPanel(Panel.SPEED) })
            add(Tool(Icons.Filled.Palette, "اللون", "Color") { vm.openPanel(Panel.COLOR) })
            add(Tool(Icons.Filled.Tune, "المؤثرات", "Effects") { vm.openPanel(Panel.EFFECTS) })
            add(Tool(Icons.Filled.BlurOn, "انتقال", "Transition") { vm.openPanel(Panel.TRANSITION) })
            add(Tool(Icons.Filled.Layers, "أقنعة", "Masks") { vm.openPanel(Panel.MASK) })
            add(Tool(Icons.Filled.Wallpaper, "خلفية/كروما", "Key / BG") { vm.openPanel(Panel.KEY) })
            add(Tool(Icons.AutoMirrored.Filled.VolumeUp, "الصوت", "Audio") { vm.openPanel(Panel.AUDIO) })
            add(Tool(Icons.Filled.ContentCopy, "نسخ", "Copy") { vm.copy() })
            add(Tool(Icons.Filled.Category, "تجميع", "Group") { if (sel.groupId != null) vm.ungroup() else vm.group() })
            add(Tool(Icons.Filled.Timeline, "دمج", "Precompose") { vm.nest() })
            add(Tool(Icons.Filled.AutoAwesome, "الذكاء", "AI") { vm.openPanel(Panel.AI) })
        }
        add(Tool(if (st.ripple) Icons.Filled.Link else Icons.Filled.LinkOff, "Ripple", "Ripple", st.ripple) { vm.toggle { it.copy(ripple = !it.ripple) } })
        add(Tool(Icons.Filled.Timeline, "التقاط", "Snap", st.snapping) { vm.toggle { it.copy(snapping = !it.snapping) } })
    }
    Row(Modifier.fillMaxWidth().background(BkColors.Surface).horizontalScroll(rememberScrollState()).padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(0.dp)) {
        tools.forEach { t ->
            TextButton(onClick = t.action, modifier = Modifier.width(72.dp).height(64.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(t.icon, null, tint = if (t.highlighted) BkColors.Accent else BkColors.Text)
                    Text(tr(t.ar, t.en), fontSize = 10.sp, maxLines = 1, textAlign = TextAlign.Center, color = BkColors.Muted)
                }
            }
        }
    }
}
