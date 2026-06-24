package com.filegraph.service

import com.filegraph.model.GraphData
import com.filegraph.model.GraphEdge
import com.filegraph.model.GraphNode
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import kotlinx.coroutines.*
import org.jetbrains.kotlin.psi.KtFile
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.PROJECT)
class DependencyGraphService(private val project: Project) : Disposable {

    private val LOG = Logger.getInstance(DependencyGraphService::class.java)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    companion object {
        private const val MAX_NODES_BEFORE_CLUSTERING = 800
        private const val MIN_CONNECTIONS_FOR_CLUSTERING = 2

        fun getInstance(project: Project): DependencyGraphService = project.service()
    }

    private data class FileCache(
        val dependencies: Set<String>,
        val lastModified: Long
    )

    private val fileCache = ConcurrentHashMap<String, FileCache>()
    private var cachedProjectFiles: Set<VirtualFile>? = null
    private var pendingChanges = mutableSetOf<String>()

    fun buildGraph(changedFiles: Set<String>? = null): GraphData {
        return try {
            if (changedFiles.isNullOrEmpty()) {
                doFullBuild()
            } else {
                doIncrementalBuild(changedFiles)
            }
        } catch (e: IndexNotReadyException) {
            LOG.debug("Index not ready for graph building")
            GraphData.empty()
        } catch (e: Exception) {
            LOG.error("Error building dependency graph", e)
            GraphData.empty()
        }
    }

    fun markFilesChanged(filePaths: Set<String>) {
        pendingChanges.addAll(filePaths)
        cachedProjectFiles?.let { files ->
            val changed = files.filter { it.path in filePaths }
            changed.forEach { fileCache.remove(it.path) }
        }
    }

    private fun doFullBuild(): GraphData {
        val projectScope = GlobalSearchScope.projectScope(project)
        val psiManager = PsiManager.getInstance(project)

        val projectFiles = mutableSetOf<VirtualFile>()

        val javaType = FileTypeManager.getInstance().findFileTypeByName("JAVA")
        val kotlinType = FileTypeManager.getInstance().findFileTypeByName("Kotlin")

        if (javaType != null) {
            FileTypeIndex.getFiles(javaType, projectScope).forEach { projectFiles.add(it) }
        }
        if (kotlinType != null) {
            FileTypeIndex.getFiles(kotlinType, projectScope).forEach { projectFiles.add(it) }
        }

        if (projectFiles.isEmpty()) {
            return GraphData.empty()
        }

        cachedProjectFiles = projectFiles
        val projectFilePaths = projectFiles.map { it.path }.toSet()

        val edges = mutableListOf<GraphEdge>()
        val fileConnections = mutableMapOf<String, Int>()

        for (vFile in projectFiles) {
            val sourcePath = vFile.path
            val cache = fileCache[sourcePath]

            val dependencies = if (cache != null && cache.lastModified >= vFile.modificationStamp) {
                cache.dependencies
            } else {
                val psiFile = psiManager.findFile(vFile) ?: continue
                val deps = when (psiFile) {
                    is PsiJavaFile -> extractJavaDependencies(psiFile, projectFilePaths)
                    is KtFile -> extractKotlinDependencies(psiFile, projectFilePaths)
                    else -> emptySet()
                }
                fileCache[sourcePath] = FileCache(deps, vFile.modificationStamp)
                deps
            }

            for (targetPath in dependencies) {
                if (targetPath != sourcePath && targetPath in projectFilePaths) {
                    edges.add(GraphEdge(source = sourcePath, target = targetPath))
                    fileConnections[sourcePath] = (fileConnections[sourcePath] ?: 0) + 1
                    fileConnections[targetPath] = (fileConnections[targetPath] ?: 0) + 1
                }
            }
        }

        val circularEdges = detectCircularDependenciesIterative(edges)

        val nodes = projectFiles.map { vFile ->
            val path = vFile.path
            val relativePath = getRelativePath(path)
            val directory = relativePath.substringBeforeLast("/", "root")

            GraphNode(
                id = path,
                name = vFile.name,
                path = path,
                extension = vFile.extension ?: "",
                directory = directory,
                connections = fileConnections[path] ?: 0,
                isCircular = circularEdges.any { it.source == path || it.target == path }
            )
        }

        if (nodes.size > MAX_NODES_BEFORE_CLUSTERING) {
            val (filteredNodes, filteredEdges) = clusterLowConnectivityNodes(
                nodes, edges, fileConnections, circularEdges
            )
            return GraphData(nodes = filteredNodes, links = filteredEdges)
        }

        val finalEdges = edges.map { edge ->
            val isCircular = circularEdges.any {
                (it.source == edge.source && it.target == edge.target) ||
                    (it.source == edge.target && it.target == edge.source)
            }
            edge.copy(isCircular = isCircular)
        }

        return GraphData(nodes = nodes, links = finalEdges)
    }

    private fun doIncrementalBuild(changedFiles: Set<String>): GraphData {
        val fullBuild = doFullBuild()
        pendingChanges.clear()
        return fullBuild
    }

    private fun clusterLowConnectivityNodes(
        nodes: List<GraphNode>,
        edges: List<GraphEdge>,
        connections: Map<String, Int>,
        circularEdges: Set<GraphEdge>
    ): Pair<List<GraphNode>, List<GraphEdge>> {
        val lowConnNodes = nodes.filter { 
            it.connections <= MIN_CONNECTIONS_FOR_CLUSTERING && !it.isCircular 
        }.toSet()

        val remainingNodes = nodes.filter { it !in lowConnNodes }
        val remainingIds = remainingNodes.map { it.id }.toSet()

        val filteredEdges = edges.filter { 
            (it.source in remainingIds) && (it.target in remainingIds)
        }

        val clusterNode = GraphNode(
            id = "__cluster__",
            name = "+${lowConnNodes.size} files",
            path = "",
            extension = "",
            directory = "Clustered",
            connections = lowConnNodes.sumOf { it.connections },
            isCircular = false
        )

        return Pair(listOf(clusterNode) + remainingNodes, filteredEdges)
    }

    private fun getRelativePath(path: String): String {
        val basePath = project.basePath ?: return path
        return if (path.startsWith(basePath)) {
            path.removePrefix(basePath).removePrefix("/")
        } else {
            path
        }
    }

    private fun extractJavaDependencies(
        javaFile: PsiJavaFile,
        projectFilePaths: Set<String>
    ): Set<String> {
        val deps = mutableSetOf<String>()

        javaFile.importList?.importStatements?.forEach { importStatement ->
            if (deps.size > 100) return@forEach // Safety limit
            try {
                safeResolve(importStatement.resolve(), projectFilePaths)?.let { deps.add(it) }
            } catch (e: Exception) {
                LOG.debug("Failed to resolve import: ${e.message}")
            }
        }

        javaFile.classes.forEach { psiClass ->
            try {
                psiClass.extendsList?.referenceElements?.forEach { ref ->
                    safeResolve(ref.resolve(), projectFilePaths)?.let { deps.add(it) }
                }
                psiClass.implementsList?.referenceElements?.forEach { ref ->
                    safeResolve(ref.resolve(), projectFilePaths)?.let { deps.add(it) }
                }
            } catch (e: Exception) {
                LOG.debug("Failed to resolve class references: ${e.message}")
            }
        }

        return deps
    }

    private fun extractKotlinDependencies(
        ktFile: KtFile,
        projectFilePaths: Set<String>
    ): Set<String> {
        val deps = mutableSetOf<String>()

        ktFile.importDirectives.forEach { importDirective ->
            if (deps.size > 100) return@forEach // Safety limit
            
            for (ref in importDirective.references) {
                try {
                    val resolved = ref.resolve()
                    safeResolve(resolved, projectFilePaths)?.let { deps.add(it) }
                } catch (e: Exception) {
                    LOG.debug("Failed to resolve import reference: ${e.message}")
                }
            }
        }

        return deps
    }

    private fun safeResolve(element: PsiElement?, projectFilePaths: Set<String>): String? {
        if (element == null) return null

        val containingFile = when (element) {
            is PsiClass -> element.containingFile?.virtualFile
            else -> element.containingFile?.virtualFile
        }

        val path = containingFile?.path ?: return null
        return if (path in projectFilePaths) path else null
    }

    private fun detectCircularDependenciesIterative(edges: List<GraphEdge>): Set<GraphEdge> {
        val adjacency = mutableMapOf<String, MutableList<String>>()
        for (edge in edges) {
            adjacency.getOrPut(edge.source) { mutableListOf() }.add(edge.target)
        }

        val circularEdges = mutableSetOf<GraphEdge>()
        val visited = mutableSetOf<String>()
        val recursionStack = mutableSetOf<String>()
        
        fun dfs(node: String, path: List<String>): Boolean {
            visited.add(node)
            recursionStack.add(node)
            
            for (neighbor in adjacency[node] ?: emptyList()) {
                if (neighbor !in visited) {
                    if (dfs(neighbor, path + node)) {
                        return true
                    }
                } else if (neighbor in recursionStack) {
                    val cycleStart = path.indexOf(neighbor)
                    val cycle = if (cycleStart >= 0) {
                        path.subList(cycleStart, path.size) + node + neighbor
                    } else {
                        listOf(node, neighbor)
                    }
                    for (i in 0 until cycle.size - 1) {
                        circularEdges.add(GraphEdge(source = cycle[i], target = cycle[i + 1], isCircular = true))
                    }
                    return true
                }
            }
            
            recursionStack.remove(node)
            return false
        }

        for (node in adjacency.keys) {
            if (node !in visited) {
                dfs(node, emptyList())
            }
        }

        return circularEdges
    }

    override fun dispose() {
        scope.cancel()
        fileCache.clear()
    }
}
