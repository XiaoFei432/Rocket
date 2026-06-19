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

import scala.util.Random

case class RocketBanditConfig(classes: Int, memoryWeight: Double, epsilon: Double, learningRate: Double)

/**
 * A lightweight contextual-bandit pre-loader.
 *
 * The paper uses VW for the cost predictor. To keep this project self-contained in the
 * OpenWhisk tree, this implementation uses online linear models with epsilon-greedy
 * neighbor exploration. The action space is the paper's fixed K-class state vector.
 */
class RocketBandit(config: RocketBanditConfig, random: Random = new Random()) {
  private val actions: Vector[Vector[RocketContainerState]] =
    enumerateActions(config.classes).toVector

  private var weights = Map.empty[Int, Vector[Double]]
  private var last: Option[(Vector[Double], Int)] = None

  def decide(groupId: String,
             specs: Vector[RocketFunctionSpec],
             predictions: Map[String, RocketPrediction]): RocketActionDecision = {
    val context = buildContext(specs, predictions)
    val costs = actions.indices.map(i => i -> estimate(i, context)).toVector
    val best = costs.minBy(_._2)._1
    val chosen =
      if (random.nextDouble() < config.epsilon) chooseNeighbor(best)
      else best
    last = Some(context -> chosen)
    val plans = plansFor(specs, predictions, actions(chosen))
    RocketActionDecision(groupId, chosen, plans, costs.find(_._1 == chosen).map(_._2).getOrElse(0.0))
  }

  def observe(cost: Double): Unit =
    last.foreach {
      case (context, action) =>
        val current = weights.getOrElse(action, Vector.fill(context.size)(0.0))
        val prediction = dot(current, context)
        val error = prediction - cost
        val updated = current.zip(context).map { case (w, x) => w - config.learningRate * error * x }
        weights = weights + (action -> updated)
    }

  def cost(spec: RocketFunctionSpec, prediction: RocketPrediction, state: RocketContainerState): Double =
    prediction.concurrency * spec.profile.startupCostFrom(state) +
      config.memoryWeight * spec.profile.memoryCostOf(state)

  private def buildContext(specs: Vector[RocketFunctionSpec],
                           predictions: Map[String, RocketPrediction]): Vector[Double] = {
    val perFunction = specs.sortBy(_.id).flatMap { spec =>
      val pred = predictions.getOrElse(spec.id, RocketPrediction(spec.id, 0, 0.0, Vector.empty))
      Vector(
        pred.concurrency.toDouble,
        pred.meanRps,
        spec.profile.coldStartMs.toDouble,
        spec.profile.libraryImportMs.toDouble,
        spec.profile.frontLoadMs.toDouble,
        spec.profile.backLoadMs.toDouble,
        spec.profile.libraryMemoryMb.toDouble,
        spec.profile.frontMemoryMb.toDouble,
        spec.profile.backMemoryMb.toDouble)
    }
    if (perFunction.isEmpty) Vector(1.0) else 1.0 +: normalize(perFunction)
  }

  private def estimate(action: Int, context: Vector[Double]): Double =
    dot(weights.getOrElse(action, Vector.fill(context.size)(0.0)), context)

  private def plansFor(specs: Vector[RocketFunctionSpec],
                       predictions: Map[String, RocketPrediction],
                       action: Vector[RocketContainerState]): Vector[RocketContainerPlan] =
    specs.sortBy(_.id).flatMap { spec =>
      val count = predictions.get(spec.id).map(_.concurrency).getOrElse(0)
      if (count <= 0) {
        Vector.empty
      } else {
        val buckets = bucketize(count, action.size)
        buckets.zip(action).collect {
          case (n, state) if n > 0 => RocketContainerPlan(spec.id, state, n)
        }
      }
    }

  private def chooseNeighbor(best: Int): Int = {
    val base = actions(best)
    val neighbors = actions.zipWithIndex.filter {
      case (candidate, _) =>
        candidate.zip(base).map { case (a, b) => Math.abs(a.level - b.level) }.sum == 1
    }
    if (neighbors.nonEmpty) neighbors(random.nextInt(neighbors.size))._2 else best
  }

  private def bucketize(count: Int, classes: Int): Vector[Int] = {
    val base = count / classes
    val extra = count % classes
    (0 until classes).map(i => base + (if (i < extra) 1 else 0)).toVector
  }

  private def normalize(values: Vector[Double]): Vector[Double] =
    values.map(v => Math.log1p(Math.max(v, 0.0)))

  private def dot(a: Vector[Double], b: Vector[Double]): Double =
    a.zip(b).map { case (x, y) => x * y }.sum

  private def enumerateActions(classes: Int): IndexedSeq[Vector[RocketContainerState]] = {
    def loop(depth: Int): IndexedSeq[Vector[RocketContainerState]] =
      if (depth == 0) IndexedSeq(Vector.empty)
      else {
        for {
          tail <- loop(depth - 1)
          state <- RocketContainerState.all
        } yield state +: tail
      }
    loop(classes)
  }
}

