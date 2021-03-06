/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.aurora.scheduler.http;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.Nonnegative;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.servlet.ServletContextListener;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Multimap;
import com.google.common.net.HostAndPort;
import com.google.common.util.concurrent.AbstractIdleService;
import com.google.inject.AbstractModule;
import com.google.inject.Injector;
import com.google.inject.Module;
import com.google.inject.PrivateModule;
import com.google.inject.Provides;
import com.google.inject.TypeLiteral;
import com.google.inject.name.Names;
import com.google.inject.servlet.GuiceFilter;
import com.google.inject.servlet.GuiceServletContextListener;
import com.google.inject.util.Modules;
import com.sun.jersey.api.container.filter.GZIPContentEncodingFilter;
import com.sun.jersey.guice.JerseyServletModule;
import com.sun.jersey.guice.spi.container.servlet.GuiceContainer;

import org.apache.aurora.common.args.Arg;
import org.apache.aurora.common.args.CmdLine;
import org.apache.aurora.common.net.http.handlers.AbortHandler;
import org.apache.aurora.common.net.http.handlers.ContentionPrinter;
import org.apache.aurora.common.net.http.handlers.HealthHandler;
import org.apache.aurora.common.net.http.handlers.LogConfig;
import org.apache.aurora.common.net.http.handlers.QuitHandler;
import org.apache.aurora.common.net.http.handlers.ThreadStackPrinter;
import org.apache.aurora.common.net.http.handlers.TimeSeriesDataSource;
import org.apache.aurora.common.net.http.handlers.VarsHandler;
import org.apache.aurora.common.net.http.handlers.VarsJsonHandler;
import org.apache.aurora.common.net.pool.DynamicHostSet.MonitorException;
import org.apache.aurora.scheduler.SchedulerServicesModule;
import org.apache.aurora.scheduler.http.api.ApiModule;
import org.apache.aurora.scheduler.http.api.security.HttpSecurityModule;
import org.apache.aurora.scheduler.thrift.ThriftModule;
import org.eclipse.jetty.rewrite.handler.RewriteHandler;
import org.eclipse.jetty.rewrite.handler.RewriteRegexRule;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.DispatcherType;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.server.handler.RequestLogHandler;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlets.GzipFilter;
import org.eclipse.jetty.util.resource.Resource;

import static java.util.Objects.requireNonNull;

import static com.sun.jersey.api.core.ResourceConfig.PROPERTY_CONTAINER_REQUEST_FILTERS;
import static com.sun.jersey.api.core.ResourceConfig.PROPERTY_CONTAINER_RESPONSE_FILTERS;
import static com.sun.jersey.api.json.JSONConfiguration.FEATURE_POJO_MAPPING;

/**
 * Binding module for scheduler HTTP servlets.
 * <p>
 * TODO(wfarner): Continue improvements here by simplifying serving of static assets.  Jetty's
 * DefaultServlet can take over this responsibility, and jetty-rewite can be used to rewrite
 * requests (for static assets) similar to what we currently do with path specs.
 */
public class JettyServerModule extends AbstractModule {
  private static final Logger LOG = Logger.getLogger(JettyServerModule.class.getName());

  // The name of the request attribute where the path for the current request before it was
  // rewritten is stored.
  static final String ORIGINAL_PATH_ATTRIBUTE_NAME = "originalPath";

  @CmdLine(name = "hostname",
      help = "The hostname to advertise in ZooKeeper instead of the locally-resolved hostname.")
  private static final Arg<String> HOSTNAME_OVERRIDE = Arg.create(null);

  @Nonnegative
  @CmdLine(name = "http_port",
      help = "The port to start an HTTP server on.  Default value will choose a random port.")
  protected static final Arg<Integer> HTTP_PORT = Arg.create(0);

  public static final Map<String, String> GUICE_CONTAINER_PARAMS = ImmutableMap.of(
      FEATURE_POJO_MAPPING, Boolean.TRUE.toString(),
      PROPERTY_CONTAINER_REQUEST_FILTERS, GZIPContentEncodingFilter.class.getName(),
      PROPERTY_CONTAINER_RESPONSE_FILTERS, GZIPContentEncodingFilter.class.getName());

  private static final String STATIC_ASSETS_ROOT = Resource
      .newClassPathResource("scheduler/assets/index.html")
      .toString()
      .replace("assets/index.html", "");

  private final boolean production;

  public JettyServerModule() {
    this(true);
  }

  @VisibleForTesting
  JettyServerModule(boolean production) {
    this.production = production;
  }

  @Override
  protected void configure() {
    bind(Runnable.class)
        .annotatedWith(Names.named(AbortHandler.ABORT_HANDLER_KEY))
        .to(AbortCallback.class);
    bind(AbortCallback.class).in(Singleton.class);
    bind(Runnable.class).annotatedWith(Names.named(QuitHandler.QUIT_HANDLER_KEY))
        .to(QuitCallback.class);
    bind(QuitCallback.class).in(Singleton.class);
    bind(new TypeLiteral<Supplier<Boolean>>() { })
        .annotatedWith(Names.named(HealthHandler.HEALTH_CHECKER_KEY))
        .toInstance(Suppliers.ofInstance(true));

    final Optional<String> hostnameOverride = Optional.fromNullable(HOSTNAME_OVERRIDE.get());
    if (hostnameOverride.isPresent()) {
      try {
        InetAddress.getByName(hostnameOverride.get());
      } catch (UnknownHostException e) {
        /* Possible misconfiguration, so warn the user. */
        LOG.warning("Unable to resolve name specified in -hostname. "
                    + "Depending on your environment, this may be valid.");
      }
    }
    install(new PrivateModule() {
      @Override
      protected void configure() {
        bind(new TypeLiteral<Optional<String>>() { }).toInstance(hostnameOverride);
        bind(HttpService.class).to(HttpServerLauncher.class);
        bind(HttpServerLauncher.class).in(Singleton.class);
        expose(HttpServerLauncher.class);
        expose(HttpService.class);
      }
    });
    SchedulerServicesModule.addAppStartupServiceBinding(binder()).to(HttpServerLauncher.class);

    bind(LeaderRedirect.class).in(Singleton.class);
    SchedulerServicesModule.addAppStartupServiceBinding(binder()).to(RedirectMonitor.class);

    if (production) {
      install(PRODUCTION_SERVLET_CONTEXT_LISTENER);
    }
  }

  private static final Module PRODUCTION_SERVLET_CONTEXT_LISTENER = new AbstractModule() {
    @Override
    protected void configure() {
      // Provider binding only.
    }

    @Provides
    @Singleton
    ServletContextListener provideServletContextListener(Injector parentInjector) {
      return makeServletContextListener(
          parentInjector,
          Modules.combine(
              new ApiModule(),
              new H2ConsoleModule(),
              new HttpSecurityModule(),
              new ThriftModule()));
    }
  };

  private static final Set<String> LEADER_ENDPOINTS = ImmutableSet.of(
      "api",
      "cron",
      "locks",
      "maintenance",
      "mname",
      "offers",
      "pendingtasks",
      "quotas",
      "slaves",
      "utilization"
  );

  private static final Multimap<Class<?>, String> JAX_RS_ENDPOINTS =
      ImmutableMultimap.<Class<?>, String>builder()
          .put(AbortHandler.class, "abortabortabort")
          .put(ContentionPrinter.class, "contention")
          .put(Cron.class, "cron")
          .put(HealthHandler.class, "health")
          .put(Locks.class, "locks")
          .put(LogConfig.class, "logconfig")
          .put(Maintenance.class, "maintenance")
          .put(Mname.class, "mname")
          .put(Offers.class, "offers")
          .put(PendingTasks.class, "pendingtasks")
          .put(QuitHandler.class, "quitquitquit")
          .put(Quotas.class, "quotas")
          .put(Services.class, "services")
          .put(Slaves.class, "slaves")
          .put(StructDump.class, "structdump")
          .put(ThreadStackPrinter.class, "threads")
          .put(TimeSeriesDataSource.class, "graphdata")
          .put(Utilization.class, "utilization")
          .put(VarsHandler.class, "vars")
          .put(VarsJsonHandler.class, "vars.json")
          .build();

  private static String allOf(Set<String> paths) {
    return "^(?:"
        + Joiner.on("|").join(Iterables.transform(paths, path -> "/" + path))
        + ").*$";
  }

  // TODO(ksweeney): Factor individual servlet configurations to their own ServletModules.
  @VisibleForTesting
  static ServletContextListener makeServletContextListener(
      final Injector parentInjector,
      final Module childModule) {

    return new GuiceServletContextListener() {
      @Override
      protected Injector getInjector() {
        return parentInjector.createChildInjector(
            childModule,
            new JerseyServletModule() {
              @Override
              protected void configureServlets() {
                bind(HttpStatsFilter.class).in(Singleton.class);
                filter("*").through(HttpStatsFilter.class);

                bind(LeaderRedirectFilter.class).in(Singleton.class);
                filterRegex(allOf(LEADER_ENDPOINTS))
                    .through(LeaderRedirectFilter.class);

                bind(GuiceContainer.class).in(Singleton.class);
                filterRegex(allOf(ImmutableSet.copyOf(JAX_RS_ENDPOINTS.values())))
                    .through(GuiceContainer.class, GUICE_CONTAINER_PARAMS);

                filterRegex("/assets/.*").through(new GzipFilter());
                filterRegex("/assets/scheduler(?:/.*)?").through(LeaderRedirectFilter.class);

                serve("/assets", "/assets/*")
                    .with(new DefaultServlet(), ImmutableMap.of(
                        "resourceBase", STATIC_ASSETS_ROOT,
                        "dirAllowed", "false"));

                for (Class<?> jaxRsHandler : JAX_RS_ENDPOINTS.keySet()) {
                  bind(jaxRsHandler);
                }
              }
            });
      }
    };
  }

  static class RedirectMonitor extends AbstractIdleService {
    private final LeaderRedirect redirector;

    @Inject
    RedirectMonitor(LeaderRedirect redirector) {
      this.redirector = requireNonNull(redirector);
    }

    @Override
    public void startUp() throws MonitorException {
      redirector.monitor();
    }

    @Override
    protected void shutDown() {
      // Nothing to do here - we await VM shutdown.
    }
  }

  public static final class HttpServerLauncher extends AbstractIdleService implements HttpService {
    private final ServletContextListener servletContextListener;
    private final Optional<String> advertisedHostOverride;
    private volatile Server server;

    @Inject
    HttpServerLauncher(
        ServletContextListener servletContextListener,
        Optional<String> advertisedHostOverride) {

      this.servletContextListener = requireNonNull(servletContextListener);
      this.advertisedHostOverride = requireNonNull(advertisedHostOverride);
    }

    private static final Map<String, String> REGEX_REWRITE_RULES =
        ImmutableMap.<String, String>builder()
            .put("/(?:index.html)?", "/assets/index.html")
            .put("/graphview(?:/index.html)?", "/assets/graphview/graphview.html")
            .put("/graphview/(.*)", "/assets/graphview/$1")
            .put("/(?:scheduler|updates)(?:/.*)?", "/assets/scheduler/index.html")
            .build();

    private RewriteHandler getRewriteHandler(HandlerCollection rootHandler) {
      RewriteHandler rewrites = new RewriteHandler();
      rewrites.setOriginalPathAttribute(ORIGINAL_PATH_ATTRIBUTE_NAME);
      rewrites.setRewriteRequestURI(true);
      rewrites.setRewritePathInfo(true);

      for (Map.Entry<String, String> entry : REGEX_REWRITE_RULES.entrySet()) {
        RewriteRegexRule rule = new RewriteRegexRule();
        rule.setRegex(entry.getKey());
        rule.setReplacement(entry.getValue());
        rewrites.addRule(rule);
      }

      rewrites.setHandler(rootHandler);

      return rewrites;
    }

    @Override
    public HostAndPort getAddress() {
      Preconditions.checkState(state() == State.RUNNING);
      Connector connector = server.getConnectors()[0];

      String host;
      if (advertisedHostOverride.isPresent()) {
        host = advertisedHostOverride.get();
      } else if (connector.getHost() == null) {
        // Resolve the local host name.
        try {
          host = InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException e) {
          throw new RuntimeException("Failed to resolve local host address: " + e, e);
        }
      } else {
        // If jetty was configured with a specific host to bind to, use that.
        host = connector.getHost();
      }

      return HostAndPort.fromParts(host, connector.getLocalPort());
    }

    @Override
    protected void startUp() {
      // N.B. we explicitly disable the resource cache here due to a bug serving content out of the
      // jar under the vagrant image. C.f. https://bugs.eclipse.org/bugs/show_bug.cgi?id=364936
      Resource.setDefaultUseCaches(false);

      server = new Server();
      ServletContextHandler servletHandler =
          new ServletContextHandler(server, "/", ServletContextHandler.NO_SESSIONS);

      servletHandler.addServlet(DefaultServlet.class, "/");
      servletHandler.addFilter(GuiceFilter.class, "/*", EnumSet.allOf(DispatcherType.class));
      servletHandler.addEventListener(servletContextListener);

      HandlerCollection rootHandler = new HandlerCollection();

      RequestLogHandler logHandler = new RequestLogHandler();
      logHandler.setRequestLog(new RequestLogger());

      rootHandler.addHandler(logHandler);
      rootHandler.addHandler(servletHandler);

      Connector connector = new SelectChannelConnector();
      connector.setPort(HTTP_PORT.get());
      server.addConnector(connector);
      server.setHandler(getRewriteHandler(rootHandler));

      try {
        connector.open();
        server.start();
      } catch (Exception e) {
        throw Throwables.propagate(e);
      }
    }

    @Override
    protected void shutDown() {
      LOG.info("Shutting down embedded http server");
      try {
        server.stop();
      } catch (Exception e) {
        LOG.log(Level.INFO, "Failed to stop jetty server: " + e, e);
      }
    }
  }
}
