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

import scala.collection.immutable.Queue
import scala.concurrent.duration._
import scala.util.Random

case class RocketPredictorConfig(largeWindow: FiniteDuration,
                                 smallWindow: FiniteDuration,
                                 timestep: FiniteDuration,
                                 maxRecordsPerFunction: Int,
                                 conservativeQuantile: Double)

/**
 * Dual-scale invocation predictor.
 *
 * The implementation follows Rocket's paper-level design while remaining online-only:
 * a large timescale predictor estimates per-function demand from historical windows,
 * and a small timescale Weibull sampler refines group-level burst concurrency.
 */
class RocketPredictor(config: RocketPredictorConfig, random: Random = new Random()) {
  private var records = Map.empty[String, Queue[Long]]

  def observe(functionId: String, timestampMillis: Long): Unit = {
    val updated = records.getOrElse(functionId, Queue.empty).enqueue(timestampMillis)
    records = records + (functionId -> updated.drop(Math.max(updated.size - config.maxRecordsPerFunction, 0)))
  }

  def predict(specs: Iterable[RocketFunctionSpec], group: Set[String], nowMillis: Long): Map[String, RocketPrediction] = {
    val functions = specs.map(_.id).toSet.intersect(group)
    if (functions.isEmpty) {
      Map.empty
    } else {
      val large = functions.map(id => id -> largeTimescaleConcurrency(id, nowMillis)).toMap
      val groupIats = interArrivalTimes(functions.flatMap(id => records.getOrElse(id, Queue.empty)).toVector.sorted)
        .filter(_ > 0.0)
      val simulated = simulateSmallTimescale(functions.toVector, large, groupIats)

      functions.map { id =>
        val iats = recentIats(id, nowMillis)
        val transientRps =
          if (iats.nonEmpty) iats.map(i => 1000.0 / Math.max(i, 1.0)).sum / iats.size
          else large(id).toDouble / Math.max(config.largeWindow.toSeconds, 1L)
        val concurrency = Math.max(large(id), simulated.getOrElse(id, 0))
        id -> RocketPrediction(id, Math.max(concurrency, 0), transientRps, iats)
      }.toMap
    }
  }

  def recentCounts(functionId: String, windows: Int, nowMillis: Long, window: FiniteDuration): Vector[Int] = {
    val items = records.getOrElse(functionId, Queue.empty)
    val w = window.toMillis
    (0 until windows).map { idx =>
      val end = nowMillis - idx * w
      val start = end - w
      items.count(ts => ts > start && ts <= end)
    }.reverse.toVector
  }

  private def largeTimescaleConcurrency(functionId: String, nowMillis: Long): Int = {
    val counts = recentCounts(functionId, 8, nowMillis, config.largeWindow)
    if (counts.isEmpty) {
      0
    } else {
      val sorted = counts.sorted
      val index = Math.min(sorted.size - 1, Math.max(0, Math.ceil(sorted.size * config.conservativeQuantile).toInt - 1))
      sorted(index)
    }
  }

  private def recentIats(functionId: String, nowMillis: Long): Vector[Double] = {
    val lower = nowMillis - config.smallWindow.toMillis
    val seq = records.getOrElse(functionId, Queue.empty).filter(_ >= lower).toVector.sorted
    interArrivalTimes(seq)
  }

  private def interArrivalTimes(seq: Vector[Long]): Vector[Double] =
    seq.sliding(2).collect {
      case Vector(a, b) if b > a => (b - a).toDouble
    }.toVector

  private def simulateSmallTimescale(functions: Vector[String],
                                     large: Map[String, Int],
                                     iats: Vector[Double]): Map[String, Int] = {
    if (functions.isEmpty || iats.isEmpty) {
      Map.empty
    } else {
      val (shape, scale) = Weibull.fit(iats)
      val totalLarge = Math.max(large.values.sum, 1)
      val probabilities = functions.map(id => id -> (large.getOrElse(id, 0).toDouble / totalLarge))
      val counts = scala.collection.mutable.Map.empty[String, Int].withDefaultValue(0)
      var elapsed = 0.0
      while (elapsed < config.timestep.toMillis) {
        elapsed += Weibull.sample(shape, scale, random)
        if (elapsed < config.timestep.toMillis) {
          val id = choose(probabilities)
          counts(id) = counts(id) + 1
        }
      }
      counts.toMap
    }
  }

  private def choose(probabilities: Vector[(String, Double)]): String = {
    val r = random.nextDouble()
    var acc = 0.0
    probabilities
      .find {
        case (_, p) =>
          acc += p
          r <= acc
      }
      .map(_._1)
      .getOrElse(probabilities.last._1)
  }
}

object Weibull {
  def fit(samples: Vector[Double]): (Double, Double) = {
    val clean = samples.filter(_ > 0.0)
    if (clean.size < 2) {
      (1.0, clean.headOption.getOrElse(1000.0))
    } else {
      val mean = clean.sum / clean.size
      val variance = clean.map(x => Math.pow(x - mean, 2)).sum / clean.size
      val cv = Math.sqrt(variance) / Math.max(mean, 1.0)
      val shape = Math.max(0.2, Math.min(10.0, Math.pow(Math.max(cv, 0.01), -1.086)))
      val scale = mean / gamma(1.0 + 1.0 / shape)
      (shape, Math.max(scale, 1.0))
    }
  }

  def sample(shape: Double, scale: Double, random: Random): Double = {
    val u = Math.max(1e-12, Math.min(1.0 - 1e-12, random.nextDouble()))
    scale * Math.pow(-Math.log(1.0 - u), 1.0 / shape)
  }

  // Lanczos approximation for positive values.
  private def gamma(z: Double): Double = {
    val p = Array(
      676.5203681218851,
      -1259.1392167224028,
      771.32342877765313,
      -176.61502916214059,
      12.507343278686905,
      -0.13857109526572012,
      9.9843695780195716e-6,
      1.5056327351493116e-7)
    if (z < 0.5) {
      Math.PI / (Math.sin(Math.PI * z) * gamma(1.0 - z))
    } else {
      var x = 0.99999999999980993
      val zz = z - 1.0
      var i = 0
      while (i < p.length) {
        x += p(i) / (zz + i + 1.0)
        i += 1
      }
      val t = zz + p.length - 0.5
      Math.sqrt(2.0 * Math.PI) * Math.pow(t, zz + 0.5) * Math.exp(-t) * x
    }
  }
}

