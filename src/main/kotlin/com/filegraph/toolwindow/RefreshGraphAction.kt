package com.filegraph.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

class RefreshGraphAction(
    private val graphPanel: GraphPanel
) : AnAction("Refresh Graph", "Re-scan project dependencies", AllIcons.Actions.Refresh) {

    override fun actionPerformed(e: AnActionEvent) {
        graphPanel.scanProject()
    }
}
