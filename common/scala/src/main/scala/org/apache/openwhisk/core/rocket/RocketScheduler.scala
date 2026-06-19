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

case class RocketGroupingConfig(alpha: Double, minDegreeOfSharing: Double)

object RocketFunctionGrouper {
  def group(specs: Iterable[RocketFunctionSpec],
            recentCounts: String => Vector[Int],
            config: RocketGroupingConfig): Vector[Set[String]] = {
    val byHint = specs.toVector.groupBy(_.groupHint)
    val hinted = byHint.collect { case (Some(_), values) => values.map(_.id).toSet }.toVector
    val auto = byHint.getOrElse(None, Vector.empty)
    hinted ++ cluster(auto, recentCounts, config)
  }

  def degreeOfSharing(a: RocketFunctionSpec,
                      b: RocketFunctionSpec,
                      countsA: Vector[Int],
                      countsB: Vector[Int],
                      alpha: Double): Double = {
    val similarity = a.artifactSimilarity(b)
    val complementarity = (1.0 - pearson(countsA, countsB)) / 2.0
    alpha * similarity + (1.0 - alpha) * complementarity
  }

  private def cluster(specs: Vector[RocketFunctionSpec],
                      recentCounts: String => Vector[Int],
                      config: RocketGroupingConfig): Vector[Set[String]] = {
    var groups = specs.map(s => Set(s.id))
    val byId = specs.map(s => s.id -> s).toMap
    var merged = true
    while (merged && groups.size > 1) {
      val candidates = for {
        i <- groups.indices
        j <- (i + 1) until groups.size
      } yield (i, j, groupDegree(groups(i), groups(j), byId, recentCounts, config.alpha))
      val best = candidates.sortBy(-_._3).headOption
      best match {
        case Some((i, j, dos)) if dos >= config.minDegreeOfSharing =>
          val g = groups(i) ++ groups(j)
          groups = groups.zipWithIndex.collect { case (value, idx) if idx != i && idx != j => value } :+ g
        case _ =>
          merged = false
      }
    }
    groups
  }

  private def groupDegree(a: Set[String],
                          b: Set[String],
                          specs: Map[String, RocketFunctionSpec],
                          recentCounts: String => Vector[Int],
                          alpha: Double): Double = {
    val pairs = for {
      left <- a.toVector
      right <- b.toVector
    } yield degreeOfSharing(specs(left), specs(right), recentCounts(left), recentCounts(right), alpha)
    if (pairs.isEmpty) 0.0 else pairs.sum / pairs.size
  }

  private def pearson(a: Vector[Int], b: Vector[Int]): Double = {
    val size = Math.min(a.size, b.size)
    if (size <= 1) {
      0.0
    } else {
      val x = a.takeRight(size).map(_.toDouble)
      val y = b.takeRight(size).map(_.toDouble)
      val mx = x.sum / size
      val my = y.sum / size
      val numerator = x.zip(y).map { case (xi, yi) => (xi - mx) * (yi - my) }.sum
      val dx = Math.sqrt(x.map(xi => Math.pow(xi - mx, 2)).sum)
      val dy = Math.sqrt(y.map(yi => Math.pow(yi - my, 2)).sum)
      if (dx == 0.0 || dy == 0.0) 0.0 else Math.max(-1.0, Math.min(1.0, numerator / (dx * dy)))
    }
  }
}

object RocketGreedyScheduler {
  def choose(functionId: String,
             state: RocketContainerState,
             group: Set[String],
             invokers: Vector[Int],
             affinity: (Set[String], Int) => Int,
             hasCapacity: Int => Boolean): Option[RocketScheduleChoice] =
    invokers
      .filter(hasCapacity)
      .sortBy(invoker => (-affinity(group, invoker), invoker))
      .headOption
      .map(invoker => RocketScheduleChoice(functionId, invoker, affinity(group, invoker), state))
}

