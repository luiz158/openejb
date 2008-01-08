/**
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.openejb.server.httpd;

import org.apache.openejb.server.ServerService;
import org.apache.openejb.server.ServiceException;
import org.apache.openejb.server.ejbd.EjbServer;
import org.apache.openejb.loader.SystemInstance;

import java.util.Properties;
import java.net.Socket;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * @version $Revision$ $Date$
 */
public class HttpEjbServer implements ServerService {
    private HttpServer httpServer;
    private String name;

    public void init(Properties props) throws Exception {
        name = props.getProperty("name");
        EjbServer ejbServer = new EjbServer();
        ServerServiceAdapter adapter = new ServerServiceAdapter(ejbServer);

        SystemInstance systemInstance = SystemInstance.get();
        HttpListenerRegistry registry = systemInstance.getComponent(HttpListenerRegistry.class);
        if (registry == null){
            registry = new HttpListenerRegistry();
            systemInstance.setComponent(HttpListenerRegistry.class, registry);
        }

        registry.addHttpListener(adapter, "/ejb/?.*");

        // todo this breaks the http ejb server impl
        // the service manage makes a static decision based on the interface of this class to create
        // a service daemon socket or not.  Since jetty is "self managed" and throws an exception from
        // service socket, this breaks this code
//        // props can name an implementation
//        // if implementation is not named, use jetty if jetty classes are present; otherwise use openejb
//        String impl = props.getProperty("impl");
//        if ("Jetty".equalsIgnoreCase(impl)) {
//            httpServer = (HttpServer) getClass().getClassLoader().loadClass("org.apache.openejb.server.httpd.JettyHttpServer").newInstance();
//        } else if ("OpenEJB".equalsIgnoreCase(impl)) {
            httpServer = new OpenEJBHttpServer(registry);
//        } else if (impl == null) {
//            try {
//                getClass().getClassLoader().loadClass("org.mortbay.jetty.Server");
//                httpServer = (HttpServer) getClass().getClassLoader().loadClass("org.apache.openejb.server.httpd.JettyHttpServer").newInstance();
//            } catch (Exception ignored) {
//                // Jetty classes not available
//                httpServer = new OpenEJBHttpServer(registry);
//            }
//        } else {
//            throw new IllegalArgumentException("Unknown HTTP server impl '" + impl + "'. Valid implementation are OpenEJB and Jetty.");
//        }

        // register the http server
        systemInstance.setComponent(HttpServer.class, httpServer);

        httpServer.init(props);
        ejbServer.init(props);
    }


    public void service(Socket socket) throws ServiceException, IOException {
        httpServer.service(socket);
    }

    public void service(InputStream in, OutputStream out) throws ServiceException, IOException {
        httpServer.service(in, out);
    }

    public void start() throws ServiceException {
        httpServer.start();
    }

    public void stop() throws ServiceException {
        httpServer.stop();
    }

    public String getName() {
        return name;
    }

    public int getPort() {
        return httpServer.getPort();
    }

    public String getIP() {
        return httpServer.getIP();
    }
}
