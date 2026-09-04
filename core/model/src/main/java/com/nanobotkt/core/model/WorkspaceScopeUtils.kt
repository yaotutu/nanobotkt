package com.nanobotkt.core.model

fun WorkspaceScope.withAccessMode(accessMode: WorkspaceAccessMode): WorkspaceScope = copy(
    accessMode = accessMode,
    restrictToWorkspace = accessMode == WorkspaceAccessMode.RESTRICTED,
)

fun projectNameFromPath(path: String): String {
    val normalized = path.replace('\\', '/').trimEnd('/')
    return normalized.split('/').filter(String::isNotEmpty).lastOrNull() ?: path
}

/**
 * 返回 Workspace 在会话列表和聊天标题栏中的短名称。
 *
 * UI 只需要让用户区分不同会话所属的工作目录，不应把服务端完整绝对路径直接铺在
 * 每一行会话上；因此优先使用服务端给出的项目名，缺失时只取路径最后一级目录。
 * 空路径不会被渲染成孤立的“/”，调用方可以据此隐藏整个 Workspace 标签。
 */
fun WorkspaceScope.displayName(): String? {
    val explicitName = projectName?.trim()?.takeIf { it.isNotEmpty() }
    val derivedName = projectNameFromPath(projectPath).trim().takeIf {
        it.isNotEmpty() && it != "/"
    }
    return explicitName ?: derivedName
}

fun shortWorkspacePath(path: String): String {
    val normalized = path.replace('\\', '/')
    val parts = normalized.split('/').filter(String::isNotEmpty)
    return if (parts.size <= 3) path else ".../${parts.takeLast(3).joinToString("/")}"
}

fun isAbsoluteWorkspacePath(path: String): Boolean {
    val trimmed = path.trim()
    return trimmed == "~" ||
        trimmed.startsWith("~/") ||
        trimmed.startsWith("~\\") ||
        trimmed.startsWith("/") ||
        WINDOWS_ABSOLUTE_PATH.matches(trimmed)
}

fun selectedProjectScope(
    scope: WorkspaceScope?,
    defaultScope: WorkspaceScope?,
): WorkspaceScope? {
    if (scope == null || defaultScope == null) return null
    return scope.takeUnless { sameWorkspacePath(it.projectPath, defaultScope.projectPath) }
}

fun normalizeWorkspacePath(path: String?): String {
    val normalized = path.orEmpty().replace('\\', '/').trimEnd('/')
    return normalized.ifEmpty { "/" }
}

fun sameWorkspacePath(left: String?, right: String?): Boolean {
    if (left.isNullOrEmpty() || right.isNullOrEmpty()) return false
    return normalizeWorkspacePath(left) == normalizeWorkspacePath(right)
}

fun WorkspaceScope.normalized(): WorkspaceScope {
    val normalizedAccessMode = if (accessMode == WorkspaceAccessMode.RESTRICTED) {
        WorkspaceAccessMode.RESTRICTED
    } else {
        WorkspaceAccessMode.FULL
    }
    return copy(
        projectName = projectName ?: projectNameFromPath(projectPath),
        accessMode = normalizedAccessMode,
        restrictToWorkspace = normalizedAccessMode == WorkspaceAccessMode.RESTRICTED,
    )
}

private val WINDOWS_ABSOLUTE_PATH = Regex("^[A-Za-z]:[\\\\/].*")
