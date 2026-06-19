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

import scala.concurrent.duration._
import scala.util.Random

case class RocketConfig(enabled: Boolean = false,
                        groupingInterval: FiniteDuration = 1.minute,
                        largeWindow: FiniteDuration = 1.minute,
                        smallWindow: FiniteDuration = 10.seconds,
                        timestep: FiniteDuration = 10.seconds,
                        maxRecordsPerFunction: Int = 4096,
                        conservativeQuantile: Double = 0.8,
                        sharingAlpha: Double = 0.6,
                        minDegreeOfSharing: Double = 0.45,
                        classes: Int = 5,
                        memoryWeight: Double = 0.0024,
                        epsilon: Double = 0.1,
                        learningRate: Double = 0.01)

class RocketCoordinator(config: RocketConfig, random: Random = new Random()) {
  private val predictor = new RocketPredictor(
    RocketPredictorConfig(
      config.largeWindow,
      config.smallWindow,
      config.timestep,
      config.maxRecordsPerFunction,
      config.conservativeQuantile),
    random)
  private val bandits = scala.collection.mutable.Map.empty[String, RocketBandit]
  private var specs = Map.empty[String, RocketFunctionSpec]
  private var groups = Vector.empty[Set[String]]
  private var lastGroupingMillis = 0L
  private var containerAffinity = Map.empty[Int, Set[String]]
  private var lastDecision = Map.empty[String, RocketActionDecision]

  def register(spec: RocketFunctionSpec): Unit = {
    specs = specs + (spec.id -> spec)
  }

  def observeInvocation(functionId: String, timestampMillis: Long): Unit =
    if (config.enabled) {
      predictor.observe(functionId, timestampMillis)
    }

  def observeContainer(invoker: Int, functionId: String): Unit = {
    val current = containerAffinity.getOrElse(invoker, Set.empty)
    containerAffinity = containerAffinity + (invoker -> (current + functionId))
  }

  def forgetContainer(invoker: Int, functionId: String): Unit = {
    val current = containerAffinity.getOrElse(invoker, Set.empty)
    containerAffinity = containerAffinity + (invoker -> (current - functionId))
  }

  def functionGroup(functionId: String, nowMillis: Long): Set[String] = {
    regroupIfNeeded(nowMillis)
    groups.find(_.contains(functionId)).getOrElse(Set(functionId))
  }

  def decision(functionId: String, nowMillis: Long): Option[RocketActionDecision] = {
    if (!config.enabled || !specs.contains(functionId)) {
      None
    } else {
      regroupIfNeeded(nowMillis)
      val group = functionGroup(functionId, nowMillis)
      val groupId = group.toVector.sorted.mkString("|")
      lastDecision.get(groupId).orElse {
        val groupSpecs = group.flatMap(specs.get).toVector
        val predictions = predictor.predict(groupSpecs, group, nowMillis)
        val bandit = bandits.getOrElseUpdate(
          groupId,
          new RocketBandit(
            RocketBanditConfig(config.classes, config.memoryWeight, config.epsilon, config.learningRate),
            random))
        val d = bandit.decide(groupId, groupSpecs, predictions)
        lastDecision = lastDecision + (groupId -> d)
        Some(d)
      }
    }
  }

  def targetState(functionId: String, nowMillis: Long): RocketContainerState =
    decision(functionId, nowMillis)
      .flatMap(_.plans.find(p => p.functionId == functionId && p.count > 0).map(_.state))
      .getOrElse(RocketWarming)

  def chooseInvoker(functionId: String,
                    invokers: Vector[Int],
                    hasCapacity: Int => Boolean,
                    nowMillis: Long): Option[RocketScheduleChoice] = {
    val state = targetState(functionId, nowMillis)
    val group = functionGroup(functionId, nowMillis)
    RocketGreedyScheduler.choose(functionId, state, group, invokers, affinity, hasCapacity)
  }

  def observeCost(groupId: String, cost: Double): Unit =
    bandits.get(groupId).foreach(_.observe(cost))

  def currentGroups(nowMillis: Long): Vector[Set[String]] = {
    regroupIfNeeded(nowMillis)
    groups
  }

  private def affinity(group: Set[String], invoker: Int): Int =
    containerAffinity.getOrElse(invoker, Set.empty).intersect(group).size

  private def regroupIfNeeded(nowMillis: Long): Unit = {
    if (groups.isEmpty || nowMillis - lastGroupingMillis >= config.groupingInterval.toMillis) {
      groups = RocketFunctionGrouper.group(
        specs.values,
        id => predictor.recentCounts(id, windows = 8, nowMillis, config.largeWindow),
        RocketGroupingConfig(config.sharingAlpha, config.minDegreeOfSharing))
      lastDecision = Map.empty
      lastGroupingMillis = nowMillis
    }
  }
}

object RocketCoordinator {
  val disabled = new RocketCoordinator(RocketConfig(enabled = false))
}

