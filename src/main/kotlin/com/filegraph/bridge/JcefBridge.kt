package com.filegraph.bridge

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import com.intellij.openapi.diagnostic.Logger

class JcefBridge(
    private val browser: JBCefBrowser,
    private val project: Project
) : Disposable {

    private val nodeClickQuery = JBCefJSQuery.create(browser)
    private val LOG = Logger.getInstance(JcefBridge::class.java)

    var onPageReady: (() -> Unit)? = null

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
            LOG.warn("JS execution failed", e)
        }
    }

    fun sendGraphData(jsonData: String) {
        val escaped = jsonData
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
        executeJs("window.updateGraph && window.updateGraph('$escaped')")
    }

    fun highlightFile(filePath: String) {
        val escaped = filePath.replace("\\", "\\\\").replace("'", "\\'")
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
