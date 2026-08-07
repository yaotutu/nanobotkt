package com.nanobotkt.core.model

fun WorkspaceScope.withAccessMode(accessMode: WorkspaceAccessMode): WorkspaceScope = copy(
    accessMode = accessMode,
    restrictToWorkspace = accessMode == WorkspaceAccessMode.RESTRICTED,
)

fun projectNameFromPath(path: String): String {
    val normalized = path.replace('\\', '/').trimEnd('/')
    return normalized.split('/').filter(String::isNotEmpty).lastOrNull() ?: path
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
