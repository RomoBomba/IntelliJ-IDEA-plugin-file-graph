package com.filegraph.toolwindow

import com.filegraph.bridge.JcefBridge
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import javax.swing.JLabel
import javax.swing.SwingConstants

class GraphToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        if (!JBCefApp.isSupported()) {
            val label = JLabel(
                "JCEF is not supported in this environment",
                SwingConstants.CENTER
            )
            val content = ContentFactory.getInstance()
                .createContent(label, "File Graph", false)
            toolWindow.contentManager.addContent(content)
            return
        }

        val browser = JBCefBrowser.createBuilder()
            .build()

        val bridge = JcefBridge(browser, project)
        val graphPanel = GraphPanel(project, browser, bridge)

        val indexFile = WebviewResourceExtractor.extract()
        if (indexFile.exists()) {
            browser.loadURL("file://${indexFile.absolutePath}")
        } else {
            browser.loadHTML(
                "<html><body style='color:white;background:#1e1e1e'><h1>Error: Could not extract webview resources</h1></body></html>"
            )
        }

        val content = ContentFactory.getInstance()
            .createContent(graphPanel, "File Graph", false)
        toolWindow.contentManager.addContent(content)

        toolWindow.setTitleActions(listOf(RefreshGraphAction(graphPanel)))

        Disposer.register(toolWindow.disposable, browser)
        Disposer.register(toolWindow.disposable, bridge)
        Disposer.register(toolWindow.disposable, graphPanel)
    }
}
