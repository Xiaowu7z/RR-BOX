from pathlib import Path


def once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected 1 match, got {count}")
    return text.replace(old, new, 1)


# 1. Recognize subscription URLs pasted from clipboard without swallowing normal node links.
p = Path("app/src/main/java/com/rr/client/subscription/SubscriptionUrlNormalizer.kt")
text = p.read_text()
marker = '    private val SCHEME_REGEX = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")\n'
helper = '''    fun looksLikeSubscriptionAddress(raw: String): Boolean {
        val input = raw.trim()
        if (input.isEmpty() || input.contains('\\n') || input.contains('\\r')) return false

        val lower = input.lowercase()
        if (SCHEME_REGEX.containsMatchIn(input) &&
            !lower.startsWith("https://") &&
            !lower.startsWith("http://")
        ) return false

        val body = when {
            lower.startsWith("https://") -> input.substring(8)
            lower.startsWith("http://") -> input.substring(7)
            input.startsWith("//") -> input.substring(2)
            else -> input
        }
        val authority = body.substringBefore('/').substringBefore('?')
        if (authority.isBlank() || authority.any(Char::isWhitespace) || '@' in authority) return false

        val validHost = if (authority.startsWith("[") && authority.contains("]")) {
            true
        } else {
            val host = authority.substringBefore(':')
            host.equals("localhost", ignoreCase = true) || host.contains('.')
        }
        if (!validHost) return false

        val remainder = body.removePrefix(authority)
        return remainder.isNotBlank() && remainder != "/"
    }

'''
text = once(text, marker, helper + marker, "normalizer helper")
p.write_text(text)

p = Path("app/src/test/java/com/rr/client/subscription/SubscriptionUrlNormalizerTest.kt")
text = p.read_text()
text = once(
    text,
    "import org.junit.Assert.assertEquals\n",
    "import org.junit.Assert.assertEquals\nimport org.junit.Assert.assertFalse\nimport org.junit.Assert.assertTrue\n",
    "normalizer test imports",
)
test_marker = "    @Test(expected = IllegalArgumentException::class)\n    fun rejectsNonHttpSubscriptionScheme()"
test_block = '''    @Test
    fun recognizesClipboardSubscriptionAddresses() {
        assertTrue(SubscriptionUrlNormalizer.looksLikeSubscriptionAddress("https://example.com/sub?token=x"))
        assertTrue(SubscriptionUrlNormalizer.looksLikeSubscriptionAddress("192.0.2.8:8080/sub"))
        assertTrue(SubscriptionUrlNormalizer.looksLikeSubscriptionAddress("[2001:db8::1]:8080/sub"))
    }

    @Test
    fun avoidsNodeLinksAndBareProxyEndpoints() {
        assertFalse(SubscriptionUrlNormalizer.looksLikeSubscriptionAddress("vless://uuid@example.com:443"))
        assertFalse(SubscriptionUrlNormalizer.looksLikeSubscriptionAddress("http://user:pass@example.com:8080"))
        assertFalse(SubscriptionUrlNormalizer.looksLikeSubscriptionAddress("http://example.com:8080"))
        assertFalse(SubscriptionUrlNormalizer.looksLikeSubscriptionAddress("{\\\"type\\\":\\\"vless\\\"}"))
    }

'''
text = once(text, test_marker, test_block + test_marker, "normalizer tests")
p.write_text(text)


# 2. Clear subscription form fields after submit/cancel and before every reopen.
p = Path("app/src/main/java/com/rr/client/ui/screens/SubscriptionScreen.kt")
text = p.read_text()
text = once(
    text,
    "                onClick = { showAddForm = true },",
    '''                onClick = {
                    nameInput = ""
                    urlInput = ""
                    showAddForm = true
                },''',
    "subscription reopen",
)
text = once(
    text,
    "                            onClick = { showAddForm = false },",
    '''                            onClick = {
                                nameInput = ""
                                urlInput = ""
                                showAddForm = false
                            },''',
    "subscription cancel",
)
text = once(
    text,
    '''                            onClick = {
                                onAddProfile(nameInput, urlInput)
                                showAddForm = false
                            },''',
    '''                            onClick = {
                                val submittedName = nameInput.trim()
                                val submittedUrl = urlInput.trim()
                                nameInput = ""
                                urlInput = ""
                                showAddForm = false
                                onAddProfile(submittedName, submittedUrl)
                            },''',
    "subscription submit",
)
p.write_text(text)


# 3. Add a dedicated rename action on every node card and a dedicated clipboard callback.
p = Path("app/src/main/java/com/rr/client/ui/screens/NodeListScreen.kt")
text = p.read_text()
text = once(
    text,
    "    onPingNode: (ProxyNode) -> Unit,\n    onEditNode: (ProxyNode) -> Unit,",
    "    onPingNode: (ProxyNode) -> Unit,\n    onRenameNode: (ProxyNode, String) -> Unit,\n    onEditNode: (ProxyNode) -> Unit,",
    "screen rename callback",
)
text = once(
    text,
    "    onDeleteLocalNode: (ProxyNode) -> Unit,\n    onImportText: (String) -> Unit,",
    "    onDeleteLocalNode: (ProxyNode) -> Unit,\n    onImportText: (String) -> Unit,\n    onImportClipboard: (String) -> Unit,",
    "screen clipboard callback",
)
text = once(
    text,
    "                                onPingNode = { onPingNode(node) },\n                                onEditNode = { onEditNode(node) },",
    "                                onPingNode = { onPingNode(node) },\n                                onRenameNode = { name -> onRenameNode(node, name) },\n                                onEditNode = { onEditNode(node) },",
    "card rename wiring",
)
text = once(
    text,
    '''                val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                onImportText(text)''',
    '''                val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                onImportClipboard(text)''',
    "clipboard routing",
)
text = once(
    text,
    "    onPingNode: () -> Unit,\n    onEditNode: () -> Unit,",
    "    onPingNode: () -> Unit,\n    onRenameNode: (String) -> Unit,\n    onEditNode: () -> Unit,",
    "card rename parameter",
)
text = once(
    text,
    "    var menuExpanded by remember(node.id) { mutableStateOf(false) }\n",
    "    var menuExpanded by remember(node.id) { mutableStateOf(false) }\n    var showRenameDialog by remember(node.id) { mutableStateOf(false) }\n    var renameInput by remember(node.id) { mutableStateOf(node.tag) }\n",
    "rename state",
)
edit_item = '''                    DropdownMenuItem(
                        text = { Text("编辑节点") },
                        leadingIcon = { Icon(Icons.Default.Edit, null) },
                        onClick = { menuExpanded = false; onEditNode() }
                    )'''
rename_and_edit = '''                    DropdownMenuItem(
                        text = { Text("重命名") },
                        leadingIcon = { Icon(Icons.Default.Edit, null) },
                        onClick = {
                            menuExpanded = false
                            renameInput = node.tag
                            showRenameDialog = true
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("编辑节点") },
                        leadingIcon = { Icon(Icons.Default.Edit, null) },
                        onClick = { menuExpanded = false; onEditNode() }
                    )'''
text = once(text, edit_item, rename_and_edit, "rename menu item")

start = text.index("private fun NodeCard(")
end = text.index("\n@Composable\nprivate fun NodeImportMethodDialog(", start)
segment = text[start:end]
close = segment.rfind("\n}")
if close < 0:
    raise SystemExit("NodeCard closing brace not found")
rename_dialog = '''

    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("重命名节点") },
            text = {
                OutlinedTextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    label = { Text("节点名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val normalized = renameInput.trim()
                        if (normalized.isNotEmpty()) {
                            showRenameDialog = false
                            onRenameNode(normalized)
                        }
                    },
                    enabled = renameInput.trim().isNotEmpty()
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text("取消") }
            }
        )
    }'''
segment = segment[:close] + rename_dialog + segment[close:]
text = text[:start] + segment + text[end:]
p.write_text(text)


# 4. MainActivity: route clipboard subscription URLs to subscription fetcher and persist renames.
p = Path("app/src/main/java/com/rr/client/MainActivity.kt")
text = p.read_text()
text = once(
    text,
    "import com.rr.client.subscription.SubscriptionParser\n",
    "import com.rr.client.subscription.SubscriptionParser\nimport com.rr.client.subscription.SubscriptionUrlNormalizer\n",
    "normalizer import",
)

add_start = text.index("        fun addProfile(name: String, url: String) {")
add_end = text.index("\n        fun refreshProfile(profileId: String) {", add_start)
clipboard_func = '''

        fun importClipboardContent(raw: String) {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) {
                importLocalNodes(raw)
                return
            }
            if (SubscriptionUrlNormalizer.looksLikeSubscriptionAddress(trimmed)) {
                selectedTab = 3
                toast("已识别订阅地址，正在同步")
                addProfile("", trimmed)
            } else {
                importLocalNodes(raw)
            }
        }'''
text = text[:add_end] + clipboard_func + text[add_end:]

schedule_marker = "\n        fun scheduleRoutingRestart("
rename_func = '''

        fun renameNode(node: ProxyNode, requestedName: String) {
            val newName = requestedName.trim()
            if (newName.isEmpty()) {
                toast("节点名称不能为空")
                return
            }
            if (newName == node.tag) return

            val renamed = NodeOverridePatcher.apply(node, node.copy(tag = newName))
            if (node.profileId == SubProfile.LOCAL_PROFILE_ID) {
                val existing = subProfiles.firstOrNull { it.isLocal }?.nodes.orEmpty()
                if (existing.none { it.id == node.id }) {
                    toast("没有找到要重命名的本地节点")
                    return
                }
                val normalized = renamed.copy(
                    profileId = SubProfile.LOCAL_PROFILE_ID,
                    profileName = SubProfile.LOCAL_PROFILE_NAME
                )
                persistLocalNodes(
                    existing.map { if (it.id == node.id) normalized else it },
                    "已重命名为「$newName」"
                )
            } else {
                lifecycleScope.launch {
                    prefs.setNodeOverride(renamed)
                    toast("已重命名为「$newName」")
                }
            }
        }'''
text = once(text, schedule_marker, rename_func + schedule_marker, "rename function")
text = once(
    text,
    "                        onPingNode = ::pingNode,\n                        onEditNode = { node -> editingNode = node },",
    "                        onPingNode = ::pingNode,\n                        onRenameNode = ::renameNode,\n                        onEditNode = { node -> editingNode = node },",
    "rename UI wiring",
)
text = once(
    text,
    "                        onDeleteLocalNode = ::deleteLocalNode,\n                        onImportText = ::importLocalNodes,\n                        onCreateManualNode = { protocol ->",
    "                        onDeleteLocalNode = ::deleteLocalNode,\n                        onImportText = ::importLocalNodes,\n                        onImportClipboard = ::importClipboardContent,\n                        onCreateManualNode = { protocol ->",
    "clipboard UI wiring",
)
p.write_text(text)


gradle = Path("app/build.gradle.kts").read_text()
if 'versionCode = 100' not in gradle or 'versionName = "1.0.0"' not in gradle:
    raise SystemExit("Version changed unexpectedly")
