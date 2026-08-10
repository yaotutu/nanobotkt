package com.nanobotkt.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nanobotkt.core.model.WorkspaceAccessMode
import com.nanobotkt.core.model.WorkspaceControls
import com.nanobotkt.core.model.WorkspaceScope
import com.nanobotkt.core.model.withAccessMode

/** Composer 更多操作面板及其内部页面，避免主页面文件承载完整弹层实现。 */
internal enum class ComposerMorePage {
    Root,
    Model,
    Access,
}

/** 更多菜单中可执行的动作；它只描述一级面板上的目标。 */
internal enum class ComposerMoreAction {
    Images,
    Files,
    Model,
    Access,
}

/**
 * “+”菜单的单一 BottomSheet 容器。 Root/Model/Access 是同一层中的内容切换，不会同时存在多个弹层； 因此模型和权限选择都能保持上下文，并且返回手势只作用于当前
 * Sheet。
 */
@Composable
internal fun ComposerMoreSheet(
    page: ComposerMorePage,
    model: ChatModelSelection,
    activeScope: WorkspaceScope?,
    controls: WorkspaceControls?,
    modelEnabled: Boolean,
    accessEnabled: Boolean,
    disabled: Boolean,
    onAction: (ComposerMoreAction) -> Unit,
    onModelChange: (String) -> Unit,
    onOpenModelSettings: () -> Unit,
    onWorkspaceChange: (WorkspaceScope) -> Unit,
    onDismissSheet: () -> Unit,
    onBack: () -> Unit,
) {
    when (page) {
        ComposerMorePage.Root ->
            ComposerMoreRootPage(
                model = model,
                activeScope = activeScope,
                modelEnabled = modelEnabled,
                accessEnabled = accessEnabled,
                onAction = onAction,
            )
        ComposerMorePage.Model ->
            ComposerMoreModelPage(
                model = model,
                disabled = !modelEnabled || disabled,
                onChange = onModelChange,
                onOpenSettings = onOpenModelSettings,
                onDismissSheet = onDismissSheet,
                onBack = onBack,
            )
        ComposerMorePage.Access ->
            ComposerMoreAccessPage(
                scope = activeScope,
                controls = controls,
                disabled = !accessEnabled || disabled,
                onChange = onWorkspaceChange,
                onDismissSheet = onDismissSheet,
                onBack = onBack,
            )
    }
}

@Composable
internal fun ComposerMoreRootPage(
    model: ChatModelSelection,
    activeScope: WorkspaceScope?,
    modelEnabled: Boolean,
    accessEnabled: Boolean,
    onAction: (ComposerMoreAction) -> Unit,
) {
    val accessLabel =
        activeScope?.let { scope ->
            stringResource(
                if (scope.accessMode == WorkspaceAccessMode.FULL) {
                    R.string.workspace_access_full_short
                } else {
                    R.string.workspace_access_default_short
                }
            )
        }
    Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
        Text(
            text = stringResource(R.string.composer_more),
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            style = MaterialTheme.typography.titleMedium,
        )
        ComposerMoreSheetRow(
            icon = Icons.Rounded.Image,
            title = stringResource(R.string.attach_image),
            onClick = { onAction(ComposerMoreAction.Images) },
        )
        ComposerMoreSheetRow(
            icon = Icons.Rounded.AttachFile,
            title = stringResource(R.string.attach_file),
            onClick = { onAction(ComposerMoreAction.Files) },
        )
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        ComposerMoreSheetRow(
            icon = Icons.Rounded.SmartToy,
            title = stringResource(R.string.model_select_title),
            value = model.displayLabel,
            showChevron = true,
            enabled = modelEnabled,
            onClick = { onAction(ComposerMoreAction.Model) },
        )
        if (activeScope != null) {
            ComposerMoreSheetRow(
                icon = Icons.Rounded.Folder,
                title = stringResource(R.string.workspace_access_title),
                value = accessLabel.orEmpty(),
                showChevron = true,
                enabled = accessEnabled,
                onClick = { onAction(ComposerMoreAction.Access) },
            )
        }
    }
}

/** 模型选择页复用原有的服务端确认逻辑，但视觉上仍属于同一个 BottomSheet。 */
@Composable
internal fun ComposerMoreModelPage(
    model: ChatModelSelection,
    disabled: Boolean,
    onChange: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onDismissSheet: () -> Unit,
    onBack: () -> Unit,
) {
    val options =
        remember(model.presets) {
            model.presets.filter { preset -> !preset.isDefault && preset.name.isNotBlank() }
        }
    var requestedPreset by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(requestedPreset, model.pendingPreset, model.error, model.activePreset) {
        val requested = requestedPreset ?: return@LaunchedEffect
        if (model.pendingPreset == null && model.error == null && model.activePreset == requested) {
            requestedPreset = null
            onDismissSheet()
        }
    }

    ComposerMorePageHeader(title = stringResource(R.string.model_select_title), onBack = onBack)
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            stringResource(R.string.model_select_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(options, key = { it.name }) { preset ->
                val selected = preset.name == model.activePreset
                val loading = preset.name == model.pendingPreset
                val provider = preset.resolvedProvider ?: preset.provider
                Surface(
                    onClick = {
                        if (model.pendingPreset == null) {
                            if (selected) onDismissSheet()
                            else {
                                requestedPreset = preset.name
                                onChange(preset.name)
                            }
                        }
                    },
                    enabled = model.pendingPreset == null && !disabled,
                    modifier = Modifier.fillMaxWidth(),
                    color =
                        if (selected) MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.surface,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                preset.label.ifBlank { preset.name },
                                style = MaterialTheme.typography.labelLarge,
                            )
                            Text(
                                listOf(provider, preset.model)
                                    .filter(String::isNotBlank)
                                    .joinToString(" · "),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        when {
                            loading ->
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            selected -> Icon(Icons.Rounded.Check, contentDescription = null)
                        }
                    }
                }
            }
            item {
                TextButton(
                    onClick = onOpenSettings,
                    enabled = model.pendingPreset == null && !disabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.Settings, contentDescription = null, Modifier.size(18.dp))
                    Text(stringResource(R.string.model_settings), Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

/** 权限选择与模型选择共用同一 Sheet 页面骨架，不再从 Sheet 跳到 AlertDialog。 */
@Composable
internal fun ComposerMoreAccessPage(
    scope: WorkspaceScope?,
    controls: WorkspaceControls?,
    disabled: Boolean,
    onChange: (WorkspaceScope) -> Unit,
    onDismissSheet: () -> Unit,
    onBack: () -> Unit,
) {
    if (scope == null) return
    ComposerMorePageHeader(title = stringResource(R.string.workspace_access_title), onBack = onBack)
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ComposerMoreAccessOption(
            selected = scope.accessMode == WorkspaceAccessMode.RESTRICTED,
            enabled = !disabled,
            title = stringResource(R.string.workspace_access_default),
            description = stringResource(R.string.workspace_access_default_description),
            onClick = {
                onChange(scope.withAccessMode(WorkspaceAccessMode.RESTRICTED))
                onDismissSheet()
            },
        )
        ComposerMoreAccessOption(
            selected = scope.accessMode == WorkspaceAccessMode.FULL,
            enabled = !disabled && controls?.canUseFullAccess != false,
            title = stringResource(R.string.workspace_access_full),
            description = stringResource(R.string.workspace_access_full_description),
            onClick = {
                onChange(scope.withAccessMode(WorkspaceAccessMode.FULL))
                onDismissSheet()
            },
        )
    }
}

@Composable
internal fun ComposerMorePageHeader(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = stringResource(R.string.cancel),
            )
        }
        Text(title, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
internal fun ComposerMoreAccessOption(
    selected: Boolean,
    enabled: Boolean,
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        color =
            if (selected) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun ComposerMoreSheetRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    value: String? = null,
    showChevron: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        color = Color.Transparent,
    ) {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent =
                value?.takeIf(String::isNotBlank)?.let { current ->
                    { Text(current, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                },
            leadingContent = { Icon(icon, contentDescription = null) },
            trailingContent =
                if (showChevron) {
                    { Icon(Icons.Rounded.ExpandMore, contentDescription = null) }
                } else {
                    null
                },
        )
    }
}
