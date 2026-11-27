package com.jarves.mh.multiagent.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.jarves.mh.R
import com.jarves.mh.model.Project
import com.jarves.mh.multiagent.engine.MultiPartExecutionPlan
import com.jarves.mh.multiagent.model.ActivityType
import com.jarves.mh.multiagent.model.AgentSlot
import com.jarves.mh.multiagent.model.AgentState
import com.jarves.mh.multiagent.model.PrioritizedCollaborationEvent
import com.jarves.mh.multiagent.supervisor.SupervisorUiSnapshot
import com.jarves.mh.multiagent.tasks.TeamTask
import com.jarves.mh.provider.core.ProviderConfig
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

private data class BackgroundStar(
    val nx: Float,
    val ny: Float,
    val depth: Float,
    val radiusPx: Float,
    val alpha: Float,
)

@Composable
fun AiUniverse3DScreen(
    activeProject: Project?,
    allProjects: List<Project>,
    agents: List<AgentState>,
    tasks: List<TeamTask>,
    multiPartPlan: MultiPartExecutionPlan?,
    activeSnapshot: SupervisorUiSnapshot?,
    collaborationEvents: List<PrioritizedCollaborationEvent>,
    providerConfigs: List<ProviderConfig>,
    isTeamRunning: Boolean,
    onSelectProject: (Project) -> Unit = {},
    onOpenAgentConfig: (AgentSlot) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // Lifecycle-aware pause so 3D animations never consume CPU/GPU when app is backgrounded
    val lifecycleOwner = LocalLifecycleOwner.current
    var isLifecycleActive by remember { mutableStateOf(true) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> isLifecycleActive = true
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> isLifecycleActive = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var is2DFallback by rememberSaveable { mutableStateOf(false) }
    var selectedNodeId by rememberSaveable { mutableStateOf<String?>(null) }
    var camera by remember { mutableStateOf(UniverseCamera.Default) }
    var targetCamera by remember { mutableStateOf<UniverseCamera?>(null) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var animPhase by remember { mutableFloatStateOf(0f) }

    val graph = remember(activeProject, allProjects, agents, activeSnapshot, collaborationEvents, isTeamRunning) {
        UniverseGraphBuilder.buildFromRuntime(
            activeProject = activeProject,
            allTeamProjects = allProjects,
            agents = agents,
            activeSnapshot = activeSnapshot,
            collaborationEvents = collaborationEvents,
            isTeamRunning = isTeamRunning,
        )
    }

    val hasAnyLiveFlow = remember(graph) { graph.edges.any { it.hasLiveFlow } || graph.nodes.any { it.isActive } }

    // Deterministic parallax starfield (pure visual backdrop, never mistaken for project/agent nodes)
    val backgroundStars = remember {
        List(72) { i ->
            val seed = (i + 1) * 1664525L + 1013904223L
            val f1 = ((seed ushr 8) and 0xFFFF).toFloat() / 65535f
            val f2 = ((seed ushr 24) and 0xFFFF).toFloat() / 65535f
            val depth = 0.25f + ((i % 4) * 0.22f)
            BackgroundStar(
                nx = f1,
                ny = f2,
                depth = depth,
                radiusPx = 1.1f + (i % 3) * 0.65f,
                alpha = 0.22f + (i % 5) * 0.12f,
            )
        }
    }

    // Smooth lifecycle-aware frame ticker (throttled when idle to conserve battery/CPU)
    LaunchedEffect(isLifecycleActive, hasAnyLiveFlow, targetCamera) {
        while (isLifecycleActive) {
            val frameDelay = if (hasAnyLiveFlow || targetCamera != null) 33L else 80L
            delay(frameDelay)
            animPhase = (animPhase + (frameDelay / 1000f) * 0.85f) % 1000f
            val tgt = targetCamera
            if (tgt != null) {
                val next = camera.lerpTowards(tgt, 0.16f)
                camera = next
                val dist = kotlin.math.abs(next.yawRad - tgt.yawRad) +
                    kotlin.math.abs(next.pitchRad - tgt.pitchRad) +
                    kotlin.math.abs(next.zoom - tgt.zoom) +
                    kotlin.math.abs(next.panX - tgt.panX) * 0.01f +
                    kotlin.math.abs(next.panY - tgt.panY) * 0.01f
                if (dist < 0.015f) {
                    camera = tgt
                    targetCamera = null
                }
            }
        }
    }

    val projectedNodes = remember(graph, camera, canvasSize, is2DFallback) {
        if (canvasSize.width == 0 || canvasSize.height == 0) emptyList()
        else Universe3DProjector.projectNodes(
            nodes = graph.nodes,
            camera = camera,
            width = canvasSize.width.toFloat(),
            height = canvasSize.height.toFloat(),
            is2DFallback = is2DFallback,
        )
    }
    val projectedById = remember(projectedNodes) { projectedNodes.associateBy { it.node.id } }

    val selectedNode = remember(selectedNodeId, graph) {
        selectedNodeId?.let { id -> graph.nodes.firstOrNull { it.id == id } }
    }

    val spaceBg = remember {
        Brush.verticalGradient(
            colors = listOf(
                Color(0xFF030612),
                Color(0xFF070E24),
                Color(0xFF040814),
            ),
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(spaceBg),
    ) {
        if (graph.isEmpty) {
            // Empty state: never fabricate fake stars/agents
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = Color(0xFF38BDF8),
                    modifier = Modifier.size(44.dp),
                )
                Text(
                    text = stringResource(R.string.manager_universe_empty_title),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                )
                Text(
                    text = stringResource(R.string.manager_universe_empty_desc),
                    color = Color(0xFF94A3B8),
                    fontSize = 12.5.sp,
                )
            }
        } else {
            // Interactive 3D Space Canvas
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { canvasSize = it }
                    .pointerInput(is2DFallback, graph.nodes) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            var totalDragPx = 0f
                            val downPos = down.position
                            var wasMultiTouch = false
                            while (true) {
                                val event = awaitPointerEvent()
                                val pressedCount = event.changes.count { it.pressed }
                                if (pressedCount == 0) break
                                targetCamera = null
                                if (pressedCount >= 2) {
                                    wasMultiTouch = true
                                    val zoomChange = event.calculateZoom()
                                    val panChange = event.calculatePan()
                                    if (zoomChange != 1f || panChange != Offset.Zero) {
                                        camera = camera.zoomed(zoomChange).panned(panChange.x, panChange.y)
                                    }
                                    event.changes.forEach { it.consume() }
                                } else {
                                    val panChange = event.calculatePan()
                                    if (panChange != Offset.Zero) {
                                        totalDragPx += hypot(panChange.x.toDouble(), panChange.y.toDouble()).toFloat()
                                        camera = if (is2DFallback) {
                                            camera.panned(panChange.x, panChange.y)
                                        } else {
                                            camera.rotated(
                                                deltaYaw = panChange.x * 0.0075f,
                                                deltaPitch = panChange.y * 0.0065f,
                                            )
                                        }
                                        event.changes.forEach { it.consume() }
                                    }
                                }
                            }
                            if (!wasMultiTouch && totalDragPx < 16f) {
                                val currentProjected = Universe3DProjector.projectNodes(
                                    nodes = graph.nodes,
                                    camera = camera,
                                    width = size.width.toFloat(),
                                    height = size.height.toFloat(),
                                    is2DFallback = is2DFallback,
                                )
                                val hit = Universe3DProjector.hitTest(currentProjected, downPos.x, downPos.y)
                                if (hit != null) {
                                    selectedNodeId = hit.node.id
                                    targetCamera = Universe3DProjector.focusCameraForNode(hit.node, camera)
                                } else {
                                    selectedNodeId = null
                                }
                            }
                        }
                    },
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height

                    // 1. Subtle deep-space nebulae
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(Color(0xFF1D4ED8).copy(alpha = 0.16f), Color.Transparent),
                            center = Offset(w * 0.25f - camera.yawRad * 26f, h * 0.28f - camera.pitchRad * 18f),
                            radius = minOf(w, h) * 0.58f,
                        ),
                        center = Offset(w * 0.25f - camera.yawRad * 26f, h * 0.28f - camera.pitchRad * 18f),
                        radius = minOf(w, h) * 0.58f,
                    )
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(Color(0xFF7C3AED).copy(alpha = 0.14f), Color.Transparent),
                            center = Offset(w * 0.78f + camera.yawRad * 22f, h * 0.68f + camera.pitchRad * 16f),
                            radius = minOf(w, h) * 0.52f,
                        ),
                        center = Offset(w * 0.78f + camera.yawRad * 22f, h * 0.68f + camera.pitchRad * 16f),
                        radius = minOf(w, h) * 0.52f,
                    )

                    // 2. Parallax background starfield
                    backgroundStars.forEach { star ->
                        val px = ((star.nx * w - camera.yawRad * 45f * star.depth) % w + w) % w
                        val py = ((star.ny * h - camera.pitchRad * 35f * star.depth) % h + h) % h
                        drawCircle(
                            color = Color(0xFFE2E8F0).copy(alpha = star.alpha),
                            radius = star.radiusPx,
                            center = Offset(px, py),
                        )
                    }

                    // 3. Orbital guide ring around the active project
                    if (activeProject != null && agents.isNotEmpty()) {
                        val orbitRadius = if (agents.size <= 2) 1.75f else 1.98f
                        val ringPath = Path()
                        val segments = 48
                        for (s in 0..segments) {
                            val theta = (s.toFloat() / segments) * (2f * PI.toFloat())
                            val pt = Vec3(cos(theta) * orbitRadius, 0f, sin(theta) * orbitRadius)
                            val (rx, ry, _) = Universe3DProjector.projectPoint(pt, camera, w, h, is2DFallback)
                            if (s == 0) ringPath.moveTo(rx, ry) else ringPath.lineTo(rx, ry)
                        }
                        drawPath(
                            path = ringPath,
                            color = Color(0xFF38BDF8).copy(alpha = 0.14f),
                            style = Stroke(width = 1.2f),
                        )
                    }

                    // 4. Thin energy connections & real-event directional particles
                    graph.edges.forEachIndexed { edgeIdx, edge ->
                        val from = projectedById[edge.fromId] ?: return@forEachIndexed
                        val to = projectedById[edge.toId] ?: return@forEachIndexed
                        val baseColor = when (edge.type) {
                            UniverseEdgeType.PROJECT_AGENT -> Color(to.node.accentColorArgb)
                            UniverseEdgeType.MANAGER_AGENT -> Color(0xFFF59E0B)
                            UniverseEdgeType.AGENT_AGENT -> Color(0xFF38BDF8)
                        }
                        val lineAlpha = if (edge.hasLiveFlow) 0.52f else 0.18f
                        val strokeW = if (edge.hasLiveFlow) 2.2f else 1.1f

                        drawLine(
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    baseColor.copy(alpha = lineAlpha * 0.55f),
                                    baseColor.copy(alpha = lineAlpha),
                                    Color(to.node.accentColorArgb).copy(alpha = lineAlpha * 0.7f),
                                ),
                                start = Offset(from.screenX, from.screenY),
                                end = Offset(to.screenX, to.screenY),
                            ),
                            start = Offset(from.screenX, from.screenY),
                            end = Offset(to.screenX, to.screenY),
                            strokeWidth = strokeW,
                            cap = StrokeCap.Round,
                        )

                        // Real-event directional particles ONLY when edge.hasLiveFlow is true (max 3 per edge)
                        if (edge.hasLiveFlow) {
                            val particleCount = 3
                            for (p in 0 until particleCount) {
                                val t = ((animPhase * 0.65f + (p.toFloat() / particleCount) + edgeIdx * 0.17f) % 1f)
                                val px = from.screenX + (to.screenX - from.screenX) * t
                                val py = from.screenY + (to.screenY - from.screenY) * t
                                // Soft glowing trail
                                val trailT = (t - 0.07f).coerceAtLeast(0f)
                                val tx = from.screenX + (to.screenX - from.screenX) * trailT
                                val ty = from.screenY + (to.screenY - from.screenY) * trailT
                                drawLine(
                                    color = baseColor.copy(alpha = 0.45f),
                                    start = Offset(tx, ty),
                                    end = Offset(px, py),
                                    strokeWidth = 3.2f,
                                    cap = StrokeCap.Round,
                                )
                                drawCircle(
                                    color = Color.White.copy(alpha = 0.92f),
                                    radius = 3.4f,
                                    center = Offset(px, py),
                                )
                            }
                        }
                    }

                    // 5. Glowing 3D Spherical Nodes (sorted back-to-front for true 3D depth)
                    projectedNodes.forEach { pn ->
                        val node = pn.node
                        val accent = Color(node.accentColorArgb)
                        val center = Offset(pn.screenX, pn.screenY)
                        val pulse = if (node.isActive) {
                            1f + 0.12f * sin(animPhase * 4.2f + pn.depthZ)
                        } else 1f
                        val isSelected = selectedNodeId == node.id

                        // Outer atmospheric halo
                        val haloRadius = pn.screenRadius * (if (isSelected) 2.55f else 2.05f) * pulse
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    accent.copy(alpha = if (node.isActive || isSelected) 0.42f else 0.22f),
                                    accent.copy(alpha = 0.06f),
                                    Color.Transparent,
                                ),
                                center = center,
                                radius = haloRadius,
                            ),
                            center = center,
                            radius = haloRadius,
                        )

                        // Tilted orbital ring on Project nodes or active/selected Agent nodes
                        if (node.type == UniverseNodeType.PROJECT || node.isActive || isSelected) {
                            val rx = pn.screenRadius * 1.55f
                            val ry = pn.screenRadius * 0.54f
                            val ringPath = Path()
                            val segs = 28
                            for (i in 0..segs) {
                                val a = (i.toFloat() / segs) * (2f * PI.toFloat())
                                val ox = center.x + cos(a) * rx
                                val oy = center.y + sin(a) * ry
                                if (i == 0) ringPath.moveTo(ox, oy) else ringPath.lineTo(ox, oy)
                            }
                            drawPath(
                                path = ringPath,
                                color = accent.copy(alpha = if (isSelected) 0.72f else 0.36f),
                                style = Stroke(width = if (isSelected) 2f else 1.3f),
                            )
                        }

                        // 3D lit sphere with top-left specular highlight
                        val highlightOffset = Offset(
                            center.x - pn.screenRadius * 0.32f,
                            center.y - pn.screenRadius * 0.32f,
                        )
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = 0.88f),
                                    accent.copy(alpha = 0.95f),
                                    accent.copy(alpha = 0.48f),
                                    Color(0xFF060B18),
                                ),
                                center = highlightOffset,
                                radius = pn.screenRadius * 1.32f,
                            ),
                            center = center,
                            radius = pn.screenRadius,
                        )

                        // Crisp rim / selection ring
                        drawCircle(
                            color = if (node.isError) Color(0xFFEF4444) else if (isSelected) Color.White else accent.copy(alpha = 0.8f),
                            radius = pn.screenRadius + if (isSelected) 2.5f else 0.5f,
                            center = center,
                            style = Stroke(width = if (isSelected) 2.4f else 1.1f),
                        )
                    }
                }

                // 6. Crisp role icons inside orbs + floating star labels
                val density = LocalDensity.current
                projectedNodes.forEach { pn ->
                    val node = pn.node
                    val accent = Color(node.accentColorArgb)
                    val matchedAgent = node.agentSlot?.let { slot -> agents.firstOrNull { it.config.slot == slot } }
                    val nodeIcon: ImageVector = when {
                        node.type == UniverseNodeType.PROJECT -> Icons.Default.Hub
                        matchedAgent != null -> AgentIconCatalog.iconFor(matchedAgent.config)
                        else -> Icons.Default.SmartToy
                    }
                    val iconSizeDp = with(density) { (pn.screenRadius * 0.88f).coerceIn(14f, 28f).toDp() }
                    val iconOffsetPx = with(density) { (iconSizeDp / 2).toPx() }

                    Box(
                        modifier = Modifier
                            .offset {
                                IntOffset(
                                    (pn.screenX - iconOffsetPx).roundToInt(),
                                    (pn.screenY - iconOffsetPx).roundToInt(),
                                )
                            }
                            .size(iconSizeDp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = nodeIcon,
                            contentDescription = node.label,
                            tint = Color.White,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    // Label beneath orb
                    val labelY = pn.screenY + pn.screenRadius + 8f
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .offset {
                                IntOffset(
                                    (pn.screenX - 58.dp.toPx()).roundToInt(),
                                    labelY.roundToInt(),
                                )
                            }
                            .width(116.dp),
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFF070E22).copy(alpha = 0.78f),
                            border = BorderStroke(0.6.dp, accent.copy(alpha = 0.45f)),
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    text = node.label,
                                    color = Color.White,
                                    fontSize = if (node.type == UniverseNodeType.PROJECT) 11.sp else 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = node.statusLabel,
                                    color = if (node.isError) Color(0xFFF87171) else accent,
                                    fontSize = 8.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }

            // Minimal Space HUD (top-right camera controls + bottom-left touch hint)
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                UniverseHudButton(
                    icon = Icons.Default.Add,
                    contentDesc = "Zoom In",
                    onClick = { targetCamera = camera.zoomed(1.22f) },
                )
                UniverseHudButton(
                    icon = Icons.Default.Remove,
                    contentDesc = "Zoom Out",
                    onClick = { targetCamera = camera.zoomed(0.82f) },
                )
                UniverseHudButton(
                    icon = Icons.Default.CenterFocusStrong,
                    contentDesc = stringResource(R.string.manager_universe_reset_camera),
                    onClick = {
                        selectedNodeId = null
                        targetCamera = UniverseCamera.Default
                    },
                )
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFF0F172A).copy(alpha = 0.85f),
                    border = BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.4f)),
                    modifier = Modifier.clickable { is2DFallback = !is2DFallback },
                ) {
                    Text(
                        text = if (is2DFallback) stringResource(R.string.manager_universe_mode_2d) else stringResource(R.string.manager_universe_mode_3d),
                        color = Color(0xFF38BDF8),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                    )
                }
            }

            // Subtle top-left hint pill (disappears when a node panel is open)
            if (selectedNode == null) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFF0A1228).copy(alpha = 0.72f),
                    border = BorderStroke(0.8.dp, Color(0xFF334155).copy(alpha = 0.6f)),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp, start = 12.dp, end = 12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.manager_universe_hint),
                        color = Color(0xFFCBD5E1),
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }

        // Overlay Detail Panel for Selected Agent or Project (no navigation away from AI Universe)
        AnimatedVisibility(
            visible = selectedNode != null,
            enter = slideInVertically(initialOffsetY = { it / 2 }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it / 2 }) + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(10.dp),
        ) {
            val node = selectedNode ?: return@AnimatedVisibility
            when (node.type) {
                UniverseNodeType.AGENT -> {
                    val agent = node.agentSlot?.let { slot -> agents.firstOrNull { it.config.slot == slot } }
                    if (agent != null) {
                        UniverseAgentDetailPanel(
                            node = node,
                            agent = agent,
                            allAgents = agents,
                            tasks = tasks,
                            activeSnapshot = activeSnapshot,
                            collaborationEvents = collaborationEvents,
                            providerConfigs = providerConfigs,
                            onClose = { selectedNodeId = null },
                            onOpenConfig = { onOpenAgentConfig(agent.config.slot) },
                        )
                    }
                }
                UniverseNodeType.PROJECT -> {
                    val proj = allProjects.firstOrNull { it.id == node.projectId } ?: activeProject
                    if (proj != null) {
                        UniverseProjectDetailPanel(
                            node = node,
                            project = proj,
                            allProjects = allProjects,
                            isCurrentActive = proj.id == activeProject?.id,
                            agents = agents,
                            multiPartPlan = multiPartPlan,
                            activeSnapshot = activeSnapshot,
                            collaborationEvents = collaborationEvents,
                            onSwitchProject = { switched ->
                                onSelectProject(switched)
                                selectedNodeId = "project:${switched.id}"
                            },
                            onClose = { selectedNodeId = null },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UniverseHudButton(
    icon: ImageVector,
    contentDesc: String,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = Color(0xFF0F172A).copy(alpha = 0.85f),
        border = BorderStroke(1.dp, Color(0xFF334155).copy(alpha = 0.7f)),
        modifier = Modifier
            .size(34.dp)
            .clickable(onClick = onClick),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = contentDesc, tint = Color(0xFFE2E8F0), modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun UniverseAgentDetailPanel(
    node: UniverseNode,
    agent: AgentState,
    allAgents: List<AgentState>,
    tasks: List<TeamTask>,
    activeSnapshot: SupervisorUiSnapshot?,
    collaborationEvents: List<PrioritizedCollaborationEvent>,
    providerConfigs: List<ProviderConfig>,
    onClose: () -> Unit,
    onOpenConfig: () -> Unit,
) {
    val accent = Color(node.accentColorArgb)
    val providerName = providerConfigs.firstOrNull { it.id == agent.config.providerId }?.name
        ?: agent.config.providerId.ifBlank { "Antigravity" }
    val healthRec = activeSnapshot?.agentHealth?.get(agent.config.slot.id)
    val assignedTasks = tasks.filter { it.assignedSlot == agent.config.slot }
    val recentAgentEvents = collaborationEvents.filter {
        it.event.senderSlot == agent.config.slot || it.event.targetSlot == agent.config.slot
    }.takeLast(4).reversed()
    val connectedPeers = allAgents.filter { it.config.slot != agent.config.slot && it.config.isEnabled }
    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val lastUpdatedStr = remember(healthRec?.lastActivityAtMs, agent.activityState.startedAtElapsedMs) {
        val ts = healthRec?.lastActivityAtMs?.takeIf { it > 0L } ?: System.currentTimeMillis()
        timeFmt.format(Date(ts))
    }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = Color(0xFF0B1328).copy(alpha = 0.96f),
        border = BorderStroke(1.2.dp, accent.copy(alpha = 0.55f)),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 360.dp),
    ) {
        Column(
            modifier = Modifier
                .padding(14.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Header with 3D Orb Avatar + Name + Role + Close
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Agent3DOrbAvatar(
                    icon = AgentIconCatalog.iconFor(agent.config),
                    accent = accent,
                    sizeDp = 46.dp,
                )
                Column(Modifier.weight(1f)) {
                    Text(agent.config.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(agent.config.role.title, color = accent, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OrqenStatusBadge(node.statusLabel, accent)
                IconButton(onClick = onClose, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color(0xFF94A3B8), modifier = Modifier.size(18.dp))
                }
            }

            // Introduction / Persona
            DetailKeyValueRow("คำแนะนำตัว", agent.config.effectivePersonality())
            // Responsibilities
            if (agent.config.role.responsibilities.isNotEmpty()) {
                DetailKeyValueRow("หน้าที่และความรับผิดชอบ", agent.config.role.responsibilities.joinToString(" · "))
            }
            // Capabilities / Tools
            DetailKeyValueRow(
                "ความสามารถ",
                agent.config.role.allowedTools.joinToString(", ").ifBlank { "Workspace Read/Write, Terminal, MCP" },
            )
            // Provider & Model
            DetailKeyValueRow("Provider / Model", "$providerName · ${agent.config.modelId.ifBlank { "Default" }}")
            // Current Task & Progress
            val curTaskText = agent.activityState.currentTask.ifBlank { agent.activityState.lastAction.ifBlank { "Standby" } }
            DetailKeyValueRow("งานที่กำลังทำ", curTaskText)
            if (activeSnapshot != null && activeSnapshot.totalSteps > 0 && activeSnapshot.activeAgentId == agent.config.slot.id) {
                val pct = ((activeSnapshot.completedSteps.toFloat() / activeSnapshot.totalSteps.toFloat()) * 100f).roundToInt()
                DetailKeyValueRow("ความคืบหน้าของงาน", "${activeSnapshot.completedSteps}/${activeSnapshot.totalSteps} ขั้นตอน ($pct%)")
            }
            // Assigned Tasks
            if (assignedTasks.isNotEmpty()) {
                DetailKeyValueRow("Task ที่ได้รับมอบหมาย", assignedTasks.take(3).joinToString(" | ") { it.title })
            }
            // Recent Work
            if (recentAgentEvents.isNotEmpty()) {
                val latestEv = recentAgentEvents.first().event
                DetailKeyValueRow("งานล่าสุด", latestEv.message.ifBlank { latestEv.type.name })
            }
            // Connected Agents
            if (connectedPeers.isNotEmpty()) {
                DetailKeyValueRow("ทำงานร่วมกับ", connectedPeers.joinToString(", ") { it.config.name })
            }
            // Last Updated
            DetailKeyValueRow("อัปเดตล่าสุด", lastUpdatedStr)

            OutlinedButton(
                onClick = onOpenConfig,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, accent.copy(alpha = 0.6f)),
            ) {
                Icon(Icons.Default.Tune, contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("ตั้งค่า Agent", color = accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun UniverseProjectDetailPanel(
    node: UniverseNode,
    project: Project,
    allProjects: List<Project>,
    isCurrentActive: Boolean,
    agents: List<AgentState>,
    multiPartPlan: MultiPartExecutionPlan?,
    activeSnapshot: SupervisorUiSnapshot?,
    collaborationEvents: List<PrioritizedCollaborationEvent>,
    onSwitchProject: (Project) -> Unit,
    onClose: () -> Unit,
) {
    val accent = Color(node.accentColorArgb)
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = Color(0xFF0B1328).copy(alpha = 0.96f),
        border = BorderStroke(1.2.dp, accent.copy(alpha = 0.55f)),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 340.dp),
    ) {
        Column(
            modifier = Modifier
                .padding(14.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Agent3DOrbAvatar(icon = Icons.Default.Hub, accent = accent, sizeDp = 42.dp)
                Column(Modifier.weight(1f)) {
                    Text(project.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("/team_workspaces/${project.slug}", color = Color(0xFF94A3B8), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OrqenStatusBadge(node.statusLabel, accent)
                IconButton(onClick = onClose, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color(0xFF94A3B8), modifier = Modifier.size(18.dp))
                }
            }

            if (isCurrentActive) {
                DetailKeyValueRow("งานปัจจุบัน", activeSnapshot?.activeStepTitle ?: activeSnapshot?.statusDetail ?: "พร้อมรับคำสั่ง")
                if (activeSnapshot != null && activeSnapshot.totalSteps > 0) {
                    val pct = ((activeSnapshot.completedSteps.toFloat() / activeSnapshot.totalSteps.toFloat()) * 100f).roundToInt()
                    DetailKeyValueRow("ความคืบหน้าโดยรวม", "${activeSnapshot.completedSteps}/${activeSnapshot.totalSteps} ขั้นตอน ($pct%)")
                }
                if (multiPartPlan != null) {
                    val done = multiPartPlan.parts.count { it.status == com.jarves.mh.multiagent.engine.ExecutionPartStatus.COMPLETED }
                    DetailKeyValueRow("แผนงานปัจจุบัน", "${multiPartPlan.title} (v${multiPartPlan.version}) · $done/${multiPartPlan.parts.size} Parts")
                }
                DetailKeyValueRow("Agents ที่เชื่อมต่อ", agents.joinToString(", ") { it.config.name }.ifBlank { "ไม่มี" })
                if (collaborationEvents.isNotEmpty()) {
                    val lastEv = collaborationEvents.last().event
                    DetailKeyValueRow("กิจกรรมล่าสุด", lastEv.message.ifBlank { lastEv.type.name })
                }
            } else {
                Button(
                    onClick = { onSwitchProject(project) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = accent),
                ) {
                    Text("สลับไปยังโปรเจกต์นี้", color = Color(0xFF040814), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }

            if (allProjects.size > 1) {
                Text("โปรเจกต์ทั้งหมด (${allProjects.size})", color = Color(0xFF94A3B8), fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(allProjects, key = { it.id }) { p ->
                        val selected = p.id == project.id
                        FilterChip(
                            selected = selected,
                            onClick = { onSwitchProject(p) },
                            label = { Text(p.name, fontSize = 11.sp, maxLines = 1) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun Agent3DOrbAvatar(
    icon: ImageVector,
    accent: Color,
    sizeDp: androidx.compose.ui.unit.Dp = 42.dp,
) {
    Box(
        modifier = Modifier
            .size(sizeDp)
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.85f),
                        accent,
                        accent.copy(alpha = 0.45f),
                        Color(0xFF060B18),
                    ),
                ),
            )
            .border(1.2.dp, accent.copy(alpha = 0.85f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(sizeDp * 0.52f),
        )
    }
}

@Composable
private fun DetailKeyValueRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(label, color = Color(0xFF94A3B8), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        Text(value, color = Color(0xFFF1F5F9), fontSize = 12.sp)
    }
}
