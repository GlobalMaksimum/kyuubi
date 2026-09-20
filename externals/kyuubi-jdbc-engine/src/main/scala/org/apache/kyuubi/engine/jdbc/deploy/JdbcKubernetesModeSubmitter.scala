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
package org.apache.kyuubi.engine.jdbc.deploy

import io.fabric8.kubernetes.api.model.{Pod, PodBuilder, Quantity, ResourceRequirementsBuilder}
import io.fabric8.kubernetes.client.{Config, ConfigBuilder, KubernetesClient, KubernetesClientBuilder}

import org.apache.kyuubi.{KyuubiException, Logging, Utils}
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.config.KyuubiConf._
import org.apache.kyuubi.config.KyuubiReservedKeys.KYUUBI_ENGINE_ID
import org.apache.kyuubi.engine.jdbc.JdbcSQLEngine
import org.apache.kyuubi.util.command.CommandLineUtils.confKeyValues

/**
 * Submits the JDBC engine as a single Kubernetes pod. Unlike YARN mode (see
 * [[org.apache.kyuubi.engine.deploy.yarn.EngineYarnModeSubmitter]]), there is no cluster-side
 * "Application Master" concept to register with: the pod's only container directly runs
 * [[JdbcSQLEngine]] main class, exactly as the LOCAL deploy mode does on the Kyuubi server host.
 * This object is only the local, short-lived process (launched by
 * `org.apache.kyuubi.engine.jdbc.JdbcKubernetesModeProcessBuilder` on the Kyuubi server side)
 * that talks to the Kubernetes API server to create that pod, then exits; it never runs the
 * engine itself.
 */
object JdbcKubernetesModeSubmitter extends Logging {

  /**
   * Must stay in sync with `KubernetesApplicationOperation.LABEL_KYUUBI_UNIQUE_KEY`
   * (kyuubi-server/.../engine/KubernetesApplicationOperation.scala). That operation is what
   * later kills/monitors this pod by tag, but kyuubi-jdbc-engine cannot depend on kyuubi-server
   * (AGENTS.md hard boundary), so the label literal is duplicated here rather than shared.
   */
  private[deploy] val LABEL_KYUUBI_UNIQUE_KEY = "kyuubi-unique-tag"

  /**
   * Must stay in sync with `KubernetesApplicationOperation.SPARK_APP_ID_LABEL`. That class's
   * pod informer only treats a pod as a trackable Kyuubi engine once both labels are present
   * (`isSparkEnginePod`), a Spark-driver-pod assumption baked into shared kill/monitor code.
   * We set it here too, on a non-Spark pod, purely to satisfy that predicate.
   */
  private[deploy] val SPARK_APP_ID_LABEL = "spark-app-selector"

  private[deploy] val CONTAINER_NAME = "kyuubi-jdbc-engine"

  private[deploy] val kyuubiConf: KyuubiConf = new KyuubiConf()

  def main(args: Array[String]): Unit = {
    Utils.fromCommandLineArgs(args, kyuubiConf)
    run()
  }

  private[deploy] def run(): Unit = {
    val client = buildKubernetesClient(kyuubiConf)
    try {
      val pod = buildEnginePod(kyuubiConf)
      info(s"Submitting JDBC engine pod ${pod.getMetadata.getNamespace}/" +
        s"${pod.getMetadata.getName} to Kubernetes")
      client.pods().inNamespace(pod.getMetadata.getNamespace).resource(pod).create()
    } finally {
      client.close()
    }
  }

  private[deploy] def buildKubernetesClient(conf: KyuubiConf): KubernetesClient = {
    val context = conf.get(KUBERNETES_CONTEXT)
    val namespace = conf.get(KUBERNETES_NAMESPACE)
    // Intentionally minimal compared to kyuubi-server's KubernetesUtils.buildKubernetesClient
    // (no explicit oauth token / client cert options): Config.autoConfigure already resolves
    // the in-cluster service account when running inside a pod, or the local kubeconfig
    // otherwise, which covers the common case of the Kyuubi server itself running on
    // Kubernetes. Revisit if users need the fuller credential surface for this submitter too.
    val config = new ConfigBuilder(Config.autoConfigure(context.orNull))
      .withNamespace(namespace)
      .withTrustCerts(conf.get(KUBERNETES_TRUST_CERTIFICATES))
      .build()
    new KubernetesClientBuilder().withConfig(config).build()
  }

  private[deploy] def buildEnginePod(conf: KyuubiConf): Pod = {
    val tag = conf.getOption(KYUUBI_ENGINE_ID)
      .getOrElse(throw new KyuubiException(s"$KYUUBI_ENGINE_ID is not set"))
    val image = conf.get(ENGINE_DEPLOY_KUBERNETES_MODE_IMAGE).getOrElse(
      throw new KyuubiException(s"${ENGINE_DEPLOY_KUBERNETES_MODE_IMAGE.key} must be set " +
        s"when ${ENGINE_JDBC_DEPLOY_MODE.key} is KUBERNETES."))
    val namespace = conf.get(KUBERNETES_NAMESPACE)
    val podName = s"kyuubi-jdbc-engine-$tag"

    val resources = {
      val builder = new ResourceRequirementsBuilder()
        .addToRequests("cpu", new Quantity(conf.get(ENGINE_DEPLOY_KUBERNETES_MODE_REQUEST_CORES)))
        .addToRequests(
          "memory",
          new Quantity(conf.get(ENGINE_DEPLOY_KUBERNETES_MODE_REQUEST_MEMORY)))
      conf.get(ENGINE_DEPLOY_KUBERNETES_MODE_LIMIT_CORES)
        .foreach(v => builder.addToLimits("cpu", new Quantity(v)))
      conf.get(ENGINE_DEPLOY_KUBERNETES_MODE_LIMIT_MEMORY)
        .foreach(v => builder.addToLimits("memory", new Quantity(v)))
      builder.build()
    }

    val podBuilder = new PodBuilder()
      .withNewMetadata()
      .withName(podName)
      .withNamespace(namespace)
      .addToLabels(LABEL_KYUUBI_UNIQUE_KEY, tag)
      .addToLabels(SPARK_APP_ID_LABEL, tag)
      .endMetadata()
      .withNewSpec()
      .withRestartPolicy("Never")
      .addNewContainer()
      .withName(CONTAINER_NAME)
      .withImage(image)
      .withImagePullPolicy(conf.get(ENGINE_DEPLOY_KUBERNETES_MODE_IMAGE_PULL_POLICY))
      .withCommand(engineCommand(conf): _*)
      .withResources(resources)
      .endContainer()
      .endSpec()

    conf.get(ENGINE_DEPLOY_KUBERNETES_MODE_SERVICE_ACCOUNT).foreach { sa =>
      podBuilder.editSpec().withServiceAccountName(sa).endSpec()
    }

    podBuilder.build()
  }

  /**
   * The command run by the pod's only container. It mirrors the LOCAL deploy mode's command
   * (`JdbcProcessBuilder.commands`) rather than YARN mode's: the classpath is whatever the
   * container image bakes in at `kyuubi.engine.kubernetes.classpath`, not a jar localized from
   * the Kyuubi server host, since Kubernetes has no equivalent of YARN's local-resource
   * distribution.
   */
  private[deploy] def engineCommand(conf: KyuubiConf): Seq[String] = {
    val classpath = conf.get(ENGINE_DEPLOY_KUBERNETES_MODE_CLASSPATH)
    val memory = conf.get(ENGINE_JDBC_MEMORY)
    val mainClass = JdbcSQLEngine.getClass.getName.stripSuffix("$")
    val javaOptions = conf.get(ENGINE_JDBC_JAVA_OPTIONS)
      .filter(_.nonEmpty).map(_.split("\\s+").toSeq).getOrElse(Seq.empty)

    Seq("java", s"-Xmx$memory") ++ javaOptions ++
      Seq("-cp", classpath, mainClass) ++
      confKeyValues(conf.getAll)
  }
}
