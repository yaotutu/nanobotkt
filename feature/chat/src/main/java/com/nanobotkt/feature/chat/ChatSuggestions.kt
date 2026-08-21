package com.nanobotkt.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nanobotkt.core.model.SlashCommand

/** 斜杠命令、Skill 与能力提及建议列表。 */
@Composable
internal fun SlashCommandSuggestions(
    commands: List<SlashCommand>,
    onSelect: (SlashCommand) -> Unit,
) {
    Card(
        colors =
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.slash_commands_label),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            commands.forEachIndexed { index, command ->
                val commandLabel =
                    listOf(command.command, command.argHint)
                        .filter(String::isNotBlank)
                        .joinToString(" ")
                val supportingText = command.description.ifBlank { command.title }
                val accessibilityLabel =
                    stringResource(R.string.slash_command_suggestion, commandLabel, supportingText)
                if (index > 0) HorizontalDivider()
                Surface(
                    onClick = { onSelect(command) },
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier =
                        Modifier.fillMaxWidth().semantics {
                            contentDescription = accessibilityLabel
                        },
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = commandLabel,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        if (supportingText.isNotBlank()) {
                            Text(
                                text = supportingText,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SkillMentionSuggestions(
    candidates: List<SkillMentionCandidate>,
    onSelect: (SkillMentionCandidate) -> Unit,
) {
    MentionSuggestionsCard(label = stringResource(R.string.skills_mentions_label)) {
        candidates.forEachIndexed { index, candidate ->
            if (index > 0) HorizontalDivider()
            MentionSuggestionRow(
                primary = candidate.command,
                supporting = candidate.skill.description,
                typeLabel = candidate.skill.source,
                accessibilityLabel =
                    stringResource(
                        R.string.skill_mention_suggestion,
                        candidate.command,
                        candidate.skill.description,
                    ),
                onClick = { onSelect(candidate) },
            )
        }
    }
}

@Composable
internal fun CapabilityMentionSuggestions(
    candidates: List<CapabilityMentionCandidate>,
    onSelect: (CapabilityMentionCandidate) -> Unit,
) {
    MentionSuggestionsCard(label = stringResource(R.string.capability_mentions_label)) {
        candidates.forEachIndexed { index, candidate ->
            if (index > 0) HorizontalDivider()
            val details =
                when (candidate) {
                    is CapabilityMentionCandidate.Cli ->
                        Triple(
                            candidate.app.displayName,
                            candidate.app.description,
                            stringResource(R.string.capability_type_cli),
                        )
                    is CapabilityMentionCandidate.Mcp ->
                        Triple(
                            candidate.preset.displayName,
                            candidate.preset.description,
                            stringResource(R.string.capability_type_mcp),
                        )
                }
            MentionSuggestionRow(
                primary = "@${candidate.name}",
                supporting =
                    listOf(details.first, details.second)
                        .filter(String::isNotBlank)
                        .distinct()
                        .joinToString(" · "),
                typeLabel = details.third,
                accessibilityLabel =
                    stringResource(
                        R.string.capability_mention_suggestion,
                        candidate.name,
                        details.third,
                        details.second,
                    ),
                onClick = { onSelect(candidate) },
            )
        }
    }
}

@Composable
internal fun MentionSuggestionsCard(label: String, content: @Composable () -> Unit) {
    Card(
        colors =
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                text = label,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            content()
        }
    }
}

@Composable
internal fun MentionSuggestionRow(
    primary: String,
    supporting: String,
    typeLabel: String,
    accessibilityLabel: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = accessibilityLabel },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = primary,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (supporting.isNotBlank()) {
                    Text(
                        text = supporting,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (typeLabel.isNotBlank()) {
                Text(
                    text = typeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier =
                        Modifier.background(
                                MaterialTheme.colorScheme.secondaryContainer,
                                MaterialTheme.shapes.small,
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}
