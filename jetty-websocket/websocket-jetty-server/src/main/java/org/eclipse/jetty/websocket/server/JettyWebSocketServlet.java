//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.server;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.time.Duration;
import java.util.Set;

import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.websocket.core.Configuration;
import org.eclipse.jetty.websocket.core.WebSocketComponents;
import org.eclipse.jetty.websocket.core.server.WebSocketServerComponents;
import org.eclipse.jetty.websocket.server.internal.JettyServerFrameHandlerFactory;
import org.eclipse.jetty.websocket.util.server.WebSocketUpgradeFilter;
import org.eclipse.jetty.websocket.util.server.internal.FrameHandlerFactory;
import org.eclipse.jetty.websocket.util.server.internal.ServletUpgradeRequest;
import org.eclipse.jetty.websocket.util.server.internal.ServletUpgradeResponse;
import org.eclipse.jetty.websocket.util.server.internal.WebSocketCreator;
import org.eclipse.jetty.websocket.util.server.internal.WebSocketMapping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Abstract Servlet used to bridge the Servlet API to the WebSocket API.
 * <p>
 * To use this servlet, you will be required to register your websockets with the {@link WebSocketMapping} so that it can create your websockets under the
 * appropriate conditions.
 * </p>
 * <p>The most basic implementation would be as follows:</p>
 * <pre>
 * package my.example;
 *
 * import JettyWebSocketServlet;
 * import JettyWebSocketServletFactory;
 *
 * public class MyEchoServlet extends JettyWebSocketServlet
 * {
 *     &#064;Override
 *     public void configure(JettyWebSocketServletFactory factory)
 *     {
 *       factory.setDefaultMaxFrameSize(4096);
 *       factory.addMapping(factory.parsePathSpec("/"), (req,res)-&gt;new EchoSocket());
 *     }
 * }
 * </pre>
 * <p>
 * Only request that conforms to a "WebSocket: Upgrade" handshake request will trigger the {@link WebSocketMapping} handling of creating
 * WebSockets.  All other requests are treated as normal servlet requests.  The configuration defined by this servlet init parameters will
 * be used as the customizer for any mappings created by {@link JettyWebSocketServletFactory#addMapping(String, JettyWebSocketCreator)} during
 * {@link #configure(JettyWebSocketServletFactory)} calls.  The request upgrade may be peformed by this servlet, or is may be performed by a
 * {@link WebSocketUpgradeFilter} instance that will share the same {@link WebSocketMapping} instance.  If the filter is used, then the
 * filter configuraton is used as the default configuration prior to this servlets configuration being applied.
 * </p>
 * <p>
 * <b>Configuration / Init-Parameters:</b>
 * </p>
 * <dl>
 * <dt>idleTimeout</dt>
 * <dd>set the time in ms that a websocket may be idle before closing<br>
 * <dt>maxTextMessageSize</dt>
 * <dd>set the size in UTF-8 bytes that a websocket may be accept as a Text Message before closing<br>
 * <dt>maxBinaryMessageSize</dt>
 * <dd>set the size in bytes that a websocket may be accept as a Binary Message before closing<br>
 * <dt>inputBufferSize</dt>
 * <dd>set the size in bytes of the buffer used to read raw bytes from the network layer<br> * <dt>outputBufferSize</dt>
 * <dd>set the size in bytes of the buffer used to write bytes to the network layer<br>
 * <dt>maxFrameSize</dt>
 * <dd>The maximum frame size sent or received.<br>
 * <dt>autoFragment</dt>
 * <dd>If true, frames are automatically fragmented to respect the maximum frame size.<br>
 * </dl>
 */
public abstract class JettyWebSocketServlet extends HttpServlet
{
    private static final Logger LOG = LoggerFactory.getLogger(JettyWebSocketServlet.class);
    private final CustomizedWebSocketServletFactory customizer = new CustomizedWebSocketServletFactory();

    private WebSocketMapping mapping;
    private WebSocketComponents components;

    /**
     * Configure the JettyWebSocketServletFactory for this servlet instance by setting default
     * configuration (which may be overriden by annotations) and mapping {@link JettyWebSocketCreator}s.
     * This method assumes a single {@link FrameHandlerFactory} will be available as a bean on the
     * {@link ContextHandler}, which in practise will mostly the the Jetty WebSocket API factory.
     *
     * @param factory the JettyWebSocketServletFactory
     */
    protected abstract void configure(JettyWebSocketServletFactory factory);

    /**
     * @return the instance of {@link FrameHandlerFactory} to be used to create the FrameHandler
     */
    private FrameHandlerFactory getFactory()
    {
        JettyServerFrameHandlerFactory frameHandlerFactory = JettyServerFrameHandlerFactory.getFactory(getServletContext());

        if (frameHandlerFactory == null)
            throw new IllegalStateException("JettyServerFrameHandlerFactory not found");

        return frameHandlerFactory;
    }

    @Override
    public void init() throws ServletException
    {
        try
        {
            ServletContext servletContext = getServletContext();

            components = WebSocketServerComponents.ensureWebSocketComponents(servletContext);
            mapping = new WebSocketMapping(components);

            String max = getInitParameter("idleTimeout");
            if (max == null)
            {
                max = getInitParameter("maxIdleTime");
                if (max != null)
                    LOG.warn("'maxIdleTime' init param is deprecated, use 'idleTimeout' instead");
            }
            if (max != null)
                customizer.setIdleTimeout(Duration.ofMillis(Long.parseLong(max)));

            max = getInitParameter("maxTextMessageSize");
            if (max != null)
                customizer.setMaxTextMessageSize(Long.parseLong(max));

            max = getInitParameter("maxBinaryMessageSize");
            if (max != null)
                customizer.setMaxBinaryMessageSize(Long.parseLong(max));

            max = getInitParameter("inputBufferSize");
            if (max != null)
                customizer.setInputBufferSize(Integer.parseInt(max));

            max = getInitParameter("outputBufferSize");
            if (max != null)
                customizer.setOutputBufferSize(Integer.parseInt(max));

            max = getInitParameter("maxFrameSize");
            if (max == null)
                max = getInitParameter("maxAllowedFrameSize");
            if (max != null)
                customizer.setMaxFrameSize(Long.parseLong(max));

            String autoFragment = getInitParameter("autoFragment");
            if (autoFragment != null)
                customizer.setAutoFragment(Boolean.parseBoolean(autoFragment));

            configure(customizer); // Let user modify customizer prior after init params
        }
        catch (Throwable x)
        {
            throw new ServletException(x);
        }
    }

    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp)
        throws ServletException, IOException
    {
        // provide a null default customizer the customizer will be on the negotiator in the mapping
        if (mapping.upgrade(req, resp, null))
            return;

        // If we reach this point, it means we had an incoming request to upgrade
        // but it was either not a proper websocket upgrade, or it was possibly rejected
        // due to incoming request constraints (controlled by WebSocketCreator)
        if (resp.isCommitted())
            return;

        // Handle normally
        super.service(req, resp);
    }

    private class CustomizedWebSocketServletFactory extends Configuration.ConfigurationCustomizer implements JettyWebSocketServletFactory
    {
        @Override
        public Set<String> getAvailableExtensionNames()
        {
            return components.getExtensionRegistry().getAvailableExtensionNames();
        }

        @Override
        public void addMapping(String pathSpec, JettyWebSocketCreator creator)
        {
            mapping.addMapping(WebSocketMapping.parsePathSpec(pathSpec), new WrappedJettyCreator(creator), getFactory(), this);
        }

        @Override
        public void register(Class<?> endpointClass)
        {
            Constructor<?> constructor;
            try
            {
                constructor = endpointClass.getDeclaredConstructor();
            }
            catch (NoSuchMethodException e)
            {
                throw new RuntimeException(e);
            }

            JettyWebSocketCreator creator = (req, resp) ->
            {
                try
                {
                    return constructor.newInstance();
                }
                catch (Throwable t)
                {
                    t.printStackTrace();
                    return null;
                }
            };

            addMapping("/", creator);
        }

        @Override
        public void setCreator(JettyWebSocketCreator creator)
        {
            addMapping("/", creator);
        }

        @Override
        public JettyWebSocketCreator getMapping(String pathSpec)
        {
            WebSocketCreator creator = mapping.getMapping(WebSocketMapping.parsePathSpec(pathSpec));
            if (creator instanceof WrappedJettyCreator)
                return ((WrappedJettyCreator)creator).getJettyWebSocketCreator();

            return null;
        }

        @Override
        public boolean removeMapping(String pathSpec)
        {
            return mapping.removeMapping(WebSocketMapping.parsePathSpec(pathSpec));
        }
    }

    private static class WrappedJettyCreator implements WebSocketCreator
    {
        private final JettyWebSocketCreator creator;

        private WrappedJettyCreator(JettyWebSocketCreator creator)
        {
            this.creator = creator;
        }

        private JettyWebSocketCreator getJettyWebSocketCreator()
        {
            return creator;
        }

        @Override
        public Object createWebSocket(ServletUpgradeRequest req, ServletUpgradeResponse resp)
        {
            return creator.createWebSocket(new JettyServerUpgradeRequest(req), new JettyServerUpgradeResponse(resp));
        }
    }
}
