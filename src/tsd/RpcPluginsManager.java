// This file is part of OpenTSDB.
// Copyright (C) 2010-2014  The OpenTSDB Authors.
//
// This program is free software: you can redistribute it and/or modify it
// under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 2.1 of the License, or (at your
// option) any later version.  This program is distributed in the hope that it
// will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty
// of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser
// General Public License for more details.  You should have received a copy
// of the GNU Lesser General Public License along with this program.  If not,
// see <http://www.gnu.org/licenses/>.
package net.opentsdb.tsd;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.stumbleupon.async.Callback;
import com.stumbleupon.async.Deferred;

import org.jboss.netty.channel.Channel;
import org.jboss.netty.handler.codec.http.HttpMethod;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.opentsdb.BuildData;
import net.opentsdb.core.Aggregators;
import net.opentsdb.core.TSDB;
import net.opentsdb.utils.JSON;
import net.opentsdb.utils.PluginLoader;

final class RpcPluginsManager {
  private static final Logger LOG = LoggerFactory.getLogger(RpcPluginsManager.class);

  /** Commands we can serve on the simple, telnet-style RPC interface. */
  private ImmutableMap<String, TelnetRpc> telnet_commands;
  /** Commands we serve on the HTTP interface. */
  private ImmutableMap<String, HttpRpc> http_commands;
  /** HTTP commands from user plugins. */
  private ImmutableMap<String, HttpRpcPlugin> http_plugin_commands;
  /** List of activated RPC plugins */
  private ImmutableList<RpcPlugin> rpc_plugins = null;
  
  /** The TSDB that owns us. */
  private TSDB tsdb;
  
  public RpcPluginsManager() {
    // Default values before initialize is called.
    telnet_commands = ImmutableMap.of();
    http_commands = ImmutableMap.of();
    http_plugin_commands = ImmutableMap.of();
    rpc_plugins = ImmutableList.of();
  }
  
  /**
   * @param tsdb The parent TSDB object
   */
  public void initialize(final TSDB tsdb) {
    this.tsdb = tsdb;
    final String mode = tsdb.getConfig().getString("tsd.mode");
    
    if (tsdb.getConfig().hasProperty("tsd.rpc.plugins")) {
      final String[] plugins = tsdb.getConfig().getString("tsd.rpc.plugins").split(",");
      final ImmutableList.Builder<RpcPlugin> rpcBuilder = ImmutableList.builder();
      initializeRpcPlugins(plugins, rpcBuilder);
      rpc_plugins = rpcBuilder.build();
    }
    
    final ImmutableMap.Builder<String, TelnetRpc> telnetBuilder = ImmutableMap.builder();
    final ImmutableMap.Builder<String, HttpRpc> httpBuilder = ImmutableMap.builder();
    initializeBuiltinRpcs(mode, telnetBuilder, httpBuilder);
    telnet_commands = telnetBuilder.build();
    http_commands = httpBuilder.build();
    
    if (tsdb.getConfig().hasProperty("tsd.httprpc.plugins")) {
      final String[] plugins = tsdb.getConfig().getString("tsd.httprpc.plugins").split(",");
      final ImmutableMap.Builder<String, HttpRpcPlugin> httpPluginsBuilder = ImmutableMap.builder();
      initializeHttpRpcPlugins(mode, plugins, httpPluginsBuilder);
      http_plugin_commands = httpPluginsBuilder.build();
    }
  }
  
  public TelnetRpc lookupTelnetRpc(final String command) {
    return telnet_commands.get(command);
  }
  
  public HttpRpc lookupHttpRpc(final String queryBaseRoute) {
    return http_commands.get(queryBaseRoute);
  }
  
  public HttpRpcPlugin lookupHttpRpcPlugin(final String queryBaseRoute) {
    return http_plugin_commands.get(queryBaseRoute);
  }
  
  private void initializeBuiltinRpcs(final String mode, 
        final ImmutableMap.Builder<String, TelnetRpc> telnet,
        final ImmutableMap.Builder<String, HttpRpc> http) {
    if (mode.equals("rw") || mode.equals("wo")) {
      final PutDataPointRpc put = new PutDataPointRpc();
      telnet.put("put", put);
      http.put("api/put", put);
    }
    
    if (mode.equals("rw") || mode.equals("ro")) {
      http.put("", new HomePage());
      final StaticFileRpc staticfile = new StaticFileRpc();
      http.put("favicon.ico", staticfile);
      http.put("s", staticfile);

      final StatsRpc stats = new StatsRpc();
      telnet.put("stats", stats);
      http.put("stats", stats);
      http.put("api/stats", stats);

      final DropCaches dropcaches = new DropCaches();
      telnet.put("dropcaches", dropcaches);
      http.put("dropcaches", dropcaches);
      http.put("api/dropcaches", dropcaches);

      final ListAggregators aggregators = new ListAggregators();
      http.put("aggregators", aggregators);
      http.put("api/aggregators", aggregators);

      final SuggestRpc suggest_rpc = new SuggestRpc();
      http.put("suggest", suggest_rpc);
      http.put("api/suggest", suggest_rpc);

      http.put("logs", new LogsRpc());
      http.put("q", new GraphHandler());
      http.put("api/serializers", new Serializers());
      http.put("api/uid", new UniqueIdRpc());
      http.put("api/query", new QueryRpc());
      http.put("api/tree", new TreeRpc());
      http.put("api/annotation", new AnnotationRpc());
      http.put("api/search", new SearchRpc());
      http.put("api/config", new ShowConfig());
      
      if (tsdb.getConfig().getString("tsd.no_diediedie").equals("false")) {
        final DieDieDie diediedie = new DieDieDie();
        telnet.put("diediedie", diediedie);
        http.put("diediedie", diediedie);
      }
      {
        final Version version = new Version();
        telnet.put("version", version);
        http.put("version", version);
        http.put("api/version", version);
      }

      telnet.put("exit", new Exit());
      telnet.put("help", new Help());
    }
  }
  
  private void initializeHttpRpcPlugins(final String mode,
        final String[] pluginClassNames,
        final ImmutableMap.Builder<String, HttpRpcPlugin> http) {
    for (final String plugin : pluginClassNames) {
      final HttpRpcPlugin rpc = createAndInitialize(plugin, HttpRpcPlugin.class);
      LOG.info("Mounting {} at {}", rpc.getClass().getName(), rpc.getPath());
      http.put(rpc.getPath(), rpc);
    }
  }
  
  private void initializeRpcPlugins(final String[] pluginClassNames, 
        final ImmutableList.Builder<RpcPlugin> rpcs) {
    for (final String plugin : pluginClassNames) {
      final RpcPlugin rpc = createAndInitialize(plugin, RpcPlugin.class);
      rpcs.add(rpc);
    }
  }
  
  /**
   * Helper method to load and initialize a given plugin class. This uses reflection
   * because plugins share no common interfaces. (They could though!)
   * @param pluginClassName the class name of the plugin to load
   * @param pluginClass class of the plugin
   * @return loaded an initialized instance of {@code pluginClass}
   */
  private <T> T createAndInitialize(final String pluginClassName, final Class<T> pluginClass) {
    final T instance = PluginLoader.loadSpecificPlugin(pluginClassName, pluginClass);
    Preconditions.checkState(instance != null, 
        "Unable to locate %s using name '%s", pluginClass, pluginClassName);
    try {
      final Method initMeth = instance.getClass().getMethod("initialize", TSDB.class);
      initMeth.invoke(instance, tsdb);
      final Method versionMeth = instance.getClass().getMethod("version");
      String version = (String) versionMeth.invoke(instance);
      LOG.info("Successfully initialized plugin [{}] version: {}",
          instance.getClass().getCanonicalName(),
          version);
      return instance;
    } catch (Exception e) {
      throw new RuntimeException("Failed to initialize " + instance.getClass());
    }
  }
  
  /**
   * Called to gracefully shutdown the plugin. Implementations should close 
   * any IO they have open
   * @return A deferred object that indicates the completion of the request.
   * The {@link Object} has not special meaning and can be {@code null}
   * (think of it as {@code Deferred<Void>}).
   */
  public Deferred<Object> shutdown() {
    final Deferred<Object> deferred = new Deferred<Object>();
    
    if (http_plugin_commands != null) {
      for (final Map.Entry<String, HttpRpcPlugin> entry : http_plugin_commands.entrySet()) {
        deferred.chain(entry.getValue().shutdown());
      }
    }
    
    if (rpc_plugins != null) {
      for (final RpcPlugin rpc : rpc_plugins) {
        deferred.chain(rpc.shutdown());
      }
    }
    
    return deferred;
  }
  
  // ---------------------------- //
  // Individual command handlers. //
  // ---------------------------- //

  /** The "diediedie" command and "/diediedie" endpoint. */
  private final class DieDieDie implements TelnetRpc, HttpRpc {
    public Deferred<Object> execute(final TSDB tsdb, final Channel chan,
                                    final String[] cmd) {
      LOG.warn("{} {}", chan, "shutdown requested");
      chan.write("Cleaning up and exiting now.\n");
      return doShutdown(tsdb, chan);
    }

    public void execute(final TSDB tsdb, final HttpQuery query) {
      LOG.warn("{} {}", query, "shutdown requested");
      query.sendReply(HttpQuery.makePage("TSD Exiting", "You killed me",
                                         "Cleaning up and exiting now."));
      doShutdown(tsdb, query.channel());
    }

    private Deferred<Object> doShutdown(final TSDB tsdb, final Channel chan) {
      ((GraphHandler) http_commands.get("q")).shutdown();
      ConnectionManager.closeAllConnections();
      // Netty gets stuck in an infinite loop if we shut it down from within a
      // NIO thread.  So do this from a newly created thread.
      final class ShutdownNetty extends Thread {
        ShutdownNetty() {
          super("ShutdownNetty");
        }
        public void run() {
          chan.getFactory().releaseExternalResources();
        }
      }
      new ShutdownNetty().start();  // Stop accepting new connections.

      // Log any error that might occur during shutdown.
      final class ShutdownTSDB implements Callback<Exception, Exception> {
        public Exception call(final Exception arg) {
          LOG.error("Unexpected exception while shutting down", arg);
          return arg;
        }
        public String toString() {
          return "shutdown callback";
        }
      }
      return tsdb.shutdown().addErrback(new ShutdownTSDB());
    }
  }

  /** The "exit" command. */
  private static final class Exit implements TelnetRpc {
    public Deferred<Object> execute(final TSDB tsdb, final Channel chan,
                                    final String[] cmd) {
      chan.disconnect();
      return Deferred.fromResult(null);
    }
  }

  /** The "help" command. */
  private final class Help implements TelnetRpc {
    public Deferred<Object> execute(final TSDB tsdb, final Channel chan,
                                    final String[] cmd) {
      final StringBuilder buf = new StringBuilder();
      buf.append("available commands: ");
      // TODO(tsuna): Maybe sort them?
      for (final String command : telnet_commands.keySet()) {
        buf.append(command).append(' ');
      }
      buf.append('\n');
      chan.write(buf.toString());
      return Deferred.fromResult(null);
    }
  }

  /** The home page ("GET /"). */
  private static final class HomePage implements HttpRpc {
    public void execute(final TSDB tsdb, final HttpQuery query) 
      throws IOException {
      final StringBuilder buf = new StringBuilder(2048);
      buf.append("<div id=queryuimain></div>"
                 + "<noscript>You must have JavaScript enabled.</noscript>"
                 + "<iframe src=javascript:'' id=__gwt_historyFrame tabIndex=-1"
                 + " style=position:absolute;width:0;height:0;border:0>"
                 + "</iframe>");
      query.sendReply(HttpQuery.makePage(
        "<script type=text/javascript language=javascript"
        + " src=/s/queryui.nocache.js></script>",
        "TSD", "Time Series Database", buf.toString()));
    }
  }
  
  /** The "/aggregators" endpoint. */
  private static final class ListAggregators implements HttpRpc {
    public void execute(final TSDB tsdb, final HttpQuery query) 
      throws IOException {
      
      // only accept GET/POST
      if (query.method() != HttpMethod.GET && query.method() != HttpMethod.POST) {
        throw new BadRequestException(HttpResponseStatus.METHOD_NOT_ALLOWED, 
            "Method not allowed", "The HTTP method [" + query.method().getName() +
            "] is not permitted for this endpoint");
      }
      
      if (query.apiVersion() > 0) {
        query.sendReply(
            query.serializer().formatAggregatorsV1(Aggregators.set()));
      } else {
        query.sendReply(JSON.serializeToBytes(Aggregators.set()));
      }
    }
  }

  /** The "version" command. */
  private static final class Version implements TelnetRpc, HttpRpc {
    public Deferred<Object> execute(final TSDB tsdb, final Channel chan,
                                    final String[] cmd) {
      if (chan.isConnected()) {
        chan.write(BuildData.revisionString() + '\n'
                   + BuildData.buildString() + '\n');
      }
      return Deferred.fromResult(null);
    }

    public void execute(final TSDB tsdb, final HttpQuery query) throws 
      IOException {
      
      // only accept GET/POST
      if (query.method() != HttpMethod.GET && query.method() != HttpMethod.POST) {
        throw new BadRequestException(HttpResponseStatus.METHOD_NOT_ALLOWED, 
            "Method not allowed", "The HTTP method [" + query.method().getName() +
            "] is not permitted for this endpoint");
      }
      
      final HashMap<String, String> version = new HashMap<String, String>();
      version.put("version", BuildData.version);
      version.put("short_revision", BuildData.short_revision);
      version.put("full_revision", BuildData.full_revision);
      version.put("timestamp", Long.toString(BuildData.timestamp));
      version.put("repo_status", BuildData.repo_status.toString());
      version.put("user", BuildData.user);
      version.put("host", BuildData.host);
      version.put("repo", BuildData.repo);
      
      if (query.apiVersion() > 0) {
        query.sendReply(query.serializer().formatVersionV1(version));
      } else {
        final boolean json = query.request().getUri().endsWith("json");      
        if (json) {
          query.sendReply(JSON.serializeToBytes(version));
        } else {
          final String revision = BuildData.revisionString();
          final String build = BuildData.buildString();
          StringBuilder buf;
          buf = new StringBuilder(2 // For the \n's
                                  + revision.length() + build.length());
          buf.append(revision).append('\n').append(build).append('\n');
          query.sendReply(buf);
        }
      }
    }
  }
  
  /** The "dropcaches" command. */
  private static final class DropCaches implements TelnetRpc, HttpRpc {
    public Deferred<Object> execute(final TSDB tsdb, final Channel chan,
                                    final String[] cmd) {
      dropCaches(tsdb, chan);
      chan.write("Caches dropped.\n");
      return Deferred.fromResult(null);
    }

    public void execute(final TSDB tsdb, final HttpQuery query) 
      throws IOException {
      dropCaches(tsdb, query.channel());
      
      // only accept GET/POST
      if (query.method() != HttpMethod.GET && query.method() != HttpMethod.POST) {
        throw new BadRequestException(HttpResponseStatus.METHOD_NOT_ALLOWED, 
            "Method not allowed", "The HTTP method [" + query.method().getName() +
            "] is not permitted for this endpoint");
      }
      
      if (query.apiVersion() > 0) {
        final HashMap<String, String> response = new HashMap<String, String>();
        response.put("status", "200");
        response.put("message", "Caches dropped");
        query.sendReply(query.serializer().formatDropCachesV1(response));
      } else { // deprecated API
        query.sendReply("Caches dropped.\n");
      }
    }

    /** Drops in memory caches.  */
    private void dropCaches(final TSDB tsdb, final Channel chan) {
      LOG.warn(chan + " Dropping all in-memory caches.");
      tsdb.dropCaches();
    }
  }

  /** The /api/formatters endpoint 
   * @since 2.0 */
  private static final class Serializers implements HttpRpc {
    public void execute(final TSDB tsdb, final HttpQuery query) 
      throws IOException {
      // only accept GET/POST
      if (query.method() != HttpMethod.GET && query.method() != HttpMethod.POST) {
        throw new BadRequestException(HttpResponseStatus.METHOD_NOT_ALLOWED, 
            "Method not allowed", "The HTTP method [" + query.method().getName() +
            "] is not permitted for this endpoint");
      }
      
      switch (query.apiVersion()) {
        case 0:
        case 1:
          query.sendReply(query.serializer().formatSerializersV1());
          break;
        default: 
          throw new BadRequestException(HttpResponseStatus.NOT_IMPLEMENTED, 
              "Requested API version not implemented", "Version " + 
              query.apiVersion() + " is not implemented");
      }
    }
  }
  
  private static final class ShowConfig implements HttpRpc {
    @Override
    public void execute(TSDB tsdb, HttpQuery query) throws IOException {
      // only accept GET/POST
      if (query.method() != HttpMethod.GET && query.method() != HttpMethod.POST) {
        throw new BadRequestException(HttpResponseStatus.METHOD_NOT_ALLOWED, 
            "Method not allowed", "The HTTP method [" + query.method().getName() +
            "] is not permitted for this endpoint");
      }
      
      switch (query.apiVersion()) {
        case 0:
        case 1:
          query.sendReply(query.serializer().formatConfigV1(tsdb.getConfig()));
          break;
        default: 
          throw new BadRequestException(HttpResponseStatus.NOT_IMPLEMENTED, 
              "Requested API version not implemented", "Version " + 
              query.apiVersion() + " is not implemented");
      }
    }
  }

}