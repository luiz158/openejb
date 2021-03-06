/*
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

package org.apache.tomee.webapp.command.impl;

import org.apache.openejb.AppContext;
import org.apache.openejb.loader.SystemInstance;
import org.apache.openejb.spi.ContainerSystem;
import org.apache.tomee.webapp.TomeeException;
import org.apache.tomee.webapp.command.Command;
import org.apache.tomee.webapp.command.Params;
import org.apache.tomee.webapp.command.impl.script.Utility;

import javax.script.*;
import java.util.concurrent.CountDownLatch;

public class RunScript implements Command {

    @Override
    public Object execute(final Params params) throws Exception {
        final String scriptCode = params.getString("scriptCode");
        if (scriptCode == null) {
            return null; //nothing to do
        }

        String engineName = params.getString("engineName");
        if (engineName == null) {
            engineName = "js";
        }

        final CountDownLatch latch = new CountDownLatch(1);

        //everything should be created inside the new classloader, so run it inside another thread and set the proper classloader
        final ExecutionThread execution = new ExecutionThread(latch, params, engineName, scriptCode);
        final Thread thread = new Thread(execution);
        thread.setContextClassLoader(getClassLoader(params.getString("appName")));
        thread.start();

        //wait until it is done
        latch.await();

        //any exception?
        if (execution.getException() != null) {
            //just throw it
            throw new TomeeException(execution.getException());
        }
        return execution.getResult();
    }

    private ClassLoader getClassLoader(final String appName) {
        if (appName == null) {
            return Thread.currentThread().getContextClassLoader();
        }

        final ContainerSystem cs = SystemInstance.get().getComponent(ContainerSystem.class);
        final AppContext ctx = cs.getAppContext(appName);
        if (ctx == null) {
            return Thread.currentThread().getContextClassLoader();
        }

        return ctx.getClassLoader();
    }

    private class ExecutionThread implements Runnable {
        private final CountDownLatch latch;
        private final Params params;
        private final String engineName;
        private final String scriptCode;

        private Object result;
        private Exception exception;

        public Object getResult() {
            return result;
        }

        public Exception getException() {
            return exception;
        }

        private ExecutionThread(final CountDownLatch latch, final Params params, final String engineName, final String scriptCode) {
            this.latch = latch;
            this.params = params;
            this.engineName = engineName;
            this.scriptCode = scriptCode;
        }

        @Override
        public void run() {
            final ScriptEngineManager manager = new ScriptEngineManager();
            final ScriptEngine engine = manager.getEngineByName(this.engineName);

            //new context for the execution of this script
            final ScriptContext newContext = new SimpleScriptContext();

            //creating the bidings object for the current execution
            final Bindings bindings = newContext.getBindings(ScriptContext.ENGINE_SCOPE);

            bindings.put("util", new Utility(this.params));

            try {
                this.result = engine.eval(this.scriptCode, newContext);
            } catch (ScriptException e) {
                this.exception = e;
            } finally {
                latch.countDown();
            }
        }
    }
}
