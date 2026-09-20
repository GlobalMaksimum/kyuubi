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

import org.apache.kyuubi.KyuubiFunSuite
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.config.KyuubiConf.{ENGINE_JDBC_CONNECTION_PASSWORD, ENGINE_JDBC_CONNECTION_URL, ENGINE_JDBC_DEPLOY_MODE, KUBERNETES_CONTEXT, KUBERNETES_NAMESPACE}
import org.apache.kyuubi.engine.ApplicationManagerInfo
import org.apache.kyuubi.engine.deploy.DeployMode

class JdbcKubernetesModeProcessBuilderSuite extends KyuubiFunSuite {

  private def newConf: KyuubiConf = KyuubiConf().set("kyuubi.on", "off")
    .set(ENGINE_JDBC_CONNECTION_URL.key, "")
    .set(ENGINE_JDBC_CONNECTION_PASSWORD.key, "123456")

  test("jdbc kubernetes mode process builder") {
    val conf = newConf
    val builder = new JdbcKubernetesModeProcessBuilder("kyuubi", true, conf, "engine-ref-id")
    val commands = builder.toString.split('\n')
    assert(commands.head.contains("bin/java"), "wrong exec")
    assert(builder.toString.contains(
      "org.apache.kyuubi.engine.jdbc.deploy.JdbcKubernetesModeSubmitter"))
    assert(commands.exists(ss => ss.contains("kyuubi-jdbc-engine")), "wrong classpath")
    assert(builder.toString.contains("--conf kyuubi.session.user=kyuubi"))
    assert(builder.toString.contains("--conf kyuubi.engine.id=engine-ref-id"))
    assert(builder.toString.contains("--conf kyuubi.on=off"))
  }

  test("cluster manager and app manager info") {
    val conf = newConf
      .set(KUBERNETES_CONTEXT.key, "minikube")
      .set(KUBERNETES_NAMESPACE.key, "kyuubi")
    val builder = new JdbcKubernetesModeProcessBuilder("kyuubi", true, conf, "engine-ref-id")
    assert(builder.isClusterMode())
    assert(builder.clusterManager() === Some("k8s"))
    assert(builder.appMgrInfo() === ApplicationManagerInfo(
      Some("k8s"),
      org.apache.kyuubi.engine.KubernetesInfo(Some("minikube"), Some("kyuubi"))))
  }

  test("apply() routes KUBERNETES deploy mode to JdbcKubernetesModeProcessBuilder") {
    val conf = newConf.set(ENGINE_JDBC_DEPLOY_MODE.key, DeployMode.KUBERNETES.toString)
    val builder = JdbcProcessBuilder(
      "kyuubi",
      true,
      conf,
      "engine-ref-id",
      None,
      "kyuubi-engine")
    assert(builder.isInstanceOf[JdbcKubernetesModeProcessBuilder])
  }
}
