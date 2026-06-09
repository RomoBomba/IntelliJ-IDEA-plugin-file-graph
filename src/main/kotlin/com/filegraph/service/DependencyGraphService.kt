package com.filegraph.service

import com.filegraph.model.GraphData
import com.filegraph.model.GraphEdge
import com.filegraph.model.GraphNode
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
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
import org.jetbrains.kotlin.psi.KtFile

@Service(Service.Level.PROJECT)
class DependencyGraphService(private val project: Project) {

    companion object {
        fun getInstance(project: Project): DependencyGraphService = project.service()
    }

    fun buildGraph(): GraphData {
        return try {
            doBuildGraph()
        } catch (e: IndexNotReadyException) {
            GraphData.empty()
        }
    }

    private fun doBuildGraph(): GraphData {
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

        val projectFilePaths = projectFiles.map { it.path }.toSet()

        val edges = mutableListOf<GraphEdge>()
        val fileConnections = mutableMapOf<String, Int>()

        for (vFile in projectFiles) {
            val psiFile = psiManager.findFile(vFile) ?: continue
            val sourcePath = vFile.path

            val dependencies: Set<String> = when (psiFile) {
                is PsiJavaFile -> extractJavaDependencies(psiFile, projectFilePaths)
                is KtFile -> extractKotlinDependencies(psiFile, projectFilePaths)
                else -> emptySet()
            }

            for (targetPath in dependencies) {
                if (targetPath != sourcePath) {
                    edges.add(GraphEdge(source = sourcePath, target = targetPath))
                    fileConnections[sourcePath] = (fileConnections[sourcePath] ?: 0) + 1
                    fileConnections[targetPath] = (fileConnections[targetPath] ?: 0) + 1
                }
            }
        }

        val circularEdges = detectCircularDependencies(edges)

        val basePath = project.basePath ?: ""
        val nodes = projectFiles.map { vFile ->
            val path = vFile.path
            val relativePath = if (path.startsWith(basePath)) {
                path.removePrefix(basePath).removePrefix("/")
            } else {
                path
            }
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

        val finalEdges = edges.map { edge ->
            val isCircular = circularEdges.any {
                (it.source == edge.source && it.target == edge.target) ||
                    (it.source == edge.target && it.target == edge.source)
            }
            edge.copy(isCircular = isCircular)
        }

        return GraphData(nodes = nodes, links = finalEdges)
    }

    private fun extractJavaDependencies(
        javaFile: PsiJavaFile,
        projectFilePaths: Set<String>
    ): Set<String> {
        val deps = mutableSetOf<String>()

        try {
            javaFile.importList?.importStatements?.forEach { importStatement ->
                safeResolve(importStatement.resolve(), projectFilePaths)?.let { deps.add(it) }
            }

            javaFile.classes.forEach { psiClass ->
                psiClass.extendsList?.referenceElements?.forEach { ref ->
                    safeResolve(ref.resolve(), projectFilePaths)?.let { deps.add(it) }
                }
                psiClass.implementsList?.referenceElements?.forEach { ref ->
                    safeResolve(ref.resolve(), projectFilePaths)?.let { deps.add(it) }
                }
            }
        } catch (e: IndexNotReadyException) {
        } catch (e: Exception) {
        }

        return deps
    }

    private fun extractKotlinDependencies(
        ktFile: KtFile,
        projectFilePaths: Set<String>
    ): Set<String> {
        val deps = mutableSetOf<String>()

        try {
            ktFile.importDirectives.forEach { importDirective ->
                for (ref in importDirective.references) {
                    val resolved = try {
                        ref.resolve()
                    } catch (e: Exception) {
                        null
                    }
                    safeResolve(resolved, projectFilePaths)?.let { deps.add(it) }
                }
            }
        } catch (e: IndexNotReadyException) {
        } catch (e: Exception) {
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

    private fun detectCircularDependencies(edges: List<GraphEdge>): Set<GraphEdge> {
        val adjacency = mutableMapOf<String, MutableList<String>>()
        for (edge in edges) {
            adjacency.getOrPut(edge.source) { mutableListOf() }.add(edge.target)
        }

        val circularEdges = mutableSetOf<GraphEdge>()
        val visited = mutableSetOf<String>()
        val inStack = mutableSetOf<String>()

        fun dfs(node: String, path: List<String>) {
            if (node in inStack) {
                val cycleStart = path.indexOf(node)
                if (cycleStart >= 0) {
                    val cycle = path.subList(cycleStart, path.size) + node
                    for (i in 0 until cycle.size - 1) {
                        circularEdges.add(GraphEdge(source = cycle[i], target = cycle[i + 1], isCircular = true))
                    }
                }
                return
            }
            if (node in visited) return

            visited.add(node)
            inStack.add(node)
            adjacency[node]?.forEach { neighbor ->
                dfs(neighbor, path + node)
            }
            inStack.remove(node)
        }

        adjacency.keys.forEach { node ->
            if (node !in visited) {
                dfs(node, emptyList())
            }
        }

        return circularEdges
    }
}
