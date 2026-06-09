package com.filegraph.model

data class GraphNode(
    val id: String,
    val name: String,
    val path: String,
    val extension: String,
    val directory: String,
    val connections: Int = 0,
    val isCircular: Boolean = false
)

data class GraphEdge(
    val source: String,
    val target: String,
    val isCircular: Boolean = false
)

data class GraphData(
    val nodes: List<GraphNode>,
    val links: List<GraphEdge>
) {
    companion object {
        fun empty() = GraphData(listOf(), listOf())
    }
}