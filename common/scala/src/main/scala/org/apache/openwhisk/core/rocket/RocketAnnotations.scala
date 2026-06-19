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

import org.apache.openwhisk.core.entity.{ExecutableWhiskAction, WhiskActionMetaData}
import spray.json._
import spray.json.DefaultJsonProtocol._

import scala.util.Try

/**
 * Annotation keys and request fields used by Rocket.
 *
 * Rocket deliberately stores function metadata in action annotations instead of changing the
 * OpenWhisk entity schema. A normal action that does not define these annotations is scheduled
 * by the stock OpenWhisk path.
 */
object RocketAnnotations {
  val Enabled = "rocket.enabled"
  val Library = "rocket.library"
  val BaseModel = "rocket.baseModel"
  val FrontModel = "rocket.frontModel"
  val BackModel = "rocket.backModel"
  val Group = "rocket.group"
  val ColdStartMs = "rocket.coldStartMs"
  val LibraryImportMs = "rocket.libraryImportMs"
  val FrontLoadMs = "rocket.frontLoadMs"
  val BackLoadMs = "rocket.backLoadMs"
  val LibraryMemoryMb = "rocket.libraryMemoryMb"
  val FrontMemoryMb = "rocket.frontMemoryMb"
  val BackMemoryMb = "rocket.backMemoryMb"

  val RequestState = "rocket_state"
  val RequestPhase = "rocket_phase"
  val RequestTarget = "rocket_target"
  val RequestLibrary = "rocket_library"
  val RequestBaseModel = "rocket_base_model"
  val RequestFrontModel = "rocket_front_model"
  val RequestBackModel = "rocket_back_model"

  val PhasePreload = "preload"
  val PhaseInvoke = "invoke"

  def isRocket(meta: WhiskActionMetaData): Boolean =
    meta.annotations.isTruthy(Enabled)

  def isRocket(action: ExecutableWhiskAction): Boolean =
    action.annotations.isTruthy(Enabled)

  def spec(meta: WhiskActionMetaData): Option[RocketFunctionSpec] =
    if (isRocket(meta)) {
      Some(
        RocketFunctionSpec(
          id = meta.fullyQualifiedName(false).asString,
          library = string(meta, Library, meta.exec.kind),
          baseModel = string(meta, BaseModel, meta.name.asString),
          frontModel = string(meta, FrontModel, string(meta, BaseModel, meta.name.asString)),
          backModel = string(meta, BackModel, meta.name.asString),
          groupHint = meta.annotations.getAs[String](Group).toOption,
          profile = RocketProfile(
            coldStartMs = int(meta, ColdStartMs, 2000),
            libraryImportMs = int(meta, LibraryImportMs, 800),
            frontLoadMs = int(meta, FrontLoadMs, 600),
            backLoadMs = int(meta, BackLoadMs, 900),
            libraryMemoryMb = int(meta, LibraryMemoryMb, 300),
            frontMemoryMb = int(meta, FrontMemoryMb, 300),
            backMemoryMb = int(meta, BackMemoryMb, meta.limits.memory.megabytes.toInt))))
    } else {
      None
    }

  def spec(action: ExecutableWhiskAction): Option[RocketFunctionSpec] =
    if (isRocket(action)) {
      Some(
        RocketFunctionSpec(
          id = action.fullyQualifiedName(false).asString,
          library = string(action, Library, action.exec.kind),
          baseModel = string(action, BaseModel, action.name.asString),
          frontModel = string(action, FrontModel, string(action, BaseModel, action.name.asString)),
          backModel = string(action, BackModel, action.name.asString),
          groupHint = action.annotations.getAs[String](Group).toOption,
          profile = RocketProfile(
            coldStartMs = int(action, ColdStartMs, 2000),
            libraryImportMs = int(action, LibraryImportMs, 800),
            frontLoadMs = int(action, FrontLoadMs, 600),
            backLoadMs = int(action, BackLoadMs, 900),
            libraryMemoryMb = int(action, LibraryMemoryMb, 300),
            frontMemoryMb = int(action, FrontMemoryMb, 300),
            backMemoryMb = int(action, BackMemoryMb, action.limits.memory.megabytes.toInt))))
    } else {
      None
    }

  def preloadRequest(action: ExecutableWhiskAction, target: RocketContainerState): JsObject = {
    val fields = metadataFields(action, target) + (RequestPhase -> JsString(PhasePreload))
    val requestFields = fields + ("value" -> JsObject(fields))
    JsObject(requestFields)
  }

  def requestMetadata(fields: Map[String, JsValue]): Map[String, JsValue] = {
    val rocketKeys =
      Set(RequestState, RequestPhase, RequestTarget, RequestLibrary, RequestBaseModel, RequestFrontModel, RequestBackModel)
    fields.filter { case (key, _) => rocketKeys.contains(key) }
  }

  def invokeEnvironment(action: ExecutableWhiskAction, target: RocketContainerState): Map[String, JsValue] =
    metadataFields(action, target) + (RequestPhase -> JsString(PhaseInvoke))

  private def metadataFields(action: ExecutableWhiskAction, target: RocketContainerState): Map[String, JsValue] = {
    val s = spec(action)
    Map(
      RequestState -> JsString(target.name),
      RequestTarget -> JsString(action.fullyQualifiedName(false).asString),
      RequestLibrary -> JsString(s.map(_.library).getOrElse(action.exec.kind)),
      RequestBaseModel -> JsString(s.map(_.baseModel).getOrElse(action.name.asString)),
      RequestFrontModel -> JsString(s.map(_.frontModel).getOrElse(action.name.asString)),
      RequestBackModel -> JsString(s.map(_.backModel).getOrElse(action.name.asString)))
  }

  private def string(meta: WhiskActionMetaData, key: String, default: String): String =
    meta.annotations.getAs[String](key).getOrElse(default)

  private def int(meta: WhiskActionMetaData, key: String, default: Int): Int =
    meta.annotations
      .getAs[Int](key)
      .orElse(meta.annotations.getAs[String](key).flatMap(s => Try(s.toInt)))
      .getOrElse(default)

  private def string(action: ExecutableWhiskAction, key: String, default: String): String =
    action.annotations.getAs[String](key).getOrElse(default)

  private def int(action: ExecutableWhiskAction, key: String, default: Int): Int =
    action.annotations
      .getAs[Int](key)
      .orElse(action.annotations.getAs[String](key).flatMap(s => Try(s.toInt)))
      .getOrElse(default)
}
