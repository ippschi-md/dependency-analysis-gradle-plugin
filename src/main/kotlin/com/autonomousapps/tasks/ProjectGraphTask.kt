package com.autonomousapps.tasks

import com.autonomousapps.TASK_GROUP_DEP
import com.autonomousapps.internal.graph.GraphViewBuilder
import com.autonomousapps.internal.graph.GraphWriter
import com.autonomousapps.internal.utils.getAndDelete
import org.gradle.api.DefaultTask
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

@CacheableTask
abstract class ProjectGraphTask : DefaultTask() {

  init {
    group = TASK_GROUP_DEP
    description = "Generates a graph view of this project's local dependency graph"
  }

  /** Used for logging. */
  @get:Input
  abstract val projectPath: Property<String>

  /**
   * Used for relativizing output paths for logging. Internal because we don't want Gradle to hash the entire project.
   */
  @get:Internal
  abstract val rootDir: DirectoryProperty

  @get:Input
  abstract val compileClasspath: Property<ResolvedComponentResult>

  @get:Input
  abstract val runtimeClasspath: Property<ResolvedComponentResult>

  @get:OutputDirectory
  abstract val output: DirectoryProperty

  @TaskAction fun action() {
    val compileOutput = output.file("project-compile-classpath.gv").getAndDelete()
    val runtimeOutput = output.file("project-runtime-classpath.gv").getAndDelete()

    val compileGraph = GraphViewBuilder(
      root = compileClasspath.get(),
      fileCoordinates = emptySet(),
      localOnly = true,
    ).graph

    val runtimeGraph = GraphViewBuilder(
      root = runtimeClasspath.get(),
      fileCoordinates = emptySet(),
      localOnly = true,
    ).graph

    compileOutput.writeText(GraphWriter.toDot(compileGraph))
    runtimeOutput.writeText(GraphWriter.toDot(runtimeGraph))

    // Print a message so users know how to do something with the generated .gv files.
    val msg = buildString {
      // convert ":foo:bar" to "foo-bar.svg"
      val svgName = projectPath.get().removePrefix(":").replace(':', '-') + ".svg"

      // Get relative paths to output for more readable logging
      val rootPath = rootDir.get().asFile
      val compilePath = compileOutput.relativeTo(rootPath)
      val runtimePath = runtimeOutput.relativeTo(rootPath)

      appendLine("Graphs generated to:")
      appendLine(" - $compilePath")
      appendLine(" - $runtimePath")
      appendLine()
      appendLine("To generate an SVG with graphviz, you could run the following. (You must have graphviz installed.)")
      appendLine()
      appendLine("    dot -Tsvg $runtimePath -o $svgName")
    }

    logger.quiet(msg)
  }
}
