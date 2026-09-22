#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

# Image for the JDBC engine's KUBERNETES deploy mode (kyuubi.engine.jdbc.deploy.mode=KUBERNETES).
# JdbcKubernetesModeSubmitter (externals/kyuubi-jdbc-engine) sets the pod's container command
# directly, so this image needs no Kyuubi server, ENTRYPOINT script or web-ui - only a JRE and
# the engine's own jars at kyuubi.engine.kubernetes.classpath (default:
# /opt/kyuubi/externals/engines/jdbc/*).
#
# Usage:
#   1. Build a binary distribution: ./build/dist (or download an official release tarball)
#   2. Drop any extra JDBC driver jars you need (Oracle ojdbc8.jar, a newer mysql-connector-j,
#      etc. - none of these are bundled, see LICENSE-binary for why) into a local directory,
#      e.g. ./jdbc-drivers/*.jar. The directory must exist, even if empty:
#        mkdir -p jdbc-drivers
#   3. Build the image from the dist output, with the drivers directory alongside it:
#        cd dist   # the ./build/dist output directory
#        cp -r /path/to/jdbc-drivers .
#        docker build -f <kyuubi-source>/docker/jdbc-engine/Dockerfile \
#          -t <private-repo>/kyuubi-jdbc-engine:<tag> .

ARG BASE_IMAGE=eclipse-temurin:8-jre-focal
FROM ${BASE_IMAGE}

ARG kyuubi_uid=10009
USER root

ENV KYUUBI_HOME=/opt/kyuubi
ENV ENGINE_HOME=${KYUUBI_HOME}/externals/engines/jdbc

RUN useradd -u ${kyuubi_uid} -g root kyuubi -d /home/kyuubi -m && \
    mkdir -p ${ENGINE_HOME}

COPY LICENSE NOTICE ${KYUUBI_HOME}/
# externals/engines/jdbc/*.jar includes symlinks back to ../../../jars/<jar> (see build/dist,
# "Share the jars w/ server to reduce binary size"); jars/ is copied too so those symlinks
# resolve inside the image exactly as they do in an untarred binary distribution.
COPY jars ${KYUUBI_HOME}/jars
COPY externals/engines/jdbc ${ENGINE_HOME}
# Extra JDBC driver jars staged by the caller (step 2 above). A directory copy (rather than a
# jdbc-drivers/*.jar glob) is required here: Docker fails the build on a glob that matches zero
# files, and the directory is allowed to be empty.
COPY jdbc-drivers ${ENGINE_HOME}

WORKDIR ${ENGINE_HOME}
RUN chown -R kyuubi:root ${KYUUBI_HOME} && \
    chmod ug+r -R ${KYUUBI_HOME}

# JdbcKubernetesModeSubmitter always sets the pod's container command explicitly
# (java -cp ... org.apache.kyuubi.engine.jdbc.JdbcSQLEngine --conf ...), which overrides this
# CMD; it is kept only so `docker run` against this image fails with a recognizable error
# instead of silently doing nothing.
CMD ["java", "-cp", "/opt/kyuubi/externals/engines/jdbc/*", "org.apache.kyuubi.engine.jdbc.JdbcSQLEngine"]

USER ${kyuubi_uid}
