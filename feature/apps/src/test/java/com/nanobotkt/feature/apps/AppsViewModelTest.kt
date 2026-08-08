package com.nanobotkt.feature.apps

import com.nanobotkt.core.model.AppManifest
import com.nanobotkt.core.model.AppPlan
import com.nanobotkt.core.model.CliAppInfo
import com.nanobotkt.core.model.McpPresetField
import com.nanobotkt.core.model.McpPresetInfo
import com.nanobotkt.core.model.McpPresetsPayload
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description

@OptIn(ExperimentalCoroutinesApi::class)
class AppsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun missingRequiredMcpFieldDoesNotCallRepository() = runTest {
        val repository = FakeAppsRepository(repositoryState())
        val viewModel = AppsViewModel(repository)
        runCurrent() // 消化 ViewModel 初始化时的首次 refresh。

        viewModel.mcp("enable", "demo")
        runCurrent()

        assertTrue(repository.mcpActions.isEmpty())
    }

    @Test
    fun removeDoesNotRequireMissingSetupFields() = runTest {
        val repository = FakeAppsRepository(
            AppsUiState(
                mcp = McpPresetsPayload(
                    presets = listOf(
                        McpPresetInfo(
                            name = "demo",
                            installed = true,
                            requiredFields = listOf(McpPresetField(name = "token", required = true)),
                        ),
                    ),
                ),
            ),
        )
        val viewModel = AppsViewModel(repository)
        runCurrent()

        viewModel.mcp("remove", "demo")
        runCurrent()

        assertEquals(listOf(McpAction("remove", "demo", emptyMap())), repository.mcpActions)
    }

    @Test
    fun mcpActionReceivesEnteredRequiredFields() = runTest {
        val repository = FakeAppsRepository(repositoryState())
        val viewModel = AppsViewModel(repository)
        runCurrent()

        viewModel.setMcpFieldValue("demo", "token", " secret-token ")
        viewModel.mcp("enable", "demo")
        runCurrent()

        assertEquals(
            listOf(McpAction("enable", "demo", mapOf("token" to " secret-token "))),
            repository.mcpActions,
        )
    }

    @Test
    fun updateCliUsesRepositoryEntryWhenManifestHasPackageStrategy() = runTest {
        val repository = FakeAppsRepository(AppsUiState())
        val viewModel = AppsViewModel(repository)
        runCurrent()

        val app = CliAppInfo(
            name = "demo",
            installed = true,
            installSupported = true,
            manifest = AppManifest(install = AppPlan(supported = true, strategy = "pip")),
        )
        assertTrue(viewModel.canUpdateCli(app))

        viewModel.cli("update", app.name)
        runCurrent()

        assertEquals(listOf(CliAction("update", "demo")), repository.cliActions)
    }

    @Test
    fun bundledCliDoesNotExposeUpdateCapability() = runTest {
        val viewModel = AppsViewModel(FakeAppsRepository(AppsUiState()))
        runCurrent()

        val app = CliAppInfo(
            name = "bundled",
            installed = true,
            installSupported = true,
            manifest = AppManifest(install = AppPlan(supported = true, strategy = "bundled")),
        )

        assertFalse(viewModel.canUpdateCli(app))
    }

    @Test
    fun invalidCustomMcpDoesNotCallRepository() = runTest {
        val repository = FakeAppsRepository(AppsUiState())
        val viewModel = AppsViewModel(repository)
        runCurrent()

        viewModel.saveCustom(
            mapOf(
                "name" to "demo",
                "transport" to "stdio",
                "command" to "",
            ),
        )
        runCurrent()

        assertTrue(repository.customValues.isEmpty())
        assertFalse(viewModel.isCustomConfigValid(mapOf("name" to "demo", "transport" to "stdio")))
    }

    @Test
    fun validCustomMcpPassesAllFieldsToRepository() = runTest {
        val repository = FakeAppsRepository(AppsUiState())
        val viewModel = AppsViewModel(repository)
        runCurrent()
        val values = mapOf(
            "name" to "demo",
            "transport" to "stdio",
            "command" to "npx",
            "args" to "[\"-y\"]",
            "env" to "{\"API_KEY\":\"secret\"}",
            "enabled_tools" to "*",
        )

        viewModel.saveCustom(values)
        runCurrent()

        assertEquals(listOf(values), repository.customValues)
    }

    @Test
    fun mcpToolsSelectionSavesOnlySelectedTools() = runTest {
        val repository = FakeAppsRepository(
            AppsUiState(
                mcp = McpPresetsPayload(
                    presets = listOf(
                        McpPresetInfo(
                            name = "demo",
                            installed = true,
                            toolNames = listOf("read", "write"),
                            enabledTools = listOf("read"),
                        ),
                    ),
                ),
            ),
        )
        val viewModel = AppsViewModel(repository)
        runCurrent()
        val preset = repository.state.value.mcp!!.presets.single()

        assertTrue(viewModel.isMcpToolSelected(preset, "read"))
        assertFalse(viewModel.isMcpToolSelected(preset, "write"))
        viewModel.setMcpToolSelected(preset, "write", selected = true)
        viewModel.setMcpToolSelected(preset, "read", selected = false)
        viewModel.updateTools(preset.name)
        runCurrent()

        assertEquals(listOf(ToolsAction("demo", listOf("write"))), repository.toolsActions)
    }

    private fun repositoryState() = AppsUiState(
        mcp = McpPresetsPayload(
            presets = listOf(
                McpPresetInfo(
                    name = "demo",
                    installSupported = true,
                    requiredFields = listOf(McpPresetField(name = "token", required = true)),
                ),
            ),
        ),
    )
}

private data class CliAction(
    val action: String,
    val name: String,
)

private data class McpAction(
    val action: String,
    val name: String,
    val values: Map<String, String>,
)

private data class ToolsAction(
    val name: String,
    val tools: List<String>,
)

private class FakeAppsRepository(initialState: AppsUiState) : AppsRepository {
    private val mutableState = MutableStateFlow(initialState)
    override val state: StateFlow<AppsUiState> = mutableState.asStateFlow()
    val cliActions = mutableListOf<CliAction>()
    val mcpActions = mutableListOf<McpAction>()
    val customValues = mutableListOf<Map<String, String>>()
    val toolsActions = mutableListOf<ToolsAction>()

    override fun reset() = Unit
    override suspend fun refresh() = Unit
    override suspend fun cliAction(action: String, name: String) {
        cliActions += CliAction(action, name)
    }
    override suspend fun mcpAction(action: String, name: String, values: Map<String, String>) {
        mcpActions += McpAction(action, name, values)
    }
    override suspend fun saveCustom(values: Map<String, String>) {
        customValues += values
    }
    override suspend fun importConfig(config: String) = Unit
    override suspend fun importCursorConfig(config: String) = Unit
    override suspend fun updateTools(name: String, tools: List<String>) {
        toolsActions += ToolsAction(name, tools)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    private val dispatcher: TestDispatcher = StandardTestDispatcher(),
) : TestWatcher() {
    override fun starting(description: Description) {
        kotlinx.coroutines.Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        kotlinx.coroutines.Dispatchers.resetMain()
    }
}
