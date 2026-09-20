/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kyuubi.engine.jdbc

import java.io.File
import java.nio.file.Paths

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

import org.apache.kyuubi.{Logging, SCALA_COMPILE_VERSION, Utils}
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.config.KyuubiConf.{ENGINE_JDBC_EXTRA_CLASSPATH, ENGINE_JDBC_MEMORY, KUBERNETES_CONTEXT, KUBERNETES_NAMESPACE}
import org.apache.kyuubi.config.KyuubiReservedKeys.{KYUUBI_ENGINE_ID, KYUUBI_SESSION_USER_KEY}
import org.apache.kyuubi.engine.{ApplicationManagerInfo, EngineType, KyuubiApplicationManager}
import org.apache.kyuubi.operation.log.OperationLog
import org.apache.kyuubi.util.command.CommandLineUtils.{confKeyValue, confKeyValues, genClasspathOption}

/**
 * A process builder for JDBC on Kubernetes.
 *
 * It launches a short-lived local process on the Kyuubi server side
 * (`org.apache.kyuubi.engine.jdbc.deploy.JdbcKubernetesModeSubmitter`) whose only job is to
 * submit the JDBC engine as a single Kubernetes pod, then exit. Unlike YARN mode there is no
 * local-resource distribution: the pod's container runs off a pre-built image, so the classpath
 * used inside the pod (`kyuubi.engine.kubernetes.classpath`) is unrelated to the classpath built
 * here, which only needs to be enough to run the submitter itself on the Kyuubi server host.
 */
class JdbcKubernetesModeProcessBuilder(
    override val proxyUser: String,
    override val doAsEnabled: Boolean,
    override val conf: KyuubiConf,
    override val engineRefId: String,
    override val extraEngineLog: Option[OperationLog] = None)
  extends JdbcProcessBuilder(proxyUser, doAsEnabled, conf, engineRefId, extraEngineLog)
  with Logging {

  override protected def mainClass: String =
    "org.apache.kyuubi.engine.jdbc.deploy.JdbcKubernetesModeSubmitter"

  override def isClusterMode(): Boolean = true

  override def clusterManager(): Option[String] = Some("k8s")

  override def appMgrInfo(): ApplicationManagerInfo =
    ApplicationManagerInfo(
      clusterManager(),
      conf.get(KUBERNETES_CONTEXT),
      Some(conf.get(KUBERNETES_NAMESPACE)))

  override protected val commands: Iterable[String] = {
    // No-op today: the submitter tags the pod directly from KYUUBI_ENGINE_ID below rather than
    // through KyuubiApplicationManager's per-(engine, resourceManager) dispatch table (which has
    // no ("JDBC", K8s*) case), since it builds the pod spec itself instead of going through a
    // third-party submit tool the way Spark's spark-submit does. Kept as a call for consistency
    // with the other deploy-mode process builders and as a hook if that changes.
    KyuubiApplicationManager.tagApplication(engineRefId, shortName, clusterManager(), conf)

    val buffer = new ArrayBuffer[String]()
    buffer += executable

    val memory = conf.get(ENGINE_JDBC_MEMORY)
    buffer += s"-Xmx$memory"

    val classpathEntries = new mutable.LinkedHashSet[String]
    mainResource.foreach(classpathEntries.add)
    mainResource.foreach { path =>
      val parent = Paths.get(path).getParent
      if (Utils.isTesting) {
        // add dev classpath
        val jdbcDeps = parent
          .resolve(s"scala-$SCALA_COMPILE_VERSION")
          .resolve("jars")
        classpathEntries.add(s"$jdbcDeps${File.separator}*")
      } else {
        // add prod classpath
        classpathEntries.add(s"$parent${File.separator}*")
      }
    }
    conf.get(ENGINE_JDBC_EXTRA_CLASSPATH).foreach(classpathEntries.add)
    buffer ++= genClasspathOption(classpathEntries)

    buffer += mainClass

    buffer ++= confKeyValue(KYUUBI_SESSION_USER_KEY, proxyUser)
    buffer ++= confKeyValue(KYUUBI_ENGINE_ID, engineRefId)

    buffer ++= confKeyValues(conf.getEngineConf(EngineType.JDBC))

    buffer
  }
}
