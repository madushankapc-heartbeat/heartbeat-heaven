val prepareFinalAppFixes = tasks.register("prepareFinalAppFixes") {
    doLast {
        fun patchFile(path: String, transform: (String) -> String) {
            val file = project.file(path)
            val text = file.readText()
            val updated = transform(text)
            if (updated != text) file.writeText(updated)
        }

        patchFile("src/main/java/com/heartbeatheaven/app/UsersAdminActivity.kt") { text ->
            var t = text
            if (!t.contains("import android.content.Intent")) t = t.replace("import android.os.Bundle", "import android.content.Intent\nimport android.os.Bundle")
            t.replace("Button(onClick = { showMessages = true }, modifier = Modifier.fillMaxWidth())", "Button(onClick = { context.startActivity(Intent(context, OwnerMessagesActivity::class.java)) }, modifier = Modifier.fillMaxWidth())")
        }

        patchFile("src/main/java/com/heartbeatheaven/app/FriendsScreen.kt") { text ->
            var t = text
            if (!t.contains("material3.pulltorefresh.PullToRefreshBox")) t = t.replace("import androidx.compose.material3.*", "import androidx.compose.material3.*\nimport androidx.compose.material3.pulltorefresh.PullToRefreshBox\nimport androidx.compose.material3.pulltorefresh.rememberPullToRefreshState")
            if (!t.contains("@OptIn(ExperimentalMaterial3Api::class)\n@Composable\ninternal fun FriendsScreen")) t = t.replace("@Composable\ninternal fun FriendsScreen", "@OptIn(ExperimentalMaterial3Api::class)\n@Composable\ninternal fun FriendsScreen")
            val marker = "    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {"
            if (marker in t && !t.contains("PullToRefreshBox(isRefreshing = busy")) {
                t = t.replace(marker, "    val refreshState = rememberPullToRefreshState()\n    PullToRefreshBox(isRefreshing = busy, onRefresh = { reload() }, state = refreshState, modifier = Modifier.fillMaxSize()) {\n" + marker)
                val end = "    }\n}"
                if (t.endsWith(end)) t = t.removeSuffix(end) + "    }\n    }\n}"
            }
            t
        }

        patchFile("src/main/java/com/heartbeatheaven/app/MainActivity.kt") { text ->
            var t = text
            if (!t.contains("import kotlinx.coroutines.launch")) t = t.replace("import kotlinx.coroutines.delay", "import kotlinx.coroutines.delay\nimport kotlinx.coroutines.launch")
            if (!t.contains("material3.pulltorefresh.PullToRefreshBox")) t = t.replace("import androidx.compose.material3.*", "import androidx.compose.material3.*\nimport androidx.compose.material3.pulltorefresh.PullToRefreshBox\nimport androidx.compose.material3.pulltorefresh.rememberPullToRefreshState")
            if (!t.contains("var refreshing by remember { mutableStateOf(false) }")) t = t.replace("val authSession = remember { AuthApi(context).currentSession() }", "val authSession = remember { AuthApi(context).currentSession() }\n    val refreshScope = rememberCoroutineScope()\n    var refreshing by remember { mutableStateOf(false) }\n    val refreshState = rememberPullToRefreshState()")
            val outer = "        Column(Modifier.fillMaxSize().padding(padding)) {"
            if (outer in t && !t.contains("PullToRefreshBox(isRefreshing = refreshing")) {
                val wrapped = "        PullToRefreshBox(\n            isRefreshing = refreshing,\n            onRefresh = {\n                if (!refreshing && (tab == 0 || tab == 3)) {\n                    refreshing = true\n                    refreshScope.launch {\n                        runCatching { fetchSongs() }\n                            .onSuccess { songs = it; error = null }\n                            .onFailure { error = \"Unable to refresh. Please check your connection.\" }\n                        refreshing = false\n                    }\n                }\n            },\n            state = refreshState,\n            modifier = Modifier.fillMaxSize()\n        ) {\n" + outer
                t = t.replace(outer, wrapped)
                val exactEnd = "        }\n    }\n}\n\n@Composable\nprivate fun SongCard"
                val idx = t.lastIndexOf(exactEnd)
                if (idx >= 0) t = t.substring(0, idx) + "        }\n        }\n    }\n}\n\n@Composable\nprivate fun SongCard" + t.substring(idx + exactEnd.length)
            }
            t
        }
    }
}

tasks.named("preBuild").configure { dependsOn(prepareFinalAppFixes) }
