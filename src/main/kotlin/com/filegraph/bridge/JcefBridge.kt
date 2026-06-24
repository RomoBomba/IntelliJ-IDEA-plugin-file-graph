package com.filegraph.bridge

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter

class JcefBridge(
    private val browser: JBCefBrowser,
    private val project: Project
) : Disposable {

    private val nodeClickQuery = JBCefJSQuery.create(browser)
    private val LOG = Logger.getInstance(JcefBridge::class.java)
    private val gson: Gson = GsonBuilder().create()

    var onPageReady: (() -> Unit)? = null
    var onGraphUpdated: ((Any) -> Unit)? = null

    init {
        nodeClickQuery.addHandler { request ->
            openFileInEditor(request.trim())
            JBCefJSQuery.Response("ok")
        }
        injectBridgeOnLoad()
    }

    private fun injectBridgeOnLoad() {
        browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(cefBrowser: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                if (frame?.isMain == true) {
                    val js = """
                        window.openFileInIDE = function(filePath) {
                            ${nodeClickQuery.inject("filePath")}
                        };
                        if (window.onBridgeReady) window.onBridgeReady();
                    """.trimIndent()
                    cefBrowser?.executeJavaScript(js, cefBrowser.url, 0)
                    ApplicationManager.getApplication().invokeLater {
                        onPageReady?.invoke()
                    }
                }
            }
        }, browser.cefBrowser)
    }

    private fun executeJs(script: String) = ApplicationManager.getApplication().invokeLater {
        try {
            browser.cefBrowser.executeJavaScript(script, browser.cefBrowser.url, 0)
        } catch (e: Exception) {
            LOG.warn("JS execution failed: ${e.message}")
        }
    }

    fun sendGraphData(data: Any) {
        try {
            val jsonData = gson.toJson(data)
            val escaped = escapeForJavaScript(jsonData)
            executeJs("window.updateGraph && window.updateGraph('$escaped')")
        } catch (e: Exception) {
            LOG.error("Failed to serialize graph data", e)
            sendStatus("❌ Error", "", "", "")
        }
    }

    fun sendStatus(status: String, nodeCount: String = "", edgeCount: String = "", circularWarn: String = "") {
        val escapedStatus = escapeForJavaScript(status)
        val escapedNodes = escapeForJavaScript(nodeCount)
        val escapedEdges = escapeForJavaScript(edgeCount)
        val escapedCirc = escapeForJavaScript(circularWarn)

        executeJs("""
            (function() {
                var s = document.getElementById('status');
                if (s) { s.textContent = '$escapedStatus'; s.style.color = '${
                    if (status.startsWith("❌")) "#f44747" 
                    else if (status.startsWith("🔄") || status.startsWith("⏳")) "#e5c07b" 
                    else if (status.startsWith("✅")) "#4ec9b0"
                    else "#cccccc"
                }'; }
                var n = document.getElementById('node-count');
                if (n) n.textContent = '$escapedNodes';
                var e = document.getElementById('edge-count');
                if (e) e.textContent = '$escapedEdges';
                var c = document.getElementById('circular-warn');
                if (c) c.textContent = '$escapedCirc';
            })()
        """.trimIndent())
    }

    private fun escapeForJavaScript(str: String): String {
        return str
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
            .replace("<", "\\x3C")
            .replace(">", "\\x3E")
    }

    fun highlightFile(filePath: String) {
        val escaped = escapeForJavaScript(filePath)
        executeJs("window.highlightNodeById && window.highlightNodeById('$escaped')")
    }

    private fun openFileInEditor(filePath: String) {
        val vf = LocalFileSystem.getInstance().findFileByPath(filePath) ?: return
        ApplicationManager.getApplication().invokeLater {
            FileEditorManager.getInstance(project).openFile(vf, true)
        }
    }

    override fun dispose() {
        nodeClickQuery.dispose()
    }
}
