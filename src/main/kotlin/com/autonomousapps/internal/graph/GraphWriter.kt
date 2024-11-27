// Copyright (c) 2024. Tony Robalik.
// SPDX-License-Identifier: Apache-2.0
package com.autonomousapps.internal.graph

import com.autonomousapps.graph.Graphs.root
import com.autonomousapps.graph.Topological
import com.autonomousapps.internal.utils.appendReproducibleNewLine
import com.autonomousapps.model.Coordinates
import com.autonomousapps.model.IncludedBuildCoordinates
import com.autonomousapps.model.ProjectCoordinates
import com.google.common.graph.Graph

@Suppress("UnstableApiUsage")
internal class GraphWriter(private val buildPath: String) {

  fun toDot(graph: Graph<Coordinates>): String = buildString {
    val projectNodes = graph.nodes().asSequence()
      // Maybe transform into resolvedProject for more human-readable reporting
      .map { it.maybeProjectCoordinates(buildPath) }
      .filterIsInstance<ProjectCoordinates>()
      .map { it.gav() }
      .toList()

    appendReproducibleNewLine("strict digraph DependencyGraph {")
    appendReproducibleNewLine("  ratio=0.6;")
    appendReproducibleNewLine("  node [shape=box];")

    // styling for project nodes
    if (projectNodes.isNotEmpty()) appendReproducibleNewLine()
    projectNodes.forEach {
      appendReproducibleNewLine("  \"$it\" [style=filled fillcolor=\"#008080\"];")
    }
    if (projectNodes.isNotEmpty()) appendReproducibleNewLine()

    // the graph itself
    graph.edges().forEach { edge ->
      val source = edge.nodeU().maybeProjectCoordinates(buildPath)
      val target = edge.nodeV().maybeProjectCoordinates(buildPath)
      val style =
        if (source is ProjectCoordinates && target is ProjectCoordinates) " [style=bold color=\"#FF6347\" weight=8]"
        else ""
      append("  \"${source.gav()}\" -> \"${target.gav()}\"$style;")
      append("\n")
    }
    append("}")
  }

  /**
   * Returns the [graph] sorted into topological order, ascending. Each node in the graph is paired with its in-degree,
   * or the number of dependents.
   *
   * TODO(tsr): not sure if including the in-degree has any value. What I really want is a set of batches that are
   *  internally independent. Essentially, I want a work graph that is maximally parallelizable.
   */
  fun topological(graph: Graph<Coordinates>): String {
    return Topological(graph, graph.root())
      .order
      .joinToString(separator = "\n") {
        // ":foo:bar 0"
        // ":other:baz 1"
        it.maybeProjectCoordinates(buildPath).gav() + " ${graph.inDegree(it)}"
      }
  }

  /**
   * Might transform [this][Coordinates] into [ProjectCoordinates], if it is an [IncludedBuildCoordinates] that is from
   * "this" build (with buildPath == [buildPath] when that is non-null).
   */
  private fun Coordinates.maybeProjectCoordinates(buildPath: String): Coordinates {
    return if (this is IncludedBuildCoordinates && isForBuild(buildPath)) resolvedProject else this
  }
}
