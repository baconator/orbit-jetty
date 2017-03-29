/*
 Copyright (C) 2016 Electronic Arts Inc.  All rights reserved.

 Redistribution and use in source and binary forms, with or without
 modification, are permitted provided that the following conditions
 are met:

 1.  Redistributions of source code must retain the above copyright
     notice, this list of conditions and the following disclaimer.
 2.  Redistributions in binary form must reproduce the above copyright
     notice, this list of conditions and the following disclaimer in the
     documentation and/or other materials provided with the distribution.
 3.  Neither the name of Electronic Arts, Inc. ("EA") nor the names of
     its contributors may be used to endorse or promote products derived
     from this software without specific prior written permission.

 THIS SOFTWARE IS PROVIDED BY ELECTRONIC ARTS AND ITS CONTRIBUTORS "AS IS" AND ANY
 EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 DISCLAIMED. IN NO EVENT SHALL ELECTRONIC ARTS OR ITS CONTRIBUTORS BE LIABLE FOR ANY
 DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
 THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package cloud.orbit.web;

import cloud.orbit.annotation.Config;
import cloud.orbit.concurrent.Task;
import cloud.orbit.container.Container;
import cloud.orbit.exception.UncheckedException;
import cloud.orbit.lifecycle.Startable;

import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.websocket.jsr356.server.ServerContainer;
import org.eclipse.jetty.websocket.jsr356.server.deploy.WebSocketServerContainerInitializer;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.servlet.ServletContainer;
import org.glassfish.jersey.servlet.ServletProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.servlet.Servlet;
import javax.websocket.server.ServerEndpoint;
import javax.websocket.server.ServerEndpointConfig;
import javax.ws.rs.Path;
import javax.ws.rs.ext.Provider;

import java.net.URL;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by joe@bioware.com on 2016-02-16.
 */

@Singleton
public class EmbeddedJettyServer implements Startable
{
    @Inject
    Container container;

    private static final Logger logger = LoggerFactory.getLogger(EmbeddedJettyServer.class);

    private Server server;

    @Config("orbit.jetty.port")
    private int port = 9090;

    @Config("orbit.jetty.requestHeaderSize")
    private Integer requestHeaderSize = null;

    @Config("orbit.jetty.responseHeaderSize")
    private Integer responseHeaderSize = null;

    @Config("orbit.jetty.outputBufferSize")
    private Integer outputBufferSize = null;


    // SSL config

    @Config("orbit.jetty.ssl.enabled")
    private boolean enableSSL = false;

    @Config("orbit.jetty.ssl.keyStore.path")
    private String sslKeyStorePath = null;

    @Config("orbit.jetty.ssl.keyStore.password")
    private String sslKeyStorePassword = null;

    @Config("orbit.jetty.ssl.keyManager.password")
    private String sslKeyManagerPassword = null;

    @Config("orbit.jetty.ssl.trustStore.path")
    private String sslTrustStorePath = null;

    @Config("orbit.jetty.ssl.trustStore.password")
    private String sslTrustStorePassword = null;

    @Config("orbit.jetty.ssl.cipherSuites.include")
    private List<String> sslIncludedCipherSuites = null;

    @Config("orbit.jetty.ssl.cipherSuites.exclude")
    private List<String> sslExcludedCipherSuites = null;

    @Config("orbit.jetty.ssl.clientAuth.enabled")
    private boolean clientAuth = false;

    @Config("orbit.jetty.ssl.protocols.include")
    private List<String> sslIncludedProtocols = null;

    @Config("orbit.jetty.ssl.protocols.exclude")
    private List<String> sslExcludedProtocols = null;

    @Config("orbit.jetty.ssl.certAlias")
    private String certAlias = null;


    @Override
    public Task start()
    {

        logger.info("Starting Jetty server...");

        final List<Class<?>> classes = container.getDiscoveredClasses();

        final ResourceConfig resourceConfig = new ResourceConfig();

        // Discover only JAX-RS handlers and providers
        classes.stream()
                .filter(r ->
                        (r.isAnnotationPresent(Path.class) || r.isAnnotationPresent(Provider.class))
                        && !r.isAnnotationPresent(ServerEndpoint.class)
                        && !Servlet.class.isAssignableFrom(r))
                .forEach(resourceConfig::register);


        final WebAppContext webAppContext = new WebAppContext();
        final ProtectionDomain protectionDomain = EmbeddedJettyServer.class.getProtectionDomain();
        final URL location = protectionDomain.getCodeSource().getLocation();

        logger.info(location.toExternalForm());
        webAppContext.setInitParameter("useFileMappedBuffer", "false");
        webAppContext.setWar(location.toExternalForm());

        webAppContext.getServletContext().setAttribute(ServletProperties.SERVICE_LOCATOR, container.getServiceLocator());
        webAppContext.setContextPath("/");
        webAppContext.addServlet(new ServletHolder(new ServletContainer(resourceConfig)), "/*");

        // Resource support
        final ContextHandler resourceContext = new ContextHandler();
        ResourceHandler resourceHandler = new ResourceHandler();
        resourceHandler.setDirectoriesListed(true);
        resourceHandler.setWelcomeFiles(new String[]{ "index.html" });
        resourceHandler.setBaseResource(Resource.newClassPathResource("/web"));

        resourceContext.setHandler(resourceHandler);
        resourceContext.setInitParameter("useFileMappedBuffer", "false");


        // Discover Servlets
        classes.stream()
                .filter(r -> r.isAnnotationPresent(Path.class) && Servlet.class.isAssignableFrom(r))
                .forEach(r ->
                {
                    javax.ws.rs.Path path = (Path) r.getAnnotation(Path.class);
                    webAppContext.addServlet((Class<? extends Servlet>) r, path.value());
                    resourceConfig.register(r);
                });

        List<Handler> handlers = new ArrayList<>(3);
        handlers.add(resourceContext);
        handlers.add(webAppContext);

        final ContextHandlerCollection contexts = new ContextHandlerCollection();
        contexts.setHandlers(handlers.toArray(new Handler[handlers.size()]));

        server = new Server();

        // Configure HTTP properties
        final HttpConfiguration httpConfiguration = new HttpConfiguration();
        if(requestHeaderSize != null) httpConfiguration.setRequestHeaderSize(requestHeaderSize);
        if(responseHeaderSize != null) httpConfiguration.setResponseHeaderSize(responseHeaderSize);
        if(outputBufferSize != null) httpConfiguration.setOutputBufferSize(outputBufferSize);

        // Create connector
        List<ServerConnector> connectors = new ArrayList<>();
        if (enableSSL)
        {
            // SSL HTTP Configuration
            final SslContextFactory sslContextFactory = new SslContextFactory();
            sslContextFactory.setNeedClientAuth(clientAuth);
            if (sslKeyStorePath != null)
            {
                sslContextFactory.setKeyStorePath(sslKeyStorePath);
            }
            if (sslKeyStorePassword != null)
            {
                sslContextFactory.setKeyStorePassword(sslKeyStorePassword);
            }
            if (sslKeyManagerPassword != null)
            {
                sslContextFactory.setKeyManagerPassword(sslKeyManagerPassword);
            }
            if (sslTrustStorePath != null)
            {
                sslContextFactory.setTrustStorePath(sslTrustStorePath);
            }
            if (sslTrustStorePassword != null)
            {
                sslContextFactory.setTrustStorePassword(sslTrustStorePassword);
            }
            if (sslIncludedCipherSuites != null)
            {
                sslContextFactory.setIncludeCipherSuites(sslIncludedCipherSuites.toArray(new String[sslIncludedCipherSuites.size()]));
            }
            if (sslExcludedCipherSuites != null)
            {
                sslContextFactory.setExcludeCipherSuites(sslExcludedCipherSuites.toArray(new String[sslExcludedCipherSuites.size()]));
            }
            if (sslIncludedProtocols != null)
            {
                sslContextFactory.setIncludeProtocols(sslIncludedProtocols.toArray(new String[sslIncludedProtocols.size()]));
            }
            if (sslExcludedProtocols != null)
            {
                sslContextFactory.setExcludeProtocols(sslExcludedProtocols.toArray(new String[sslExcludedProtocols.size()]));
            }
            if (certAlias != null)
            {
                sslContextFactory.setCertAlias(certAlias);
            }

            final HttpConfiguration httpsConfiguration = new HttpConfiguration(httpConfiguration);
            httpsConfiguration.addCustomizer(new SecureRequestCustomizer());
            // SSL connector
            final ServerConnector sslConnector = new ServerConnector(server,
                    new SslConnectionFactory(sslContextFactory, HttpVersion.HTTP_1_1.asString()),
                    new HttpConnectionFactory(httpsConfiguration));
            sslConnector.setPort(port);
            connectors.add(sslConnector);
        }
        else
        {
            final HttpConnectionFactory httpConnectionFactory = new HttpConnectionFactory(httpConfiguration);
            final ServerConnector connector = new ServerConnector(server, httpConnectionFactory);
            connector.setPort(port);
            connectors.add(connector);
        }

        // Create the server
        ServerConnector[] actualList = new ServerConnector[connectors.size()];
        actualList = connectors.toArray(actualList);
        server.setConnectors(actualList);
        server.setHandler(contexts);

        // Discover websockets
        try
        {
            ///Initialize javax.websocket layer
            final ServerContainer serverContainer = WebSocketServerContainerInitializer.configureContext(webAppContext);

            classes.stream()
                    .filter(r -> r.isAnnotationPresent(ServerEndpoint.class))
                    .forEach(r ->
                    {
                        final ServerEndpoint annotation = (ServerEndpoint) r.getAnnotation(ServerEndpoint.class);

                        final ServerEndpointConfig serverEndpointConfig = ServerEndpointConfig.Builder.create(r, annotation.value()).configurator(new ServerEndpointConfig.Configurator()
                        {
                            @Override
                            public <T> T getEndpointInstance(final Class<T> endpointClass) throws InstantiationException
                            {
                                T instance = container.getServiceLocator().getService(endpointClass);

                                if(instance == null)
                                {
                                    try
                                    {
                                        instance = endpointClass.newInstance();
                                    }
                                    catch(Exception e)
                                    {
                                        throw new UncheckedException(e);
                                    }
                                }

                                return instance;
                            }
                        }).build();

                        try
                        {
                            serverContainer.addEndpoint(serverEndpointConfig);
                        }
                        catch(Exception e)
                        {
                            throw new UncheckedException(e);
                        }

                    });

        }
        catch (Exception e)
        {
            logger.error("Error starting jetty: " + e.toString());
            throw new UncheckedException(e);
        }


        try
        {
            server.start();
        }
        catch (Exception e)
        {
            logger.error("Error starting jetty: " + e.toString());
            throw new UncheckedException(e);
        }

        logger.info("Jetty server started.");

        return Task.done();
    }

    @Override
    public Task stop()
    {
        try
        {
            server.stop();
        }
        catch (Exception e)
        {
            logger.error("Error stopping jetty: " + e.toString());
            throw new UncheckedException(e);
        }
        return Task.done();
    }

    public int getPort()
    {
        return port;
    }

    public void setPort(int port)
    {
        this.port = port;
    }
}
