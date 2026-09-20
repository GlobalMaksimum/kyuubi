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

import org.apache.kyuubi.{KyuubiException, KyuubiFunSuite}
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.config.KyuubiConf._
import org.apache.kyuubi.config.KyuubiReservedKeys.KYUUBI_ENGINE_ID

class JdbcKubernetesModeSubmitterSuite extends KyuubiFunSuite {

  private def confWithImage: KyuubiConf = KyuubiConf()
    .set(KYUUBI_ENGINE_ID, "engine-ref-id")
    .set(ENGINE_DEPLOY_KUBERNETES_MODE_IMAGE.key, "kyuubi/kyuubi-jdbc-engine:latest")
    .set(KUBERNETES_NAMESPACE.key, "kyuubi")

  test("buildEnginePod requires the image to be configured") {
    val conf = KyuubiConf().set(KYUUBI_ENGINE_ID, "engine-ref-id")
    val e = intercept[KyuubiException] {
      JdbcKubernetesModeSubmitter.buildEnginePod(conf)
    }
    assert(e.getMessage.contains(ENGINE_DEPLOY_KUBERNETES_MODE_IMAGE.key))
  }

  test("buildEnginePod requires the engine ref id tag to be configured") {
    val conf = KyuubiConf().set(ENGINE_DEPLOY_KUBERNETES_MODE_IMAGE.key, "image")
    val e = intercept[KyuubiException] {
      JdbcKubernetesModeSubmitter.buildEnginePod(conf)
    }
    assert(e.getMessage.contains(KYUUBI_ENGINE_ID))
  }

  test("buildEnginePod sets the labels KubernetesApplicationOperation looks for") {
    val pod = JdbcKubernetesModeSubmitter.buildEnginePod(confWithImage)
    val labels = pod.getMetadata.getLabels
    assert(labels.get(JdbcKubernetesModeSubmitter.LABEL_KYUUBI_UNIQUE_KEY) === "engine-ref-id")
    assert(labels.get(JdbcKubernetesModeSubmitter.SPARK_APP_ID_LABEL) === "engine-ref-id")
    assert(pod.getMetadata.getNamespace === "kyuubi")
    assert(pod.getMetadata.getName === "kyuubi-jdbc-engine-engine-ref-id")
    assert(pod.getSpec.getRestartPolicy === "Never")
  }

  test("buildEnginePod configures the container image, pull policy and resources") {
    val conf = confWithImage
      .set(ENGINE_DEPLOY_KUBERNETES_MODE_IMAGE_PULL_POLICY.key, "Always")
      .set(ENGINE_DEPLOY_KUBERNETES_MODE_REQUEST_CORES.key, "2")
      .set(ENGINE_DEPLOY_KUBERNETES_MODE_REQUEST_MEMORY.key, "2Gi")
      .set(ENGINE_DEPLOY_KUBERNETES_MODE_LIMIT_CORES.key, "4")
      .set(ENGINE_DEPLOY_KUBERNETES_MODE_LIMIT_MEMORY.key, "4Gi")
      .set(ENGINE_DEPLOY_KUBERNETES_MODE_SERVICE_ACCOUNT.key, "kyuubi-engine")
    val pod = JdbcKubernetesModeSubmitter.buildEnginePod(conf)
    val container = pod.getSpec.getContainers.get(0)
    assert(container.getImage === "kyuubi/kyuubi-jdbc-engine:latest")
    assert(container.getImagePullPolicy === "Always")
    assert(container.getResources.getRequests.get("cpu").getAmount === "2")
    assert(container.getResources.getRequests.get("memory").getAmount === "2")
    assert(container.getResources.getRequests.get("memory").getFormat === "Gi")
    assert(container.getResources.getLimits.get("cpu").getAmount === "4")
    assert(container.getResources.getLimits.get("memory").getAmount === "4")
    assert(container.getResources.getLimits.get("memory").getFormat === "Gi")
    assert(pod.getSpec.getServiceAccountName === "kyuubi-engine")
  }

  test("engineCommand runs the JdbcSQLEngine main class off the configured classpath") {
    val conf = confWithImage
      .set(ENGINE_DEPLOY_KUBERNETES_MODE_CLASSPATH.key, "/opt/kyuubi/jars/*")
      .set(ENGINE_JDBC_MEMORY.key, "2g")
      .set("kyuubi.on", "off")
    val command = JdbcKubernetesModeSubmitter.engineCommand(conf)
    assert(command.head === "java")
    assert(command.contains("-Xmx2g"))
    val cpIdx = command.indexOf("-cp")
    assert(cpIdx >= 0)
    assert(command(cpIdx + 1) === "/opt/kyuubi/jars/*")
    assert(command(cpIdx + 2) === "org.apache.kyuubi.engine.jdbc.JdbcSQLEngine")
    assert(command.sliding(2).exists(pair => pair == Seq("--conf", "kyuubi.on=off")))
  }

  // buildKubernetesClient() is intentionally not unit-tested here: it builds a real HTTP client
  // from Config.autoConfigure, which reads ambient state (a local kubeconfig, optional TLS
  // libraries on the classpath) that a unit test must not depend on - see AGENTS.md's note on
  // KubernetesApplicationOperation, the sibling class this mirrors, requiring tests that "must
  // not assume a real cluster". Exercising it needs a mocked Kubernetes API server instead.
}
