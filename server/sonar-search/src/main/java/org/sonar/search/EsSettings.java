/*
 * SonarQube, open source software quality management tool.
 * Copyright (C) 2008-2014 SonarSource
 * mailto:contact AT sonarsource DOT com
 *
 * SonarQube is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * SonarQube is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.search;

import org.apache.commons.lang.StringUtils;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.settings.Settings;
import org.slf4j.LoggerFactory;
import org.sonar.process.MessageException;
import org.sonar.process.Props;
import org.sonar.search.script.ListUpdate;

import java.io.File;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

class EsSettings {

  // set by monitor process, so value is never null
  public static final String PROP_TCP_PORT = "sonar.search.port";

  public static final String PROP_CLUSTER_ACTIVATION = "sonar.cluster.activation";
  public static final String PROP_NODE_NAME = "sonar.node.name";
  public static final String PROP_CLUSTER_NAME = "sonar.cluster.name";
  public static final String PROP_CLUSTER_MASTER = "sonar.cluster.master";
  public static final String PROP_MARVEL = "sonar.search.marvel";

  public static final String SONAR_PATH_HOME = "sonar.path.home";
  public static final String SONAR_PATH_DATA = "sonar.path.data";
  public static final String SONAR_PATH_TEMP = "sonar.path.temp";
  public static final String SONAR_PATH_LOG = "sonar.path.log";

  private final Props props;
  private final Set<String> clusterNodes = new LinkedHashSet<String>();
  private final String clusterName;
  private final int tcpPort;

  EsSettings(Props props) {
    this.props = props;
    clusterNodes.addAll(Arrays.asList(StringUtils.split(props.value(PROP_CLUSTER_MASTER, ""), ",")));

    clusterName = props.value(PROP_CLUSTER_NAME);
    Integer port = props.valueAsInt(PROP_TCP_PORT);
    if (port == null) {
      throw new MessageException("Property is not set: " + PROP_TCP_PORT);
    }
    tcpPort = port.intValue();
  }

  boolean inCluster() {
    return !clusterNodes.isEmpty();
  }

  String clusterName() {
    return clusterName;
  }

  int tcpPort() {
    return tcpPort;
  }

  Settings build() {
    ImmutableSettings.Builder builder = ImmutableSettings.settingsBuilder();
    configureFileSystem(builder);
    configureStorage(builder);
    configurePlugins(builder);
    configureNetwork(builder);
    configureCluster(builder);
    configureMarvel(builder);
    System.out.println(builder.build());
    return builder.build();
  }

  private void configureFileSystem(ImmutableSettings.Builder builder) {
    File homeDir = props.nonNullValueAsFile(SONAR_PATH_HOME);
    File dataDir, workDir, logDir;

    // data dir
    String dataPath = props.value(SONAR_PATH_DATA);
    if (StringUtils.isNotEmpty(dataPath)) {
      dataDir = new File(dataPath, "es");
    } else {
      dataDir = new File(homeDir, "data/es");
    }
    builder.put("path.data", dataDir.getAbsolutePath());

    // working dir
    String workPath = props.value(SONAR_PATH_TEMP);
    if (StringUtils.isNotEmpty(workPath)) {
      workDir = new File(workPath);
    } else {
      workDir = new File(homeDir, "temp");
    }
    builder.put("path.work", workDir.getAbsolutePath());
    builder.put("path.plugins", workDir.getAbsolutePath());

    // log dir
    String logPath = props.value(SONAR_PATH_LOG);
    if (StringUtils.isNotEmpty(logPath)) {
      logDir = new File(logPath);
    } else {
      logDir = new File(homeDir, "log");
    }
    builder.put("path.logs", logDir.getAbsolutePath());
  }

  private void configurePlugins(ImmutableSettings.Builder builder) {
    builder
      .put("script.default_lang", "native")
      .put("script.native." + ListUpdate.NAME + ".type", ListUpdate.UpdateListScriptFactory.class.getName());
  }

  private void configureNetwork(ImmutableSettings.Builder builder) {
    // disable multicast
    builder.put("discovery.zen.ping.multicast.enabled", "false");

    builder
      .put("transport.tcp.port", tcpPort)
      .put("http.enabled", false);
  }

  private void configureStorage(ImmutableSettings.Builder builder) {
    builder
      .put("index.number_of_shards", "1")
      .put("index.refresh_interval", "30s")
      .put("index.store.type", "mmapfs")
      .put("indices.store.throttle.type", "none")
      .put("index.merge.scheduler.max_thread_count",
        Math.max(1, Math.min(3, Runtime.getRuntime().availableProcessors() / 2)));
  }

  private void configureCluster(ImmutableSettings.Builder builder) {
    if (!clusterNodes.isEmpty()) {
      LoggerFactory.getLogger(SearchServer.class).info("Joining ES cluster with master: {}", clusterNodes);
      builder.put("discovery.zen.ping.unicast.hosts", StringUtils.join(clusterNodes, ","));
      builder.put("node.master", false);

      // Enforce a N/2+1 number of masters in cluster
      builder.put("discovery.zen.minimum_master_nodes", 1);
    }

    // When SQ is ran as a cluster
    // see https://jira.codehaus.org/browse/SONAR-5687
    int replicationFactor = props.valueAsBoolean(PROP_CLUSTER_ACTIVATION, false) ? 1 : 0;
    builder.put("index.number_of_replicas", replicationFactor);

    // Set cluster coordinates
    builder.put("cluster.name", clusterName);
    builder.put("node.rack_id", props.value(PROP_NODE_NAME, "unknown"));
    if (props.contains(PROP_NODE_NAME)) {
      builder.put("node.name", props.value(PROP_NODE_NAME));
    } else {
      try {
        builder.put("node.name", InetAddress.getLocalHost().getHostName());
      } catch (Exception e) {
        LoggerFactory.getLogger(SearchServer.class).warn("Could not determine hostname", e);
        builder.put("node.name", "sq-" + System.currentTimeMillis());
      }
    }
  }

  private void configureMarvel(ImmutableSettings.Builder builder) {
    Set<String> marvels = new TreeSet<String>();
    marvels.addAll(Arrays.asList(StringUtils.split(props.value(PROP_MARVEL, ""), ",")));

    // Enable marvel's index creation
    builder.put("action.auto_create_index", ".marvel-*");
    // If we're collecting indexing data send them to the Marvel host(s)
    if (!marvels.isEmpty()) {
      builder.put("marvel.agent.exporter.es.hosts", StringUtils.join(marvels, ","));
    }
  }
}
