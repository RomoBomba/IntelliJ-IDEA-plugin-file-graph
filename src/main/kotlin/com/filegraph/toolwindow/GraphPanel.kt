package com.filegraph.toolwindow

import com.filegraph.bridge.JcefBridge
import com.filegraph.model.GraphData
import com.filegraph.service.DependencyGraphService
import com.google.gson.Gson
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.util.concurrency.AppExecutorUtil
import java.awt.BorderLayout
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.swing.JPanel

class GraphPanel(
    private val project: Project,
    private val browser: JBCefBrowser,
    private val bridge: JcefBridge
) : JPanel(BorderLayout()), Disposable {

    private val gson = Gson()
    private var pageReady = false
    private var pendingData: GraphData? = null

    private val scheduler = AppExecutorUtil.getAppScheduledExecutorService()
    private var scheduledFuture: ScheduledFuture<*>? = null
    private var vfsConnection: com.intellij.util.messages.MessageBusConnection? = null

    init {
        add(browser.component, BorderLayout.CENTER)

        bridge.onPageReady = {
            pageReady = true
            pendingData?.let { sendDataToWebView(it) }
            scanProject()
        }

        setupFileChangeListener()
    }

    fun scanProject() {
        if (DumbService.isDumb(project)) return

        ReadAction.nonBlocking<GraphData> {
            DependencyGraphService.getInstance(project).buildGraph()
        }
            .finishOnUiThread(ModalityState.defaultModalityState()) { data ->
                if (pageReady) sendDataToWebView(data) else pendingData = data
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun sendDataToWebView(data: GraphData) {
        bridge.sendGraphData(gson.toJson(data))
        pendingData = null
    }

    private fun setupFileChangeListener() {
        vfsConnection = project.messageBus.connect(this)
        vfsConnection?.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: MutableList<out VFileEvent>) {
                if (events.any(::isRelevant)) debouncedScan()
            }
        })
    }

    private fun isRelevant(event: VFileEvent): Boolean {
        val file = event.file ?: return false
        val ext = file.extension?.lowercase() ?: return false
        return ext in setOf("java", "kt", "kts") && (
            event is VFileContentChangeEvent ||
                event is VFileCreateEvent ||
                event is VFileDeleteEvent)
    }

    private fun debouncedScan() {
        scheduledFuture?.cancel(false)
        scheduledFuture = scheduler.schedule({ scanProject() }, 1500, TimeUnit.MILLISECONDS)
    }

    override fun dispose() {
        vfsConnection?.disconnect()
        scheduledFuture?.cancel(false)
    }
}
