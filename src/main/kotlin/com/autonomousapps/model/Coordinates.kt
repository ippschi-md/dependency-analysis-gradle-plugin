// Copyright (c) 2024. Tony Robalik.
// SPDX-License-Identifier: Apache-2.0
package com.autonomousapps.model

import com.google.common.hash.Hashing
import com.squareup.moshi.JsonClass
import java.nio.charset.StandardCharsets

@JsonClass(generateAdapter = false)
data class Coordinates(
  /**
   * The 'artifact' name part identifying a component. This corresponds to the Artifact part of Group-Artifact (GA)
   * coordinates and to the name of a local project.
   */
  val artifact: String,
  /**
   * The 'group' part identifying a component. This corresponds to the Group part of Group-Artifact (GA) coordinates.
   * The group may be unknown for local projects at a certain point of time. Then the 'projectParentPath' needs to
   * be used to uniquely identify a project instead.
   */
  val group: String?,
  /**
   * The Gradle parent path that, combined with the 'artifact' gives the full project path in the Gradle sense.
   * For example, for a project with the path ':server:model', the 'projectParentPath' would be ':server:'.
   * For a project with the path ':model', the 'projectParentPath' would be ':'.
   */
  val projectPath: String?,
  /**
   * The build to which the project referred to by the 'projectPath' belongs.
   */
  val buildPath: String?,
  /**
   * Capabilities to distinguish different variants of a component that can co-exist in a graph. Capabilities are part
   * of the identity of a node.
   */
  val capabilities: Set<String>,
  /**
   * Selected attributes that have influence on which variant of the component is selected. We only record attributes
   * known in the JVM ecosystem that have an influence on the dependency analyses, such as Category
   * (library vs platform). Attributes are not part of the identity of a node as there can only be multiple nodes
   * with the same coordinates if their capabilities differ.
   */
  val attributes: Map<String, String>,
  /**
   * The version if it is known. This is purely for reporting versions as part of the advice. It is not part of the
   * identity of a node, as Gradle can always only select one version of a component in a graph.
   */
  val version: String?,
  /**
   * Which notation should be preferred when presenting these coordinates to the user in an advice.
   * This should be used as follows:
   * - If the advice is about a dependency between two projects of the same build, the 'projectPath' notation should
   *   be used, unless there is already a dependency declaration that should be changed in which the user used the 'GA'
   *   notation to refer to another project in the same build.
   * - If the advice is pointing at a published component or a project from another build, the 'GA' notation should
   *   be used.
   */
  val preferredCoordinatesNotation: CoordinatesNotation
) {

  init {
    require(projectPath == null || projectPath == ":" || projectPath.endsWith(":$artifact")) {
      "last segment of 'projectPath' and artifact must be equal"
    }
    require(group != null || projectPath != null) {
      "'group' and 'projectPath' cannot both be null"
    }
    require(projectPath != null && buildPath == null) {
      "If the 'projectPath' is provided, the 'buildPath' also needs to be provided"
    }
  }

  fun ga() = if (groupKnown()) "$group:$artifact" else null

  /**
   * Returns the preferred notation for these coordinates. Respects the 'preferredCoordinatesNotation' setting, unless
   * it is about a dependency between two projects of different builds. That's why this method has a 'from' parameter.
   * Only use this to present the coordinates to the user. Not for identity checks.
   */
  fun preferredIdentifier(from: Coordinates) = when {
    preferredCoordinatesNotation == CoordinatesNotation.PROJECT && from.buildPath == buildPath -> projectPath!!
    else -> ga()!!
  }

  /**
   * Is the 'group' for these coordinates known?
   */
  fun groupKnown() = group != null

  /**
   * Do these coordinates have a 'projectPath', i.e. do they represent a local project?
   */
  fun isLocalProject() = projectPath != null

  /**
   * Do two coordinates represent the same graph node?
   */
  fun matches(other: Coordinates): Boolean {
    val normalizedThis = this.withoutDefaultCapability()
    val normalizedOther = other.withoutDefaultCapability()
    if (groupKnown() && other.groupKnown() && ga() == other.ga()) {
      return normalizedThis.capabilities == normalizedOther.capabilities
    }
    if (buildPath == other.buildPath && projectPath == other.projectPath) {
      return normalizedThis.capabilities == normalizedOther.capabilities
    }
    return false;
  }

  fun matches(id: String): Boolean {
    return id == projectPath || id == ga()
  }

  fun matches(regex: Regex): Boolean {
    val project = projectPath
    val ga = ga()
    return project != null && regex.matches(project) || ga != null && regex.matches(ga)
  }

  private fun serializableID() = when {
    groupKnown() -> ga()!!.replace(":", "__")
    else -> projectPath!!.replace(":", "__")
  }

  fun toFileName() = capabilitiesWithoutDefault().let { capabilities ->
    when {
      capabilities.isEmpty() -> "${serializableID()}.json"
      // In case we have capabilities, we use a unique hash for the capability combination in the file name
      // to not mix dependencies with different capabilities.
      else -> "${serializableID()}__${fingerprint(capabilities)}.json"
    }
  }

  private fun fingerprint(capabilities: List<String>) =
    Hashing.fingerprint2011().hashString(capabilities.joinToString("_"), StandardCharsets.UTF_8)

  /**
   * In case of an 'ADD' advice, the GradleVariantIdentification is directly sourced from the selected node
   * in the dependency graph. It's hard (or impossible with Gradle's current APIs) to find the exact declaration that
   * let to selecting that node. If we could find that declaration, we could use it for the ADD advice.
   * Right now, we use the details from the node in the Graph which may contain more capabilities as you need
   * to declare. In particular, it also contains the 'default capability', which makes it conceptually equal to
   * Coordinates without capability.
   * In order to correctly reduce advices (e.g. merge a REMOVE and an ADD to a CHANGE), we need the same Coordinates
   * on both. So this method should be used to 'minify' the GradleVariantIdentification for ADD advices.
   *
   * @return A copy of this Coordinates without the 'default capability'
   */
  fun withoutDefaultCapability(): Coordinates {
    return capabilities.let { capabilities ->
      when {
        capabilities.size == 1 && isDefaultCapability(capabilities.single()) -> {
          // Only one capability that is the default -> remove it
          copy(capabilities = emptySet())
        }
        capabilities.size > 1 && capabilities.any { isDefaultCapability(it) } -> {
          // The default capability is in the list, we assume that the others are not important for selection -> remove them all
          copy(capabilities = emptySet())
        }
        else -> {
          this
        }
      }
    }
  }

  private fun capabilitiesWithoutDefault() =
    capabilities.filter { !isDefaultCapability(it) }.sorted()

  private fun isDefaultCapability(capability: String) =
    when {
      groupKnown() -> capability == ga()
      else -> capability.endsWith(":$artifact") // we don't know the 'group'; only match the 'name' part and assume that the group fits
    }

  companion object {
    /** Convert a raw string into [Coordinates]. */
    fun of(raw: String): Coordinates {
      val segments = raw.split(":")
      return when {
        // project(...) - starts with a ':' - TODO this is assuming buildPath is always ':'
        segments[0].isEmpty() -> Coordinates(segments[1], null, raw, ":", emptySet(), emptyMap(), null, CoordinatesNotation.PROJECT)
        // GAV
        segments.size == 3 -> Coordinates(segments[1], segments[0], null, null, emptySet(), emptyMap(), segments[2], CoordinatesNotation.GA)
        // GA
        segments.size == 2 && segments[0].isNotEmpty() -> Coordinates(segments[1], segments[0], null, null, emptySet(), emptyMap(), null, CoordinatesNotation.GA)
        else -> throw IllegalStateException("FlatCoordinates(raw)")
      }
    }
  }
}
