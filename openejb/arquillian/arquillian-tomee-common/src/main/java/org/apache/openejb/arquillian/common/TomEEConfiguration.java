/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.openejb.arquillian.common;


import org.jboss.arquillian.container.spi.ConfigurationException;
import org.jboss.arquillian.container.spi.client.container.ContainerConfiguration;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.util.Properties;

public class TomEEConfiguration implements ContainerConfiguration {

    private boolean exportConfAsSystemProperty = false;
    private int httpPort = 8080;
    private int stopPort = 8005;
    private String dir = System.getProperty("java.io.tmpdir") + "/arquillian-apache-tomee";
    private String appWorkingDir = System.getProperty("java.io.tmpdir");
    private String host = "localhost";
    private String serverXml = null;

    public int getHttpPort() {
        return httpPort;
    }

    public void setHttpPort(int httpPort) {
        this.httpPort = httpPort;
    }

    public int getStopPort() {
        return stopPort;
    }

    public void setStopPort(int stopPort) {
        this.stopPort = stopPort;
    }

    public String getDir() {
        return dir;
    }

    public void setDir(String dir) {
        this.dir = dir;
    }

    public String getAppWorkingDir() {
        return appWorkingDir;
    }

    public void setAppWorkingDir(String appWorkingDir) {
        this.appWorkingDir = appWorkingDir;
    }

    public void validate() throws ConfigurationException {
    }

    public boolean getExportConfAsSystemProperty() {
        return exportConfAsSystemProperty;
    }

    public void setExportConfAsSystemProperty(boolean exportConfAsSystemProperty) {
        this.exportConfAsSystemProperty = exportConfAsSystemProperty;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public String getServerXml() {
        return serverXml;
    }

    public void setServerXml(String serverXml) {
        this.serverXml = serverXml;
    }
}
