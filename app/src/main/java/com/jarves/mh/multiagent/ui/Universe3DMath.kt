package com.jarves.mh.multiagent.ui

import com.jarves.mh.model.Project
import com.jarves.mh.multiagent.model.ActivityType
import com.jarves.mh.multiagent.model.AgentSlot
import com.jarves.mh.multiagent.model.AgentState
import com.jarves.mh.multiagent.model.PrioritizedCollaborationEvent
import com.jarves.mh.multiagent.supervisor.AgentHealthState
import com.jarves.mh.multiagent.supervisor.SupervisorUiSnapshot
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Pure 3D math, camera, and real-time graph model for the ORQEN AI Universe.
 * Separated from Jetpack Compose rendering so projection, camera transforms,
 * hit-testing, and real-data graph assembly are deterministic and unit-testable.
 */
data class Vec3(
    val x: Float,
    val y: Float,
    val z: Float,
)

data class UniverseCamera(
    val yawRad: Float = 0.38f,
    val pitchRad: Float = 0.34f,
    val zoom: Float = 1.0f,
    val panX: Float = 0f,
    val panY: Float = 0f,
) {
    fun rotated(deltaYaw: Float, deltaPitch: Float): UniverseCamera = copy(
        yawRad = yawRad + deltaYaw,
        pitchRad = (pitchRad + deltaPitch).coerceIn(-1.28f, 1.28f),
    )

    fun panned(dx: Float, dy: Float): UniverseCamera = copy(
        panX = (panX + dx).coerceIn(-420f, 420f),
        panY = (panY + dy).coerceIn(-420f, 420f),
    )

    fun zoomed(scaleFactor: Float): UniverseCamera = copy(
        zoom = (zoom * scaleFactor).coerceIn(0.45f, 2.6f),
    )

    fun lerpTowards(target: UniverseCamera, t: Float): UniverseCamera {
        val alpha = t.coerceIn(0f, 1f)
        return UniverseCamera(
            yawRad = yawRad + (target.yawRad - yawRad) * alpha,
            pitchRad = pitchRad + (target.pitchRad - pitchRad) * alpha,
            zoom = zoom + (target.zoom - zoom) * alpha,
            panX = panX + (target.panX - panX) * alpha,
            panY = panY + (target.panY - panY) * alpha,
        )
    }

    companion object {
        val Default = UniverseCamera()
    }
}

enum class UniverseNodeType {
    PROJECT,
    AGENT,
}

data class UniverseNode(
    val id: String,
    val type: UniverseNodeType,
    val label: String,
    val subtitle: String,
    val statusLabel: String,
    val isActive: Boolean,
    val isError: Boolean,
    val position: Vec3,
    val baseRadius: Float,
    val accentColorArgb: Long,
    val projectId: String? = null,
    val agentSlot: AgentSlot? = null,
)

enum class UniverseEdgeType {
    PROJECT_AGENT,
    MANAGER_AGENT,
    AGENT_AGENT,
}

data class UniverseEdge(
    val id: String,
    val fromId: String,
    val toId: String,
    val type: UniverseEdgeType,
    val hasLiveFlow: Boolean,
    val flowLabel: String = "",
    val intensity: Float = 0.5f,
)

data class UniverseGraph(
    val nodes: List<UniverseNode>,
    val edges: List<UniverseEdge>,
) {
    val isEmpty: Boolean get() = nodes.isEmpty()
}

data class ProjectedNode(
    val node: UniverseNode,
    val screenX: Float,
    val screenY: Float,
    val depthZ: Float,
    val screenRadius: Float,
    val scale: Float,
)

object Universe3DProjector {
    private const val CAMERA_DISTANCE = 5.4f

    fun transformToCameraSpace(point: Vec3, camera: UniverseCamera): Vec3 {
        val cosY = cos(camera.yawRad)
        val sinY = sin(camera.yawRad)
        val x1 = point.x * cosY - point.z * sinY
        val z1 = point.x * sinY + point.z * cosY

        val cosP = cos(camera.pitchRad)
        val sinP = sin(camera.pitchRad)
        val y2 = point.y * cosP - z1 * sinP
        val z2 = point.y * sinP + z1 * cosP
        return Vec3(x1, y2, z2)
    }

    fun projectPoint(
        point: Vec3,
        camera: UniverseCamera,
        width: Float,
        height: Float,
        is2DFallback: Boolean = false,
    ): Triple<Float, Float, Float> {
        val minDim = minOf(width, height).coerceAtLeast(1f)
        if (is2DFallback) {
            val scale = (minDim * 0.16f) * camera.zoom
            val sx = width * 0.5f + camera.panX + point.x * scale
            val sy = height * 0.5f + camera.panY + point.z * scale * 0.72f + point.y * scale * 0.35f
            return Triple(sx, sy, 0f)
        }
        val cam = transformToCameraSpace(point, camera)
        val denom = (CAMERA_DISTANCE - cam.z).coerceAtLeast(1.1f)
        val focal = minDim * 0.68f * camera.zoom
        val perspective = focal / denom
        val sx = width * 0.5f + camera.panX + cam.x * perspective
        val sy = height * 0.5f + camera.panY - cam.y * perspective
        return Triple(sx, sy, cam.z)
    }

    fun projectNodes(
        nodes: List<UniverseNode>,
        camera: UniverseCamera,
        width: Float,
        height: Float,
        is2DFallback: Boolean = false,
    ): List<ProjectedNode> {
        val minDim = minOf(width, height).coerceAtLeast(1f)
        return nodes.map { node ->
            val (sx, sy, depthZ) = projectPoint(node.position, camera, width, height, is2DFallback)
            val scale = if (is2DFallback) {
                camera.zoom.coerceIn(0.6f, 1.8f)
            } else {
                val denom = (CAMERA_DISTANCE - depthZ).coerceAtLeast(1.1f)
                ((CAMERA_DISTANCE / denom) * camera.zoom).coerceIn(0.42f, 2.4f)
            }
            val radius = (node.baseRadius * (minDim / 390f).coerceIn(0.75f, 1.35f) * scale).coerceIn(14f, 68f)
            ProjectedNode(
                node = node,
                screenX = sx,
                screenY = sy,
                depthZ = depthZ,
                screenRadius = radius,
                scale = scale,
            )
        }.sortedBy { it.depthZ } // Painter's algorithm: far nodes first, near nodes on top
    }

    fun hitTest(
        projectedNodes: List<ProjectedNode>,
        tapX: Float,
        tapY: Float,
        touchSlopPx: Float = 22f,
    ): ProjectedNode? {
        // Test front-most (highest depthZ) first
        return projectedNodes.asReversed().firstOrNull { pn ->
            hypot((tapX - pn.screenX).toDouble(), (tapY - pn.screenY).toDouble()) <= (pn.screenRadius + touchSlopPx)
        }
    }

    fun focusCameraForNode(node: UniverseNode, current: UniverseCamera): UniverseCamera {
        val targetYaw = if (node.type == UniverseNodeType.PROJECT && node.position.x == 0f && node.position.z == 0f) {
            UniverseCamera.Default.yawRad
        } else {
            kotlin.math.atan2(node.position.x, node.position.z) * 0.55f
        }
        return UniverseCamera(
            yawRad = targetYaw,
            pitchRad = 0.28f,
            zoom = if (node.type == UniverseNodeType.PROJECT) 1.18f else 1.34f,
            panX = 0f,
            panY = 0f,
        )
    }
}

/**
 * Builds the 3D constellation strictly from REAL ORQEN runtime state:
 * - Zero fake stars, zero fake agents, zero random fake traffic.
 * - Particles animate ONLY on edges with real active tasks or recent real collaboration events.
 */
object UniverseGraphBuilder {
    private const val RECENT_EVENT_WINDOW_MS = 180_000L

    fun buildFromRuntime(
        activeProject: Project?,
        allTeamProjects: List<Project>,
        agents: List<AgentState>,
        activeSnapshot: SupervisorUiSnapshot?,
        collaborationEvents: List<PrioritizedCollaborationEvent>,
        isTeamRunning: Boolean,
        nowMs: Long = System.currentTimeMillis(),
    ): UniverseGraph {
        val nodes = mutableListOf<UniverseNode>()
        val edges = mutableListOf<UniverseEdge>()

        val uniqueProjects = buildList {
            if (activeProject != null) add(activeProject)
            allTeamProjects.forEach { p -> if (none { it.id == p.id }) add(p) }
        }
        if (uniqueProjects.isEmpty() && agents.isEmpty()) {
            return UniverseGraph(emptyList(), emptyList())
        }

        // 1. Active Project central star + any additional real team projects in outer orbit
        val activeProjNodeId = activeProject?.let { "project:${it.id}" }
        uniqueProjects.forEachIndexed { idx, proj ->
            val isMain = proj.id == activeProject?.id
            val pos = if (isMain) {
                Vec3(0f, 0.08f, 0f)
            } else {
                val outerAngle = ((idx - 1).toFloat() / (uniqueProjects.size - 1).coerceAtLeast(1)) * (2f * PI.toFloat()) + 0.7f
                Vec3(
                    x = cos(outerAngle) * 3.15f,
                    y = sin(outerAngle * 1.5f) * 0.55f + 0.45f,
                    z = sin(outerAngle) * 2.85f,
                )
            }
            val projStatus = when {
                isMain && activeSnapshot?.status == com.jarves.mh.multiagent.supervisor.SupervisorOperationalStatus.COMPLETED -> "Completed"
                isMain && activeSnapshot?.status == com.jarves.mh.multiagent.supervisor.SupervisorOperationalStatus.BLOCKED -> "Blocked"
                isMain && isTeamRunning -> "Active"
                else -> "Standby"
            }
            nodes += UniverseNode(
                id = "project:${proj.id}",
                type = UniverseNodeType.PROJECT,
                label = proj.name,
                subtitle = proj.language.ifBlank { "Workspace" },
                statusLabel = projStatus,
                isActive = isMain && isTeamRunning,
                isError = isMain && activeSnapshot?.status == com.jarves.mh.multiagent.supervisor.SupervisorOperationalStatus.BLOCKED,
                position = pos,
                baseRadius = if (isMain) 36f else 24f,
                accentColorArgb = if (isMain) 0xFF38BDF8L else 0xFF818CF8L,
                projectId = proj.id,
            )
        }

        // 2. Real AI Agent Nodes orbiting the active project on an inclined 3D orbital plane
        val orbitRadius = if (agents.size <= 2) 1.75f else 1.98f
        val managerNodeId = agents.firstOrNull { it.config.slot == AgentSlot.AGENT_1 }?.let { "agent:${it.config.slot.id}" }

        agents.forEachIndexed { idx, agent ->
            val angle = (idx.toFloat() / agents.size.coerceAtLeast(1)) * (2f * PI.toFloat()) - (PI.toFloat() / 2f)
            val elevation = when (agent.config.slot) {
                AgentSlot.AGENT_1 -> 0.55f
                AgentSlot.AGENT_2 -> -0.25f
                AgentSlot.AGENT_3 -> 0.28f
                AgentSlot.AGENT_4 -> -0.45f
            }
            val pos = Vec3(
                x = cos(angle) * orbitRadius,
                y = elevation,
                z = sin(angle) * orbitRadius,
            )
            val health = activeSnapshot?.agentHealth?.get(agent.config.slot.id)?.healthState
            val isError = agent.activityState.activity == ActivityType.ERROR ||
                health in setOf(AgentHealthState.PROVIDER_ERROR, AgentHealthState.TIMEOUT, AgentHealthState.UNAVAILABLE)
            val isActive = agent.isWorking ||
                health == AgentHealthState.BUSY ||
                activeSnapshot?.activeAgentId == agent.config.slot.id
            val statusLabel = when {
                !agent.config.isEnabled -> "Disabled"
                isError -> "Error"
                health == AgentHealthState.RECOVERING -> "Recovering"
                isActive -> when (agent.activityState.activity) {
                    ActivityType.PLANNING -> "Planning"
                    ActivityType.REVIEWING, ActivityType.TESTING -> "Reviewing"
                    else -> "Working"
                }
                agent.activityState.activity == ActivityType.COMPLETED -> "Completed"
                else -> "Idle"
            }
            val colorArgb = when (agent.config.slot) {
                AgentSlot.AGENT_1 -> 0xFFF59E0BL // Amber gold (Manager / Lead)
                AgentSlot.AGENT_2 -> 0xFF10B981L // Emerald (Core Developer)
                AgentSlot.AGENT_3 -> 0xFF3B82F6L // Sapphire (Specialist / Coder 2)
                AgentSlot.AGENT_4 -> 0xFFA855F7L // Amethyst (QA / Reviewer)
            }
            val agentNodeId = "agent:${agent.config.slot.id}"
            nodes += UniverseNode(
                id = agentNodeId,
                type = UniverseNodeType.AGENT,
                label = agent.config.name,
                subtitle = agent.config.role.title,
                statusLabel = statusLabel,
                isActive = isActive,
                isError = isError,
                position = pos,
                baseRadius = if (agent.config.slot == AgentSlot.AGENT_1) 25f else 22f,
                accentColorArgb = colorArgb,
                projectId = activeProject?.id,
                agentSlot = agent.config.slot,
            )

            // Project <-> Agent real connection
            if (activeProjNodeId != null) {
                edges += UniverseEdge(
                    id = "edge:$activeProjNodeId->$agentNodeId",
                    fromId = activeProjNodeId,
                    toId = agentNodeId,
                    type = UniverseEdgeType.PROJECT_AGENT,
                    hasLiveFlow = isActive,
                    flowLabel = if (isActive) agent.activityState.currentTask.take(32) else "",
                    intensity = if (isActive) 1.0f else 0.35f,
                )
            }

            // Manager <-> Worker real delegation connection
            if (managerNodeId != null && agentNodeId != managerNodeId && agent.config.isEnabled) {
                val delegatedActive = isActive ||
                    (isTeamRunning && activeSnapshot?.activeAgentId == agent.config.slot.id)
                edges += UniverseEdge(
                    id = "edge:$managerNodeId->$agentNodeId",
                    fromId = managerNodeId,
                    toId = agentNodeId,
                    type = UniverseEdgeType.MANAGER_AGENT,
                    hasLiveFlow = delegatedActive,
                    flowLabel = if (delegatedActive) "Delegated" else "",
                    intensity = if (delegatedActive) 0.92f else 0.25f,
                )
            }
        }

        // 3. Real Agent <-> Agent connections from actual collaboration events or concurrent workers
        val seenAgentPairs = mutableSetOf<String>()
        collaborationEvents
            .filter { (nowMs - it.event.timestamp) <= RECENT_EVENT_WINDOW_MS }
            .forEach { ev ->
                val fromSlot = ev.event.senderSlot ?: return@forEach
                val toSlot = ev.event.targetSlot ?: return@forEach
                if (fromSlot == toSlot) return@forEach
                val fromId = "agent:${fromSlot.id}"
                val toId = "agent:${toSlot.id}"
                if (nodes.none { it.id == fromId } || nodes.none { it.id == toId }) return@forEach
                val key = listOf(fromId, toId).sorted().joinToString("|")
                if (seenAgentPairs.add(key)) {
                    edges += UniverseEdge(
                        id = "edge:collab:$fromId->$toId",
                        fromId = fromId,
                        toId = toId,
                        type = UniverseEdgeType.AGENT_AGENT,
                        hasLiveFlow = true,
                        flowLabel = ev.event.message.ifBlank { ev.event.type.name }.take(28),
                        intensity = 1.0f,
                    )
                }
            }

        // Also connect simultaneously active worker agents (parallel coding collaboration!)
        val activeWorkers = agents.filter { it.config.slot != AgentSlot.AGENT_1 && it.isWorking }
        if (activeWorkers.size >= 2) {
            for (i in 0 until activeWorkers.size - 1) {
                val fromId = "agent:${activeWorkers[i].config.slot.id}"
                val toId = "agent:${activeWorkers[i + 1].config.slot.id}"
                val key = listOf(fromId, toId).sorted().joinToString("|")
                if (seenAgentPairs.add(key)) {
                    edges += UniverseEdge(
                        id = "edge:parallel:$fromId->$toId",
                        fromId = fromId,
                        toId = toId,
                        type = UniverseEdgeType.AGENT_AGENT,
                        hasLiveFlow = true,
                        flowLabel = "Parallel Sync",
                        intensity = 0.88f,
                    )
                }
            }
        }

        return UniverseGraph(nodes = nodes, edges = edges)
    }
}
