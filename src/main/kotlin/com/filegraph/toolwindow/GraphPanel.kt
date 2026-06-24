package com.filegraph.toolwindow

import com.filegraph.bridge.JcefBridge
import com.filegraph.model.GraphData
import com.filegraph.service.DependencyGraphService
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
import kotlinx.coroutines.*
import java.awt.BorderLayout
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JPanel

class GraphPanel(
    private val project: Project,
    private val browser: JBCefBrowser,
    private val bridge: JcefBridge
) : JPanel(BorderLayout()), Disposable {

    private var pageReady = false
    private var pendingData: GraphData? = null
    private val isScanning = AtomicBoolean(false)

    private val scheduler = AppExecutorUtil.getAppScheduledExecutorService()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var scheduledFuture: ScheduledFuture<*>? = null
    private var vfsConnection: com.intellij.util.messages.MessageBusConnection? = null
    private var pendingFileChanges = mutableSetOf<String>()

    private val debounceDelay = 1500L
    private val scanTimeout = 60000L

    init {
        add(browser.component, BorderLayout.CENTER)

        bridge.onPageReady = {
            pageReady = true
            pendingData?.let { sendDataToWebView(it) }
            scanProject()
        }

        bridge.onGraphUpdated = { data ->
            if (pageReady) sendDataToWebView(data as GraphData)
        }

        setupFileChangeListener()
    }

    fun scanProject(forceFullScan: Boolean = false) {
        if (DumbService.isDumb(project)) {
            bridge.sendStatus("⏳ Indexing...")
            scheduler.schedule({ scanProject(forceFullScan) }, 2000, TimeUnit.MILLISECONDS)
            return
        }

        if (!isScanning.compareAndSet(false, true)) {
            return
        }

        val changedFiles = if (!forceFullScan && pendingFileChanges.isNotEmpty()) {
            val changes = pendingFileChanges.toSet()
            pendingFileChanges.clear()
            changes
        } else {
            pendingFileChanges.clear()
            null
        }

        bridge.sendStatus("🔄 Scanning project...")
        
        val scanFuture = ReadAction.nonBlocking<GraphData> {
            DependencyGraphService.getInstance(project).buildGraph(changedFiles)
        }
            .finishOnUiThread(ModalityState.defaultModalityState()) { data: GraphData ->
                isScanning.set(false)
                bridge.sendStatus(
                    "✅ Done",
                    "${data.nodes.size} files",
                    "${data.links.size} deps"
                )
                if (pageReady) {
                    sendDataToWebView(data)
                } else {
                    pendingData = data
                }
            }
            .submit(AppExecutorUtil.getAppExecutorService())
        
        scheduler.schedule({
            if (!scanFuture.isDone) {
                scanFuture.cancel(true)
                isScanning.set(false)
                bridge.sendStatus("❌ Scan timed out", "", "", "")
            }
        }, scanTimeout, TimeUnit.MILLISECONDS)
    }

    private fun sendDataToWebView(data: GraphData) {
        bridge.sendGraphData(data)
        pendingData = null
    }

    private fun setupFileChangeListener() {
        vfsConnection = project.messageBus.connect(this)
        vfsConnection?.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: MutableList<out VFileEvent>) {
                val relevantEvents = events.filter(::isRelevant)
                if (relevantEvents.isNotEmpty()) {
                    relevantEvents.forEach { event ->
                        event.file?.let { pendingFileChanges.add(it.path) }
                    }
                    debouncedScan()
                }
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

        val scanDelay = if (pendingFileChanges.size > 10) {
            500L
        } else {
            debounceDelay
        }

        scheduledFuture = scheduler.schedule({ 
            scanProject(forceFullScan = pendingFileChanges.size > 50)
        }, scanDelay, TimeUnit.MILLISECONDS)
    }

    fun cancelScan() {
        scheduledFuture?.cancel(false)
        isScanning.set(false)
    }

    fun refresh() {
        cancelScan()
        scope.launch {
            delay(100)
            scanProject(forceFullScan = true)
        }
    }

    override fun dispose() {
        vfsConnection?.disconnect()
        scheduledFuture?.cancel(false)
        scope.cancel()
    }
}
