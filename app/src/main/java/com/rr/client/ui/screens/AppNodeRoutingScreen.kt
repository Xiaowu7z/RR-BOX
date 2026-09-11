package com.rr.client.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.rr.client.core.model.AppRouteConfig
import com.rr.client.core.AppNodeRuntimeValidation
import com.rr.client.core.model.ProxyNode
import com.rr.client.core.model.friendlyLabel
import com.rr.client.routing.AppNodeBinding
import com.rr.client.routing.AppNodeBindingEdit
import com.rr.client.routing.AppNodeEditOperation
import com.rr.client.routing.AppNodeUidGroup
import com.rr.client.routing.PerAppPolicyResolver
import com.rr.client.subscription.TrafficInfoNode
import com.rr.client.subscription.model.SubProfile
import com.rr.client.ui.theme.CardBorder
import com.rr.client.ui.theme.CyanPrimary
import com.rr.client.ui.theme.DarkBackground
import com.rr.client.ui.theme.DarkSurface
import com.rr.client.ui.theme.TextPrimary
import com.rr.client.ui.theme.TextSecondary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A separate override list: opening or changing it never changes the main selected node. */
@Composable
fun AppNodeRoutingScreen(
    apps: List<AppRouteConfig>,
    nodes: List<ProxyNode>,
    bindings: List<AppNodeBinding>,
    mainNodeId: String?,
    perAppMode: String,
    selectedPackages: Set<String>,
    isLoading: Boolean = false,
    isApplying: Boolean = false,
    onBindingsChange: (AppNodeBindingEdit) -> Unit,
    onBack: () -> Unit,
    loadError: String? = null,
    onResetInvalidBindings: () -> Unit = {},
    onEditPreparationFailure: (String, AppNodeEditOperation, String?, Boolean?, Exception) -> Unit = { _, _, _, _, _ -> }
) {
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var choosingApp by rememberSaveable { mutableStateOf(false) }
    var editingPackage by rememberSaveable { mutableStateOf<String?>(null) }
    var resolvingGroup by remember { mutableStateOf(false) }
    var pendingSharedEdit by remember { mutableStateOf<AppNodeBindingEdit?>(null) }
    var editError by remember { mutableStateOf<String?>(null) }
    var groupsByPackage by remember { mutableStateOf<Map<String, AppNodeUidGroup>>(emptyMap()) }
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val busy = isLoading || isApplying || resolvingGroup
    val appsByPackage = remember(apps) { apps.associateBy { it.packageName } }
    val selectableNodes = remember(nodes) {
        nodes.filterNot(TrafficInfoNode::isInfoNode).distinctBy { it.id }
    }
    val nodesById = remember(selectableNodes) { selectableNodes.associateBy { it.id } }
    val mainNode = nodesById[mainNodeId]
    val scopedApps = remember(apps, perAppMode, selectedPackages) {
        apps.filter { appIsCaptured(it.packageName, perAppMode, selectedPackages) }
            .distinctBy { it.packageName }
            .sortedWith(compareBy<AppRouteConfig> { it.appName.lowercase() }.thenBy { it.packageName })
    }
    val availableApps = remember(scopedApps, bindings) {
        val boundPackages = bindings.mapTo(mutableSetOf()) { it.packageName }
        scopedApps.filterNot { it.packageName in boundPackages }
    }
    val visibleBindings = remember(bindings, searchQuery, appsByPackage, nodesById, groupsByPackage) {
        bindings.distinctBy { it.packageName }.filter { binding ->
            val app = appsByPackage[binding.packageName]
            val node = nodesById[binding.nodeId]
            listOf(binding.packageName, app?.appName.orEmpty(), node?.tag.orEmpty(), node?.profileName.orEmpty(),
                groupsByPackage[binding.packageName]?.labels?.get(binding.packageName).orEmpty())
                .any { it.contains(searchQuery, ignoreCase = true) }
        }
    }

    LaunchedEffect(bindings, apps) {
        groupsByPackage = withContext(Dispatchers.IO) {
            val resolved = mutableMapOf<String, AppNodeUidGroup>()
            bindings.forEach { binding ->
                if (binding.packageName !in resolved) {
                    try {
                        val group = AppNodeRuntimeValidation.resolveEditingGroup(context, binding.packageName)
                        group.packages.forEach { resolved[it] = group }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        // Display hints are optional; every edit resolves identity again.
                    }
                }
            }
            resolved
        }
    }

    fun requestEdit(packageName: String, operation: AppNodeEditOperation, nodeId: String? = null, enabled: Boolean? = null) {
        if (busy || resolvingGroup) return
        resolvingGroup = true
        scope.launch {
            try {
                val group = withContext(Dispatchers.IO) {
                    AppNodeRuntimeValidation.resolveEditingGroup(context, packageName)
                }
                if (operation == AppNodeEditOperation.ASSIGN || enabled == true) {
                    require(group.uid != null) { "应用未安装，无法设置指定节点" }
                    require(group.blockedReason == null) { group.blockedReason.orEmpty() }
                }
                val edit = AppNodeBindingEdit(packageName, operation, nodeId, enabled, group)
                if (group.packages.size > 1) pendingSharedEdit = edit else onBindingsChange(edit)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                editError = error.message ?: "无法读取应用身份，请重试"
                onEditPreparationFailure(packageName, operation, nodeId, enabled, error)
            } finally {
                resolvingGroup = false
            }
        }
    }

    BackHandler(onBack = onBack)
    LaunchedEffect(loadError) {
        if (loadError != null) {
            choosingApp = false
            editingPackage = null
            pendingSharedEdit = null
        }
    }
    if (loadError != null) {
        AppNodeBindingsErrorState(
            error = loadError,
            isApplying = isApplying,
            onReset = onResetInvalidBindings,
            onBack = onBack
        )
        return
    }

    Column(
        modifier = Modifier.fillMaxSize().background(DarkBackground)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回应用接管范围", tint = TextPrimary)
            }
            Text(
                "应用指定节点",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            IconButton(onClick = { choosingApp = true }, enabled = !busy) {
                Icon(Icons.Default.Add, contentDescription = "添加应用指定节点规则", tint = CyanPrimary)
            }
        }
        if (bindings.isNotEmpty()) {
            AppNodeSearchField(searchQuery, { searchQuery = it }, "搜索应用或节点…")
            Spacer(Modifier.height(8.dp))
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 8.dp)
        ) {
            item(key = "main_node_summary", contentType = "summary") {
                Column(
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        "默认主节点：${mainNode?.tag ?: "尚未选择"}",
                        color = CyanPrimary,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "仅覆盖下方应用的线路，独立于智能分流。关闭或删除规则后跟随主节点。",
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "指定节点失效时仅阻断该应用；未纳入接管的规则保留但不生效。",
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        when {
                            isLoading -> "正在读取应用…"
                            resolvingGroup -> "正在确认应用身份…"
                            isApplying -> "正在应用配置…"
                            else -> "${bindings.size} 条规则 · 修改后自动保存"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }
            }
            if (visibleBindings.isEmpty()) {
                item(key = "empty_bindings", contentType = "status") {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            if (searchQuery.isNotBlank()) "没有匹配的规则" else "为应用选择一条专属线路",
                            color = TextPrimary,
                            style = MaterialTheme.typography.bodyLarge
                        )
                        if (searchQuery.isBlank()) {
                            Text("例如：Telegram → HK 节点", color = TextSecondary)
                            TextButton(
                                onClick = { choosingApp = true },
                                enabled = !busy,
                                modifier = Modifier.heightIn(min = 48.dp),
                                colors = ButtonDefaults.textButtonColors(contentColor = CyanPrimary)
                            ) { Text("添加规则") }
                        }
                    }
                }
            } else {
                items(visibleBindings, key = { "binding_${it.packageName}" }, contentType = { "binding" }) { binding ->
                    AppNodeBindingCard(
                        binding = binding,
                        app = appsByPackage[binding.packageName] ?: groupsByPackage[binding.packageName]
                            ?.takeIf { it.uid != null }?.let { group ->
                                AppRouteConfig(binding.packageName,
                                    group.labels[binding.packageName] ?: binding.packageName, "PROXY_NODE")
                            },
                        node = nodesById[binding.nodeId],
                        captured = groupsByPackage[binding.packageName]?.let { group ->
                            when (perAppMode) {
                                PerAppPolicyResolver.MODE_ALLOW_LIST -> group.packages.any { it in selectedPackages }
                                PerAppPolicyResolver.MODE_DISALLOW_LIST -> group.packages.none { it in selectedPackages }
                                else -> appIsCaptured(binding.packageName, perAppMode, selectedPackages)
                            }
                        } ?: appIsCaptured(binding.packageName, perAppMode, selectedPackages),
                        busy = busy,
                        sharedGroup = groupsByPackage[binding.packageName],
                        onEnabledChange = { enabled ->
                            requestEdit(binding.packageName, AppNodeEditOperation.SET_ENABLED, enabled = enabled)
                        },
                        onEdit = { editingPackage = binding.packageName },
                        onDelete = { requestEdit(binding.packageName, AppNodeEditOperation.DELETE) }
                    )
                }
            }
        }
    }

    if (choosingApp) {
        AppBindingAppPicker(
            apps = availableApps,
            busy = busy,
            hasCapturedApps = scopedApps.isNotEmpty(),
            onDismiss = { choosingApp = false },
            onSelected = { app ->
                choosingApp = false
                editingPackage = app.packageName
            }
        )
    }
    editingPackage?.let { packageName ->
        AppBindingNodePicker(
            appName = appsByPackage[packageName]?.appName ?: packageName,
            nodes = selectableNodes,
            selectedNodeId = bindings.firstOrNull { it.packageName == packageName }?.nodeId,
            mainNodeId = mainNodeId,
            busy = busy,
            onDismiss = { editingPackage = null },
            onSelected = { node ->
                requestEdit(packageName, AppNodeEditOperation.ASSIGN, nodeId = node.id)
                editingPackage = null
            }
        )
    }
    pendingSharedEdit?.let { edit ->
        val group = edit.confirmedGroup
        val chosenNodeId = edit.nodeId ?: bindings.firstOrNull { it.packageName == edit.packageName }?.nodeId
        val chosenNodeName = nodesById[chosenNodeId]?.tag ?: "节点已失效，接管时将阻断连接"
        val willEnable = when (edit.operation) {
            AppNodeEditOperation.DELETE -> false
            AppNodeEditOperation.SET_ENABLED -> edit.enabled == true
            AppNodeEditOperation.ASSIGN -> bindings.firstOrNull { it.packageName == edit.packageName }?.enabled ?: true
        }
        val scopeMessage = when {
            !willEnable -> "代理接管名单保留。"
            perAppMode == PerAppPolicyResolver.MODE_ALLOW_LIST ->
                if (group.packages.any { it in selectedPackages })
                    "整组加入当前的“仅选中应用”名单；保存的绕过名单保持不变。"
                else "本组未纳入接管，规则保存后仍不生效；代理接管名单保留。"
            perAppMode == PerAppPolicyResolver.MODE_DISALLOW_LIST ->
                if (group.packages.all { it in selectedPackages })
                    "本组均在绕过名单，规则保存后仍不生效；代理接管名单保留。"
                else if (group.packages.any { it in selectedPackages })
                    "本组有应用被绕过，Android 当前会绕过整组。确认后整组移出当前绕过名单并走代理；保存的“仅选中应用”名单保持不变。"
                else "整组继续被接管，两份应用名单保持不变。"
            else -> "所有应用模式下整组均被接管，两份应用名单保持不变。"
        }
        val action = when (edit.operation) {
            AppNodeEditOperation.DELETE -> "删除这组规则"
            AppNodeEditOperation.SET_ENABLED -> if (edit.enabled == true) "启用这组规则" else "关闭这组规则"
            AppNodeEditOperation.ASSIGN -> "整组使用此节点"
        }
        AlertDialog(
            onDismissRequest = { pendingSharedEdit = null },
            title = { Text("共享身份的应用需要一起设置") },
            text = {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        Text("Android 将以下应用视为同一个网络身份，无法分别使用不同节点。")
                    }
                    items(group.packages.sorted(), key = { it }) { member ->
                        Column {
                            Text(group.labels[member] ?: member, fontWeight = FontWeight.SemiBold)
                            if (group.labels[member] != member) Text(member, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    item {
                        Text(when (edit.operation) {
                            AppNodeEditOperation.DELETE -> "整组删除后恢复默认线路，代理接管名单保留。"
                            AppNodeEditOperation.SET_ENABLED -> if (edit.enabled == true)
                                "共同节点：$chosenNodeName\n整组启用；默认主节点保持不变。\n$scopeMessage"
                                else "整组关闭后恢复默认线路，代理接管名单保留。"
                            AppNodeEditOperation.ASSIGN ->
                                "共同节点：$chosenNodeName\n$scopeMessage\n默认主节点保持不变。"
                        })
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    pendingSharedEdit = null
                    onBindingsChange(edit)
                }) { Text(action) }
            },
            dismissButton = { TextButton(onClick = { pendingSharedEdit = null }) { Text("取消") } }
        )
    }
    editError?.let { error ->
        AlertDialog(onDismissRequest = { editError = null },
            title = { Text("暂时无法设置") }, text = { Text(error) },
            confirmButton = { TextButton(onClick = { editError = null }) { Text("知道了") } })
    }
}

@Composable
private fun AppNodeBindingsErrorState(
    error: String,
    isApplying: Boolean,
    onReset: () -> Unit,
    onBack: () -> Unit
) {
    var confirmReset by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxSize().background(DarkBackground)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回应用接管范围", tint = TextPrimary)
            }
            Text(
                "应用指定节点",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
        }
        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                    border = BorderStroke(1.dp, CardBorder)
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            "应用指定节点规则读取失败",
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(error, color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "可以重置此模块的规则，然后重新添加应用与节点。",
                            color = TextSecondary,
                            style = MaterialTheme.typography.bodySmall
                        )
                        OutlinedButton(
                            onClick = { confirmReset = true },
                            enabled = !isApplying,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = CyanPrimary),
                            border = BorderStroke(1.dp, CardBorder)
                        ) {
                            Text(if (isApplying) "正在重置…" else "重置应用指定节点")
                        }
                    }
                }
            }
        }
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("重置应用指定节点？") },
            text = {
                Text("只清除此模块的全部规则。主节点、订阅和代理 APP 名单都会保留；应用恢复使用默认线路和现有分流设置。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmReset = false
                        onReset()
                    },
                    enabled = !isApplying,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) { Text("确认重置") }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("取消")
                }
            }
        )
    }
}

private fun appIsCaptured(packageName: String, mode: String, selectedPackages: Set<String>): Boolean =
    when (mode) {
        PerAppPolicyResolver.MODE_ALL -> true
        PerAppPolicyResolver.MODE_ALLOW_LIST -> packageName in selectedPackages
        PerAppPolicyResolver.MODE_DISALLOW_LIST -> packageName !in selectedPackages
        else -> false
    }

@Composable
private fun AppNodeBindingCard(
    binding: AppNodeBinding,
    app: AppRouteConfig?,
    node: ProxyNode?,
    captured: Boolean,
    busy: Boolean,
    sharedGroup: AppNodeUidGroup?,
    onEnabledChange: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val missingNode = node == null && binding.enabled
    val status = when {
        app == null -> "应用未安装 · 规则已保留"
        !captured -> "未纳入接管 · 规则未生效"
        !binding.enabled -> "已关闭 · 跟随主节点"
        missingNode -> "节点已失效 · 接管时阻断，请重选"
        else -> "已启用 · 使用指定节点"
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        border = BorderStroke(1.dp, if (binding.enabled && captured && app != null) CyanPrimary else CardBorder)
    ) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        app?.appName ?: binding.packageName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (app != null) {
                        Text(
                            binding.packageName,
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Switch(
                    checked = binding.enabled,
                    onCheckedChange = onEnabledChange,
                    enabled = !busy,
                    modifier = Modifier.heightIn(min = 48.dp).semantics {
                        contentDescription = "${app?.appName ?: binding.packageName}指定节点规则"
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = DarkBackground,
                        checkedTrackColor = CyanPrimary
                    )
                )
                Box {
                    IconButton(onClick = { menuExpanded = true }, enabled = !busy) {
                        Icon(Icons.Default.MoreVert, contentDescription = "${app?.appName ?: binding.packageName}规则设置", tint = TextSecondary)
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text("更换节点") },
                            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                            enabled = !busy,
                            onClick = { menuExpanded = false; onEdit() }
                        )
                        DropdownMenuItem(
                            text = { Text("删除规则") },
                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                            enabled = !busy,
                            onClick = { menuExpanded = false; onDelete() }
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .clickable(enabled = !busy, onClick = onEdit)
                    .padding(end = 8.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        node?.tag ?: "节点已失效，点击重新选择",
                        color = if (node == null) MaterialTheme.colorScheme.error else CyanPrimary,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (node != null) {
                        Text(
                            "${nodeGroupName(node)} · ${node.type.friendlyLabel()}",
                            color = TextSecondary,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Icon(Icons.Default.Edit, contentDescription = null, tint = TextSecondary)
            }
            Text(
                status,
                modifier = Modifier.padding(end = 8.dp),
                color = if (missingNode && captured && app != null) MaterialTheme.colorScheme.error else TextSecondary,
                style = MaterialTheme.typography.labelSmall
            )
            if (sharedGroup != null && sharedGroup.packages.size > 1) {
                val peers = (sharedGroup.packages - binding.packageName).sorted()
                    .joinToString("、") { sharedGroup.labels[it] ?: it }
                Text("与 $peers 共享身份 · 改节点、启停和删除会同步",
                    modifier = Modifier.padding(end = 8.dp, top = 4.dp),
                    color = TextSecondary, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun AppBindingAppPicker(
    apps: List<AppRouteConfig>,
    busy: Boolean,
    hasCapturedApps: Boolean,
    onDismiss: () -> Unit,
    onSelected: (AppRouteConfig) -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(apps, query) {
        apps.filter { it.appName.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true) }
    }
    AppBindingPickerDialog(
        title = "选择应用",
        subtitle = "仅显示接管范围内尚未设置规则的应用",
        query = query,
        placeholder = "搜索应用或包名…",
        onQueryChanged = { query = it },
        onDismiss = onDismiss
    ) {
        if (filtered.isEmpty()) {
            item {
                Text(
                    when {
                        busy -> "正在读取应用…"
                        query.isNotBlank() -> "没有找到匹配的应用"
                        hasCapturedApps -> "这些应用均已设置规则，可返回修改已有规则。"
                        else -> "请先返回应用接管范围，选择需要代理的应用。"
                    },
                    modifier = Modifier.padding(vertical = 24.dp),
                    color = TextSecondary
                )
            }
        }
        items(filtered, key = { it.packageName }) { app ->
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    .clickable(enabled = !busy) { onSelected(app) }
                    .padding(horizontal = 8.dp, vertical = 10.dp)
            ) {
                Text(app.appName, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun AppBindingNodePicker(
    appName: String,
    nodes: List<ProxyNode>,
    selectedNodeId: String?,
    mainNodeId: String?,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSelected: (ProxyNode) -> Unit
) {
    var query by rememberSaveable(appName) { mutableStateOf("") }
    val groups = remember(nodes, query) {
        nodes.filter {
            it.tag.contains(query, ignoreCase = true) || nodeGroupName(it).contains(query, ignoreCase = true) ||
                it.type.friendlyLabel().contains(query, ignoreCase = true)
        }.groupBy { it.profileId }
    }
    AppBindingPickerDialog(
        title = "为 $appName 选择节点",
        subtitle = "默认主节点保持不变；如有共享身份的应用，选择后会提示整组确认",
        query = query,
        placeholder = "搜索节点、分组或协议…",
        onQueryChanged = { query = it },
        onDismiss = onDismiss
    ) {
        if (groups.isEmpty()) {
            item {
                Text(
                    if (query.isBlank()) "暂无可连接节点，请先添加节点或更新订阅。" else "没有找到匹配的节点",
                    modifier = Modifier.padding(vertical = 24.dp),
                    color = TextSecondary
                )
            }
        }
        groups.forEach { (profileId, groupNodes) ->
            item(key = "group_$profileId", contentType = "group") {
                Text(
                    nodeGroupName(groupNodes.first()),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = CyanPrimary
                )
            }
            items(groupNodes, key = { "node_${it.id}" }, contentType = { "node" }) { node ->
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                        .clickable(enabled = !busy) { onSelected(node) }
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            node.tag,
                            color = TextPrimary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            node.type.friendlyLabel() + if (node.id == mainNodeId) " · 当前主节点" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    if (node.id == selectedNodeId) {
                        Icon(Icons.Default.Check, contentDescription = "当前指定节点", tint = CyanPrimary)
                    }
                }
            }
        }
    }
}

private fun nodeGroupName(node: ProxyNode): String = when {
    node.profileId == SubProfile.LOCAL_PROFILE_ID -> SubProfile.LOCAL_PROFILE_NAME
    node.profileName.isNotBlank() -> node.profileName
    node.profileId.isBlank() -> "本地节点"
    else -> "订阅分组"
}

@Composable
private fun AppNodeSearchField(value: String, onValueChange: (String) -> Unit, placeholder: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = DarkSurface,
            unfocusedContainerColor = DarkSurface,
            focusedBorderColor = CyanPrimary,
            unfocusedBorderColor = CardBorder
        )
    )
}

@Composable
private fun AppBindingPickerDialog(
    title: String,
    subtitle: String,
    query: String,
    placeholder: String,
    onQueryChanged: (String) -> Unit,
    onDismiss: () -> Unit,
    content: LazyListScope.() -> Unit
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp)
                .widthIn(max = 560.dp).fillMaxWidth().fillMaxHeight(0.88f),
            shape = RoundedCornerShape(20.dp),
            color = DarkSurface,
            border = BorderStroke(1.dp, CardBorder)
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "取消选择", tint = TextSecondary)
                    }
                }
                Text(subtitle, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))
                AppNodeSearchField(query, onQueryChanged, placeholder)
                Spacer(Modifier.height(8.dp))
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(bottom = 8.dp),
                    content = content
                )
            }
        }
    }
}
