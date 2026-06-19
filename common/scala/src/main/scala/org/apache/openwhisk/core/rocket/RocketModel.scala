/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.openwhisk.core.rocket

sealed abstract class RocketContainerState(val level: Int, val name: String)
case object RocketWarming extends RocketContainerState(1, "warming")
case object RocketImported extends RocketContainerState(2, "imported")
case object RocketPartialLoaded extends RocketContainerState(3, "p-loaded")
case object RocketFullLoaded extends RocketContainerState(4, "f-loaded")

object RocketContainerState {
  val all: Vector[RocketContainerState] =
    Vector(RocketWarming, RocketImported, RocketPartialLoaded, RocketFullLoaded)

  def fromName(name: String): RocketContainerState =
    all.find(_.name.equalsIgnoreCase(name)).getOrElse(RocketWarming)
}

case class RocketProfile(coldStartMs: Int,
                         libraryImportMs: Int,
                         frontLoadMs: Int,
                         backLoadMs: Int,
                         libraryMemoryMb: Int,
                         frontMemoryMb: Int,
                         backMemoryMb: Int) {
  def startupCostFrom(state: RocketContainerState): Double = state match {
    case RocketWarming       => libraryImportMs + frontLoadMs + backLoadMs
    case RocketImported      => frontLoadMs + backLoadMs
    case RocketPartialLoaded => backLoadMs
    case RocketFullLoaded    => 0.0
  }

  def memoryCostOf(state: RocketContainerState): Double = state match {
    case RocketWarming       => 0.0
    case RocketImported      => libraryMemoryMb.toDouble
    case RocketPartialLoaded => libraryMemoryMb.toDouble + frontMemoryMb
    case RocketFullLoaded    => libraryMemoryMb.toDouble + frontMemoryMb + backMemoryMb
  }
}

case class RocketFunctionSpec(id: String,
                              library: String,
                              baseModel: String,
                              frontModel: String,
                              backModel: String,
                              profile: RocketProfile,
                              groupHint: Option[String] = None) {
  def artifactSimilarity(other: RocketFunctionSpec): Double =
    if (library != other.library) {
      0.0
    } else if (baseModel != other.baseModel) {
      0.5
    } else {
      0.75
    }
}

case class RocketInvocationRecord(functionId: String, timestampMillis: Long)

case class RocketPrediction(functionId: String, concurrency: Int, meanRps: Double, sampledIatsMillis: Vector[Double])

case class RocketContainerPlan(functionId: String, state: RocketContainerState, count: Int) {
  require(count >= 0, "count must be non-negative")
}

case class RocketActionDecision(groupId: String,
                                actionIndex: Int,
                                plans: Vector[RocketContainerPlan],
                                estimatedCost: Double)

case class RocketScheduleChoice(functionId: String, invokerId: Int, affinity: Int, state: RocketContainerState)

