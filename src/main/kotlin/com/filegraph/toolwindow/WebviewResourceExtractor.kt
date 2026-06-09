package com.filegraph.toolwindow

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

object WebviewResourceExtractor {

    private val RESOURCE_FILES = listOf(
        "index.html",
        "graph.js",
        "styles.css",
        "force-graph.min.js"
    )

    private var cachedIndex: File? = null
    private var tempDir: File? = null

    fun extract(): File {
        cachedIndex?.let { return it }

        val dir = Files.createTempDirectory("filegraph-webview").toFile()
        dir.deleteOnExit()
        tempDir = dir

        for (name in RESOURCE_FILES) {
            val stream = this::class.java.getResourceAsStream("/webview/$name") ?: continue
            val target = File(dir, name)
            stream.use { input ->
                Files.copy(input, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            target.deleteOnExit()
        }

        cachedIndex = File(dir, "index.html")
        return cachedIndex!!
    }
}
