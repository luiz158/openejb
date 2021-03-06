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
package org.apache.openejb.assembler.classic;

import java.io.File;
import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import javax.enterprise.inject.spi.Bean;
import javax.enterprise.inject.spi.BeanManager;
import javax.management.InstanceNotFoundException;
import javax.management.MBeanRegistrationException;
import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import javax.naming.Binding;
import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NameAlreadyBoundException;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.resource.spi.BootstrapContext;
import javax.resource.spi.ConnectionManager;
import javax.resource.spi.ManagedConnectionFactory;
import javax.resource.spi.ResourceAdapter;
import javax.resource.spi.ResourceAdapterInternalException;
import javax.resource.spi.XATerminator;
import javax.resource.spi.work.WorkManager;
import javax.sql.DataSource;
import javax.transaction.TransactionManager;
import javax.transaction.TransactionSynchronizationRegistry;
import javax.validation.ValidationException;
import javax.validation.ValidatorFactory;

import org.apache.geronimo.connector.GeronimoBootstrapContext;
import org.apache.geronimo.connector.work.GeronimoWorkManager;
import org.apache.geronimo.connector.work.HintsContextHandler;
import org.apache.geronimo.connector.work.TransactionContextHandler;
import org.apache.geronimo.connector.work.WorkContextHandler;
import org.apache.geronimo.transaction.manager.GeronimoTransactionManager;
import org.apache.openejb.AppContext;
import org.apache.openejb.BeanContext;
import org.apache.openejb.BeanType;
import org.apache.openejb.ClassLoaderUtil;
import org.apache.openejb.Container;
import org.apache.openejb.DuplicateDeploymentIdException;
import org.apache.openejb.Injection;
import org.apache.openejb.JndiConstants;
import org.apache.openejb.MethodContext;
import org.apache.openejb.NoSuchApplicationException;
import org.apache.openejb.OpenEJB;
import org.apache.openejb.OpenEJBException;
import org.apache.openejb.OpenEJBRuntimeException;
import org.apache.openejb.UndeployException;
import org.apache.openejb.assembler.classic.event.AssemblerAfterApplicationCreated;
import org.apache.openejb.assembler.classic.event.AssemblerBeforeApplicationDestroyed;
import org.apache.openejb.assembler.classic.event.AssemblerCreated;
import org.apache.openejb.assembler.classic.event.AssemblerDestroyed;
import org.apache.openejb.cdi.CdiAppContextsService;
import org.apache.openejb.cdi.CdiBuilder;
import org.apache.openejb.cdi.CdiResourceInjectionService;
import org.apache.openejb.cdi.CdiScanner;
import org.apache.openejb.cdi.CustomELAdapter;
import org.apache.openejb.cdi.ManagedSecurityService;
import org.apache.openejb.cdi.OpenEJBTransactionService;
import org.apache.openejb.cdi.OptimizedLoaderService;
import org.apache.openejb.core.ConnectorReference;
import org.apache.openejb.core.CoreContainerSystem;
import org.apache.openejb.core.CoreUserTransaction;
import org.apache.openejb.core.JndiFactory;
import org.apache.openejb.core.SimpleTransactionSynchronizationRegistry;
import org.apache.openejb.core.TransactionSynchronizationRegistryWrapper;
import org.apache.openejb.core.WebContext;
import org.apache.openejb.core.ivm.naming.IvmContext;
import org.apache.openejb.core.ivm.naming.IvmJndiFactory;
import org.apache.openejb.core.security.SecurityContextHandler;
import org.apache.openejb.core.timer.EjbTimerServiceImpl;
import org.apache.openejb.core.timer.NullEjbTimerServiceImpl;
import org.apache.openejb.core.timer.ScheduleData;
import org.apache.openejb.core.timer.TimerStore;
import org.apache.openejb.core.transaction.JtaTransactionPolicyFactory;
import org.apache.openejb.core.transaction.SimpleBootstrapContext;
import org.apache.openejb.core.transaction.SimpleWorkManager;
import org.apache.openejb.core.transaction.TransactionPolicyFactory;
import org.apache.openejb.core.transaction.TransactionType;
import org.apache.openejb.javaagent.Agent;
import org.apache.openejb.loader.Options;
import org.apache.openejb.loader.SystemInstance;
import org.apache.openejb.monitoring.DynamicMBeanWrapper;
import org.apache.openejb.assembler.monitoring.JMXContainer;
import org.apache.openejb.monitoring.LocalMBeanServer;
import org.apache.openejb.monitoring.ObjectNameBuilder;
import org.apache.openejb.observer.Observes;
import org.apache.openejb.persistence.JtaEntityManagerRegistry;
import org.apache.openejb.persistence.PersistenceClassLoaderHandler;
import org.apache.openejb.resource.GeronimoConnectionManagerFactory;
import org.apache.openejb.spi.ApplicationServer;
import org.apache.openejb.spi.ContainerSystem;
import org.apache.openejb.spi.SecurityService;
import org.apache.openejb.util.AsmParameterNameLoader;
import org.apache.openejb.util.Contexts;
import org.apache.openejb.util.LogCategory;
import org.apache.openejb.util.Logger;
import org.apache.openejb.util.Messages;
import org.apache.openejb.util.OpenEJBErrorHandler;
import org.apache.openejb.util.References;
import org.apache.openejb.util.SafeToolkit;
import org.apache.openejb.util.proxy.ProxyFactory;
import org.apache.openejb.util.proxy.ProxyManager;
import org.apache.webbeans.config.WebBeansContext;
import org.apache.webbeans.spi.ContextsService;
import org.apache.webbeans.spi.LoaderService;
import org.apache.webbeans.spi.ResourceInjectionService;
import org.apache.webbeans.spi.ScannerService;
import org.apache.webbeans.spi.TransactionService;
import org.apache.webbeans.spi.adaptor.ELAdaptor;
import org.apache.xbean.finder.ResourceFinder;
import org.apache.xbean.recipe.ObjectRecipe;
import org.apache.xbean.recipe.Option;
import org.apache.xbean.recipe.UnsetPropertiesRecipe;

public class Assembler extends AssemblerTool implements org.apache.openejb.spi.Assembler, JndiConstants {

    static {
        AsmParameterNameLoader.install();
    }

    public static final String OPENEJB_URL_PKG_PREFIX = IvmContext.class.getPackage().getName();

    public static final Logger logger = Logger.getInstance(LogCategory.OPENEJB_STARTUP, Assembler.class);

    private static final String GLOBAL_UNIQUE_ID = "global";

    Messages messages = new Messages(Assembler.class.getPackage().getName());

    private final CoreContainerSystem containerSystem;
    private final PersistenceClassLoaderHandler persistenceClassLoaderHandler;
    private final JndiBuilder jndiBuilder;
    private TransactionManager transactionManager;
    private SecurityService securityService;
    protected OpenEjbConfigurationFactory configFactory;
    private final Map<String, AppInfo> deployedApplications = new HashMap<String, AppInfo> ();
    private final Set<String> moduleIds = new HashSet<String>();
    private final Set<ObjectName> containersObjectNames = new HashSet<ObjectName>();


    public org.apache.openejb.spi.ContainerSystem getContainerSystem() {
        return containerSystem;
    }

    public TransactionManager getTransactionManager() {
        return transactionManager;
    }

    public SecurityService getSecurityService() {
        return securityService;
    }

    public synchronized void addDeploymentListener(DeploymentListener deploymentListener) {
        logger.warning("DeploymentListener API is replaced by @Observes event");
        SystemInstance.get().addObserver(new DeploymentListenerObserver(deploymentListener));
    }

    public synchronized void removeDeploymentListener(DeploymentListener deploymentListener) {
        // the wrapping is done here to get the correct equals/hashcode methods
        SystemInstance.get().removeObserver(new DeploymentListenerObserver(deploymentListener));
    }

    protected SafeToolkit toolkit = SafeToolkit.getToolkit("Assembler");
    protected OpenEjbConfiguration config;

    public Assembler() {
        this(new IvmJndiFactory());
    }

    public Assembler(JndiFactory jndiFactory) {
        persistenceClassLoaderHandler = new PersistenceClassLoaderHandlerImpl();

        installNaming();

        SystemInstance system = SystemInstance.get();

        system.setComponent(org.apache.openejb.spi.Assembler.class, this);
        system.setComponent(Assembler.class, this);

        containerSystem = new CoreContainerSystem(jndiFactory);
        system.setComponent(ContainerSystem.class, containerSystem);

        jndiBuilder = new JndiBuilder(containerSystem.getJNDIContext());

        setConfiguration(new OpenEjbConfiguration());

        ApplicationServer appServer = system.getComponent(ApplicationServer.class);
        if (appServer == null) {
            system.setComponent(ApplicationServer.class, new org.apache.openejb.core.ServerFederation());
        }

        system.setComponent(EjbResolver.class, new EjbResolver(null, EjbResolver.Scope.GLOBAL));

        installExtensions();

        system.fireEvent(new AssemblerCreated());
    }

    private void installExtensions() {

        final ResourceFinder finder = new ResourceFinder("META-INF");

        try {
            final List<Class<?>> classes = finder.findAvailableClasses("org.apache.openejb.extension");
            for (Class<?> clazz : classes) {
                try {
                    final Object object = clazz.newInstance();
                    SystemInstance.get().addObserver(object);
                } catch (Throwable t) {
                    logger.error("Extension construction failed" + clazz.getName(), t);
                }
            }
        } catch (IOException e) {
            logger.error("Extension scanning of 'META-INF/org.apache.openejb.extension' files failed", e);
        }
    }

    private void setConfiguration(OpenEjbConfiguration config) {
        this.config = config;
        if (config.containerSystem == null) {
            config.containerSystem = new ContainerSystemInfo();
        }

        if (config.facilities == null) {
            config.facilities = new FacilitiesInfo();
        }

        SystemInstance.get().setComponent(OpenEjbConfiguration.class, this.config);
    }

    public void init(Properties props) throws OpenEJBException {
        this.props = new Properties(props);
        Options options = new Options(props, SystemInstance.get().getOptions());
        String className = options.get("openejb.configurator", "org.apache.openejb.config.ConfigurationFactory");

        configFactory = (OpenEjbConfigurationFactory) toolkit.newInstance(className);
        configFactory.init(props);
    }

    public static void installNaming() {
        if (SystemInstance.get().hasProperty("openejb.geronimo")) return;

        /* Add IntraVM JNDI service /////////////////////*/
        installNaming(OPENEJB_URL_PKG_PREFIX);
        /*\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\*/
    }

    public static void installNaming(String prefix) {
        installNaming(prefix, false);
    }

    public static void installNaming(String prefix, boolean clean) {
        Properties systemProperties = System.getProperties();
        synchronized (systemProperties) {
            String str = systemProperties.getProperty(Context.URL_PKG_PREFIXES);
            String naming = prefix;
            if (str == null || clean) {
                str = naming;
            } else if (str.indexOf(naming) == -1) {
                str = str + ":" + naming;
            }
            systemProperties.setProperty(Context.URL_PKG_PREFIXES, str);
        }
    }

    private static ThreadLocal<Map<String, Object>> context = new ThreadLocal<Map<String, Object>>();

    public static void setContext(Map<String, Object> map) {
        context.set(map);
    }

    public static Map<String, Object> getContext() {
        Map<String, Object> map = context.get();
        if (map == null) {
            map = new HashMap<String, Object>();
            context.set(map);
        }
        return map;
    }

    public void build() throws OpenEJBException {
        setContext(new HashMap<String, Object>());
        try {
            OpenEjbConfiguration config = getOpenEjbConfiguration();
            buildContainerSystem(config);
        } catch (OpenEJBException ae) {
            /* OpenEJBExceptions contain useful information and are debbugable.
             * Let the exception pass through to the top and be logged.
             */
            throw ae;
        } catch (Exception e) {
            /* General Exceptions at this level are too generic and difficult to debug.
             * These exceptions are considered unknown bugs and are fatal.
             * If you get an error at this level, please trap and handle the error
             * where it is most relevant.
             */
            OpenEJBErrorHandler.handleUnknownError(e, "Assembler");
            throw new OpenEJBException(e);
        } finally {
            context.set(null);
        }
    }

    protected OpenEjbConfiguration getOpenEjbConfiguration() throws OpenEJBException {
        OpenEjbConfiguration config = configFactory.getOpenEjbConfiguration();
        return config;
    }

    /////////////////////////////////////////////////////////////////////
    ////
    ////    Public Methods Used for Assembly
    ////
    /////////////////////////////////////////////////////////////////////

    /**
     * When given a complete OpenEjbConfiguration graph this method
     * will construct an entire container system and return a reference to that
     * container system, as ContainerSystem instance.
     * <p/>
     * This method leverage the other assemble and apply methods which
     * can be used independently.
     * <p/>
     * Assembles and returns the {@link org.apache.openejb.core.CoreContainerSystem} using the
     * information from the {@link OpenEjbConfiguration} object passed in.
     * <pre>
     * This method performs the following actions(in order):
     * <p/>
     * 1  Assembles ProxyFactory
     * 2  Assembles External JNDI Contexts
     * 3  Assembles TransactionService
     * 4  Assembles SecurityService
     * 5  Assembles ConnectionManagers
     * 6  Assembles Connectors
     * 7  Assembles Containers
     * 8  Assembles Applications
     * </pre>
     *
     * @param configInfo
     * @throws Exception if there was a problem constructing the ContainerSystem.
     * @throws Exception
     * @see OpenEjbConfiguration
     */
    public void buildContainerSystem(final OpenEjbConfiguration configInfo) throws Exception {

        for (ServiceInfo serviceInfo : configInfo.facilities.services) {
            createService(serviceInfo);
        }

        ContainerSystemInfo containerSystemInfo = configInfo.containerSystem;

        if (configInfo.facilities.intraVmServer != null) {
            createProxyFactory(configInfo.facilities.intraVmServer);
        }

        for (JndiContextInfo contextInfo : configInfo.facilities.remoteJndiContexts) {
            createExternalContext(contextInfo);
        }

        createTransactionManager(configInfo.facilities.transactionService);

        createSecurityService(configInfo.facilities.securityService);

        for (ResourceInfo resourceInfo : configInfo.facilities.resources) {
            createResource(resourceInfo);
        }

        // Containers
        for (ContainerInfo serviceInfo : containerSystemInfo.containers) {
            createContainer(serviceInfo);
        }

        for (AppInfo appInfo : containerSystemInfo.applications) {

            try {
                createApplication(appInfo, createAppClassLoader(appInfo));
            } catch (DuplicateDeploymentIdException e) {
                // already logged.
            } catch (Throwable e) {
                logger.error("appNotDeployed", e, appInfo.path);

                final DeploymentExceptionManager exceptionManager = SystemInstance.get().getComponent(DeploymentExceptionManager.class);
                if (exceptionManager != null && e instanceof Exception) {
                    exceptionManager.saveDeploymentException(appInfo, (Exception) e);
                }
            }
        }
    }

    public Collection<AppInfo> getDeployedApplications() {
        return new ArrayList<AppInfo>(deployedApplications.values());
    }

    public AppContext createApplication(EjbJarInfo ejbJar) throws NamingException, IOException, OpenEJBException {
        return createEjbJar(ejbJar);
    }

    public AppContext createEjbJar(EjbJarInfo ejbJar) throws NamingException, IOException, OpenEJBException {
        AppInfo appInfo = new AppInfo();
        appInfo.path = ejbJar.path;
        appInfo.appId = ejbJar.moduleName;
        appInfo.ejbJars.add(ejbJar);
        return createApplication(appInfo);
    }

    public AppContext createApplication(EjbJarInfo ejbJar, ClassLoader classLoader) throws NamingException, IOException, OpenEJBException {
        return createEjbJar(ejbJar, classLoader);
    }

    public AppContext createEjbJar(EjbJarInfo ejbJar, ClassLoader classLoader) throws NamingException, IOException, OpenEJBException {
        AppInfo appInfo = new AppInfo();
        appInfo.path = ejbJar.path;
        appInfo.appId = ejbJar.moduleName;
        appInfo.ejbJars.add(ejbJar);
        return createApplication(appInfo, classLoader);
    }

    public AppContext createClient(ClientInfo clientInfo) throws NamingException, IOException, OpenEJBException {
        AppInfo appInfo = new AppInfo();
        appInfo.path = clientInfo.path;
        appInfo.appId = clientInfo.moduleId;
        appInfo.clients.add(clientInfo);
        return createApplication(appInfo);
    }

    public AppContext createClient(ClientInfo clientInfo, ClassLoader classLoader) throws NamingException, IOException, OpenEJBException {
        AppInfo appInfo = new AppInfo();
        appInfo.path = clientInfo.path;
        appInfo.appId = clientInfo.moduleId;
        appInfo.clients.add(clientInfo);
        return createApplication(appInfo, classLoader);
    }

    public AppContext createConnector(ConnectorInfo connectorInfo) throws NamingException, IOException, OpenEJBException {
        AppInfo appInfo = new AppInfo();
        appInfo.path = connectorInfo.path;
        appInfo.appId = connectorInfo.moduleId;
        appInfo.connectors.add(connectorInfo);
        return createApplication(appInfo);
    }

    public AppContext createConnector(ConnectorInfo connectorInfo, ClassLoader classLoader) throws NamingException, IOException, OpenEJBException {
        AppInfo appInfo = new AppInfo();
        appInfo.path = connectorInfo.path;
        appInfo.appId = connectorInfo.moduleId;
        appInfo.connectors.add(connectorInfo);
        return createApplication(appInfo, classLoader);
    }

    public AppContext createWebApp(WebAppInfo webAppInfo) throws NamingException, IOException, OpenEJBException {
        AppInfo appInfo = new AppInfo();
        appInfo.path = webAppInfo.path;
        appInfo.appId = webAppInfo.moduleId;
        appInfo.webApps.add(webAppInfo);
        return createApplication(appInfo);
    }

    public AppContext createWebApp(WebAppInfo webAppInfo, ClassLoader classLoader) throws NamingException, IOException, OpenEJBException {
        AppInfo appInfo = new AppInfo();
        appInfo.path = webAppInfo.path;
        appInfo.appId = webAppInfo.moduleId;
        appInfo.webApps.add(webAppInfo);
        return createApplication(appInfo, classLoader);
    }

    public AppContext createApplication(AppInfo appInfo) throws OpenEJBException, IOException, NamingException {
        return createApplication(appInfo, createAppClassLoader(appInfo));
    }

    public AppContext createApplication(AppInfo appInfo, ClassLoader classLoader) throws OpenEJBException, IOException, NamingException {
        return createApplication(appInfo, classLoader, true);
    }

    public AppContext createApplication(AppInfo appInfo, ClassLoader classLoader, boolean start) throws OpenEJBException, IOException, NamingException {
        // The path is used in the UrlCache, command line deployer, JNDI name templates, tomcat integration and a few other places
        if (appInfo.appId == null) throw new IllegalArgumentException("AppInfo.appId cannot be null");
        if (appInfo.path == null) appInfo.path = appInfo.appId;

        logger.info("createApplication.start", appInfo.path);

//        try {
//            Thread.sleep(5000);
//        } catch (InterruptedException e) {
//            e.printStackTrace();
//            Thread.interrupted();
//        }

        // To start out, ensure we don't already have any beans deployed with duplicate IDs.  This
        // is a conflict we can't handle.
        List<String> used = new ArrayList<String>();
        for (EjbJarInfo ejbJarInfo : appInfo.ejbJars) {
            for (EnterpriseBeanInfo beanInfo : ejbJarInfo.enterpriseBeans) {
                if (containerSystem.getBeanContext(beanInfo.ejbDeploymentId) != null) {
                    used.add(beanInfo.ejbDeploymentId);
                }
            }
        }

        if (used.size() > 0) {
            String message = logger.error("createApplication.appFailedDuplicateIds", appInfo.path);
            for (String id : used) {
                logger.debug("createApplication.deploymentIdInUse", id);
                message += "\n    "+id;
            }
            throw new DuplicateDeploymentIdException(message);
        }

        //Construct the global and app jndi contexts for this app
        final InjectionBuilder injectionBuilder = new InjectionBuilder(classLoader);

        Set<Injection> injections = new HashSet<Injection>();
        injections.addAll(injectionBuilder.buildInjections(appInfo.globalJndiEnc));
        injections.addAll(injectionBuilder.buildInjections(appInfo.appJndiEnc));

        final JndiEncBuilder globalBuilder = new JndiEncBuilder(appInfo.globalJndiEnc, injections, appInfo.appId, null, GLOBAL_UNIQUE_ID, classLoader);
        final Map<String, Object> globalBindings = globalBuilder.buildBindings(JndiEncBuilder.JndiScope.global);
        final Context globalJndiContext = globalBuilder.build(globalBindings);

        final JndiEncBuilder appBuilder = new JndiEncBuilder(appInfo.appJndiEnc, injections, appInfo.appId, null, appInfo.appId, classLoader);
        final Map<String, Object> appBindings = appBuilder.buildBindings(JndiEncBuilder.JndiScope.app);
        final Context appJndiContext = appBuilder.build(appBindings);

        try {
            // Generate the cmp2/cmp1 concrete subclasses
            CmpJarBuilder cmpJarBuilder = new CmpJarBuilder(appInfo, classLoader);
            File generatedJar = cmpJarBuilder.getJarFile();
            if (generatedJar != null) {
                classLoader = ClassLoaderUtil.createClassLoader(appInfo.path, new URL []{generatedJar.toURI().toURL()}, classLoader);
            }

            final AppContext appContext = new AppContext(appInfo.appId, SystemInstance.get(), classLoader, globalJndiContext, appJndiContext, appInfo.standaloneModule);
            appContext.getInjections().addAll(injections);
            appContext.getBindings().putAll(globalBindings);
            appContext.getBindings().putAll(appBindings);

            containerSystem.addAppContext(appContext);

            final Context containerSystemContext = containerSystem.getJNDIContext();
            
            if (!SystemInstance.get().hasProperty("openejb.geronimo")) {
                // Bean Validation
                // ValidatorFactory needs to be put in the map sent to the entity manager factory
                // so it has to be constructed before
                final List<CommonInfoObject> vfs = new ArrayList<CommonInfoObject>();
                for (ClientInfo clientInfo : appInfo.clients) {
                    vfs.add(clientInfo);
                }
                for (ConnectorInfo connectorInfo : appInfo.connectors) {
                    vfs.add(connectorInfo);
                }
                for (EjbJarInfo ejbJarInfo : appInfo.ejbJars) {
                    vfs.add(ejbJarInfo);
                }
                for (WebAppInfo webAppInfo : appInfo.webApps) {
                    vfs.add(webAppInfo);
                }

                final Map<String, ValidatorFactory> validatorFactories = new HashMap<String, ValidatorFactory>();
                for (CommonInfoObject info : vfs) {
                    ValidatorFactory factory = null;
                    try {
                        factory = ValidatorBuilder.buildFactory(classLoader, info.validationInfo);
                    } catch (ValidationException ve) {
                        logger.warning("can't build the validation factory for module " + info.uniqueId, ve);
                    }
                    if (factory != null) {
                        validatorFactories.put(info.uniqueId, factory);
                    }
                }
                moduleIds.addAll(validatorFactories.keySet());

                // validators bindings
                for (Entry<String, ValidatorFactory> validatorFactory : validatorFactories.entrySet()) {
                    String id = validatorFactory.getKey();
                    ValidatorFactory factory = validatorFactory.getValue();
                    try {
                        containerSystemContext.bind(VALIDATOR_FACTORY_NAMING_CONTEXT + id, factory);
                        containerSystemContext.bind(VALIDATOR_NAMING_CONTEXT + id, factory.usingContext().getValidator());
                    } catch (NameAlreadyBoundException e) {
                        throw new OpenEJBException("ValidatorFactory already exists for module " + id, e);
                    } catch (Exception e) {
                        throw new OpenEJBException(e);
                    }
                }
            }
            
            // JPA - Persistence Units MUST be processed first since they will add ClassFileTransformers
            // to the class loader which must be added before any classes are loaded
            Map<String, String> units = new HashMap<String, String>();
            PersistenceBuilder persistenceBuilder = new PersistenceBuilder(persistenceClassLoaderHandler);
            for (PersistenceUnitInfo info : appInfo.persistenceUnits) {
                ReloadableEntityManagerFactory factory;
                try {
                    factory = persistenceBuilder.createEntityManagerFactory(info, classLoader);
                    containerSystem.getJNDIContext().bind(PERSISTENCE_UNIT_NAMING_CONTEXT + info.id, factory);
                    units.put(info.name, PERSISTENCE_UNIT_NAMING_CONTEXT + info.id);
                } catch (NameAlreadyBoundException e) {
                    throw new OpenEJBException("PersistenceUnit already deployed: " + info.persistenceUnitRootUrl);
                } catch (Exception e) {
                    throw new OpenEJBException(e);
                }

                factory.register();
            }

            // Connectors
            for (ConnectorInfo connector : appInfo.connectors) {
                ClassLoader oldClassLoader = Thread.currentThread().getContextClassLoader();
                Thread.currentThread().setContextClassLoader(classLoader);
                try {
                    // todo add undeployment code for these
                    if (connector.resourceAdapter != null) {
                        createResource(connector.resourceAdapter);
                    }
                    for (ResourceInfo outbound : connector.outbound) {
                        createResource(outbound);
                    }
                    for (MdbContainerInfo inbound : connector.inbound) {
                        createContainer(inbound);
                    }
                    for (ResourceInfo adminObject : connector.adminObject) {
                        createResource(adminObject);
                    }
                } finally {
                    Thread.currentThread().setContextClassLoader(oldClassLoader);
                }
            }

            List<BeanContext> allDeployments = new ArrayList<BeanContext>();

            // EJB
            EjbJarBuilder ejbJarBuilder = new EjbJarBuilder(props, appContext);
            for (EjbJarInfo ejbJar : appInfo.ejbJars) {
                HashMap<String, BeanContext> deployments = ejbJarBuilder.build(ejbJar, injections);

                JaccPermissionsBuilder jaccPermissionsBuilder = new JaccPermissionsBuilder();
                PolicyContext policyContext = jaccPermissionsBuilder.build(ejbJar, deployments);
                jaccPermissionsBuilder.install(policyContext);

                TransactionPolicyFactory transactionPolicyFactory = createTransactionPolicyFactory(ejbJar, classLoader);
                for (BeanContext beanContext : deployments.values()) {

                    beanContext.setTransactionPolicyFactory(transactionPolicyFactory);
                }

                MethodTransactionBuilder methodTransactionBuilder = new MethodTransactionBuilder();
                methodTransactionBuilder.build(deployments, ejbJar.methodTransactions);

                MethodConcurrencyBuilder methodConcurrencyBuilder = new MethodConcurrencyBuilder();
                methodConcurrencyBuilder.build(deployments, ejbJar.methodConcurrency);

                for (BeanContext beanContext : deployments.values()) {
                    containerSystem.addDeployment(beanContext);
                }

                //bind ejbs into global jndi
                jndiBuilder.build(ejbJar, deployments);

                // setup timers/asynchronous methods - must be after transaction attributes are set
                for (BeanContext beanContext : deployments.values()) {
                    if (beanContext.getComponentType() != BeanType.STATEFUL) {
                        Method ejbTimeout = beanContext.getEjbTimeout();
                        boolean timerServiceRequired = false;
                        if (ejbTimeout != null) {
                            // If user set the tx attribute to RequiresNew change it to Required so a new transaction is not started
                            if (beanContext.getTransactionType(ejbTimeout) == TransactionType.RequiresNew) {
                                beanContext.setMethodTransactionAttribute(ejbTimeout, TransactionType.Required);
                            }
                            timerServiceRequired = true;
                        }
                        for (Iterator<Map.Entry<Method, MethodContext>> it = beanContext.iteratorMethodContext(); it.hasNext();) {
                            Map.Entry<Method, MethodContext> entry = it.next();
                            MethodContext methodContext = entry.getValue();
                            if (methodContext.getSchedules().size() > 0) {
                                timerServiceRequired = true;
                                Method method = entry.getKey();
                                //TODO Need ?
                                if (beanContext.getTransactionType(method) == TransactionType.RequiresNew) {
                                    beanContext.setMethodTransactionAttribute(method, TransactionType.Required);
                                }
                            }
                        }
                        if (timerServiceRequired) {
                            // Create the timer
                            EjbTimerServiceImpl timerService = new EjbTimerServiceImpl(beanContext);
                            //Load auto-start timers
                            TimerStore timerStore = timerService.getTimerStore();
                            for (Iterator<Map.Entry<Method, MethodContext>> it = beanContext.iteratorMethodContext(); it.hasNext();) {
                                Map.Entry<Method, MethodContext> entry = it.next();
                                MethodContext methodContext = entry.getValue();
                                for(ScheduleData scheduleData : methodContext.getSchedules()) {
                                    timerStore.createCalendarTimer(timerService, (String) beanContext.getDeploymentID(), null, entry.getKey(), scheduleData.getExpression(), scheduleData.getConfig());
                                }
                            }
                            beanContext.setEjbTimerService(timerService);
                        } else {
                            beanContext.setEjbTimerService(new NullEjbTimerServiceImpl());
                        }
                    }
                    //set asynchronous methods transaction
                    //TODO ???
                    for (Iterator<Entry<Method, MethodContext>> it = beanContext.iteratorMethodContext(); it.hasNext();) {
                        Entry<Method, MethodContext> entry = it.next();
                        if (entry.getValue().isAsynchronous() && beanContext.getTransactionType(entry.getKey()) == TransactionType.RequiresNew) {
                            beanContext.setMethodTransactionAttribute(entry.getKey(), TransactionType.Required);
                        }
                    }
                }
                // process application exceptions
                for (ApplicationExceptionInfo exceptionInfo : ejbJar.applicationException) {
                    try {
                        Class exceptionClass = classLoader.loadClass(exceptionInfo.exceptionClass);
                        for (BeanContext beanContext : deployments.values()) {
                            beanContext.addApplicationException(exceptionClass, exceptionInfo.rollback, exceptionInfo.inherited);
                        }
                    } catch (ClassNotFoundException e) {
                        logger.error("createApplication.invalidClass", e, exceptionInfo.exceptionClass, e.getMessage());
                    }
                }

                allDeployments.addAll(deployments.values());
            }

            allDeployments = sort(allDeployments);

            appContext.getBeanContexts().addAll(allDeployments);

            new CdiBuilder().build(appInfo, appContext, allDeployments);

            ensureWebBeansContext(appContext);

            appJndiContext.bind("app/BeanManager", appContext.getBeanManager());
            appContext.getBindings().put("app/BeanManager", appContext.getBeanManager());

            // now that everything is configured, deploy to the container
            if (start) {
                // deploy
                for (BeanContext deployment : allDeployments) {
                    try {
                        Container container = deployment.getContainer();
                        container.deploy(deployment);
                        if (!((String) deployment.getDeploymentID()).endsWith(".Comp")
                                && !deployment.isHidden()) {
                            logger.info("createApplication.createdEjb", deployment.getDeploymentID(), deployment.getEjbName(), container.getContainerID());
                        }
                        if (logger.isDebugEnabled()) {
                            for (Map.Entry<Object, Object> entry : deployment.getProperties().entrySet()) {
                                logger.info("createApplication.createdEjb.property", deployment.getEjbName(), entry.getKey(), entry.getValue());
                            }
                        }
                    } catch (Throwable t) {
                        throw new OpenEJBException("Error deploying '"+deployment.getEjbName()+"'.  Exception: "+t.getClass()+": "+t.getMessage(), t);
                    }
                }

                // start
                for (BeanContext deployment : allDeployments) {
                    try {
                        Container container = deployment.getContainer();
                        container.start(deployment);
                        if (!((String) deployment.getDeploymentID()).endsWith(".Comp")
                                && !deployment.isHidden()) {
                            logger.info("createApplication.startedEjb", deployment.getDeploymentID(), deployment.getEjbName(), container.getContainerID());
                        }
                    } catch (Throwable t) {
                        throw new OpenEJBException("Error starting '"+deployment.getEjbName()+"'.  Exception: "+t.getClass()+": "+t.getMessage(), t);
                    }
                }
            }

            // App Client
            for (ClientInfo clientInfo : appInfo.clients) {
                // determine the injections
                List<Injection> clientInjections = injectionBuilder.buildInjections(clientInfo.jndiEnc);

                // build the enc
                JndiEncBuilder jndiEncBuilder = new JndiEncBuilder(clientInfo.jndiEnc, clientInjections, "Bean", clientInfo.moduleId, null, clientInfo.uniqueId, classLoader);
                // if there is at least a remote client classes
                // or if there is no local client classes
                // then, we can set the client flag
                if ((clientInfo.remoteClients.size() > 0) || (clientInfo.localClients.size() == 0)) {
                    jndiEncBuilder.setClient(true);

                }
                jndiEncBuilder.setUseCrossClassLoaderRef(false);
                Context context = jndiEncBuilder.build(JndiEncBuilder.JndiScope.comp);

//                Debug.printContext(context);

                containerSystemContext.bind("openejb/client/" + clientInfo.moduleId, context);

                if (clientInfo.path != null) {
                    context.bind("info/path", clientInfo.path);
                }
                if (clientInfo.mainClass != null) {
                    context.bind("info/mainClass", clientInfo.mainClass);
                }
                if (clientInfo.callbackHandler != null) {
                    context.bind("info/callbackHandler", clientInfo.callbackHandler);
                }
                context.bind("info/injections", clientInjections);

                for (String clientClassName : clientInfo.remoteClients) {
                    containerSystemContext.bind("openejb/client/" + clientClassName, clientInfo.moduleId);
                }

                for (String clientClassName : clientInfo.localClients) {
                    containerSystemContext.bind("openejb/client/" + clientClassName, clientInfo.moduleId);
                    logger.getChildLogger("client").info("createApplication.createLocalClient", clientClassName, clientInfo.moduleId);
                }
            }

            SystemInstance systemInstance = SystemInstance.get();

            // WebApp

            WebAppBuilder webAppBuilder = systemInstance.getComponent(WebAppBuilder.class);
            if (webAppBuilder != null) {
                webAppBuilder.deployWebApps(appInfo, classLoader);
            }

            if (start) {
                EjbResolver globalEjbResolver = systemInstance.getComponent(EjbResolver.class);
                globalEjbResolver.addAll(appInfo.ejbJars);
            }

            // bind all global values on global context
            for (Map.Entry<String, Object> value : appContext.getBindings().entrySet()) {
                String path = value.getKey();
                if (!path.startsWith("global") || path.equalsIgnoreCase("global/dummy")) { // dummy bound for each app
                    continue;
                }

                // a bit weird but just to be consistent if user doesn't lookup directly the resource
                Context lastContext = Contexts.createSubcontexts(containerSystemContext, path);
                try {
                    lastContext.bind(path.substring(path.lastIndexOf("/") + 1, path.length()), value.getValue());
                } catch (NameAlreadyBoundException nabe) {
                    nabe.printStackTrace();
                }
                containerSystemContext.rebind(path, value.getValue());
            }

            // deploy MBeans
            for (String mbean : appInfo.mbeans) {
                deployMBean(appContext.getBeanManager(), classLoader, mbean, appInfo.jmx, appInfo.appId);
            }
            for (EjbJarInfo ejbJarInfo : appInfo.ejbJars) {
                for (String mbean : ejbJarInfo.mbeans) {
                    deployMBean(appContext.getBeanManager(), classLoader, mbean, appInfo.jmx, ejbJarInfo.moduleName);
                }
            }
            for (ConnectorInfo connectorInfo : appInfo.connectors) {
                for (String mbean : connectorInfo.mbeans) {
                    deployMBean(appContext.getBeanManager(), classLoader, mbean, appInfo.jmx, appInfo.appId + ".add-lib");
                }
            }


            logger.info("createApplication.success", appInfo.path);

            deployedApplications.put(appInfo.path, appInfo);
            systemInstance.fireEvent(new AssemblerAfterApplicationCreated(appInfo));

            return appContext;
        } catch (ValidationException ve) {
            throw ve;
        } catch (Throwable t) {
            try {
                destroyApplication(appInfo);
            } catch (Exception e1) {
                logger.debug("createApplication.undeployFailed", e1, appInfo.path);
            }
            throw new OpenEJBException(messages.format("createApplication.failed", appInfo.path), t);
        }
    }

    private static void deployMBean(final BeanManager bm, final ClassLoader cl, final String mbeanClass, final Properties appMbeans, final String id) {
        final Class<?> clazz;
        try {
            clazz = cl.loadClass(mbeanClass);
        } catch (ClassNotFoundException e) {
            throw new OpenEJBRuntimeException(e);
        }
        final Set<Bean<?>> beans = bm.getBeans(clazz);
        final Bean bean = bm.resolve(beans);
        final Object instance;
        if (bean == null) {
            try {
                instance = clazz.newInstance();
            } catch (InstantiationException e) {
                logger.error("the mbean " + mbeanClass + " can't be registered because it can't be instantiated", e);
                return;
            } catch (IllegalAccessException e) {
                logger.error("the mbean " + mbeanClass + " can't be registered because it can't be accessed", e);
                return;
            }
        } else {
            instance = bm.getReference(bean, clazz, bm.createCreationalContext(bean));
        }

        final MBeanServer server = LocalMBeanServer.get();
        try {
            final ObjectName leaf =  new ObjectNameBuilder("openejb.user.mbeans")
                .set("application", id)
                .set("group", clazz.getPackage().getName())
                .set("name", clazz.getSimpleName())
                .build();

            server.registerMBean(new DynamicMBeanWrapper(instance), leaf);
            appMbeans.put(mbeanClass, leaf.getCanonicalName());
            logger.info("Deployed MBean(" + leaf.getCanonicalName() + ")");
        } catch (Exception e) {
            logger.error("the mbean " + mbeanClass + " can't be registered", e);
        }
    }

    private void ensureWebBeansContext(AppContext appContext) {
        WebBeansContext webBeansContext = appContext.get(WebBeansContext.class);
        if (webBeansContext == null) webBeansContext = appContext.getWebBeansContext();
        if (webBeansContext == null) {

            final Map<Class<?>, Object> services = new HashMap<Class<?>, Object>();

            services.put(AppContext.class, appContext);
            services.put(TransactionService.class, new OpenEJBTransactionService());
            services.put(ContextsService.class, new CdiAppContextsService(true));
            services.put(ResourceInjectionService.class, new CdiResourceInjectionService());
            services.put(ScannerService.class, new CdiScanner());
            services.put(ELAdaptor.class, new CustomELAdapter(appContext));
            services.put(LoaderService.class, new OptimizedLoaderService());
            final Properties properties = new Properties();
            properties.setProperty(org.apache.webbeans.spi.SecurityService.class.getName(), ManagedSecurityService.class.getName());
            webBeansContext = new WebBeansContext(services, properties);
            appContext.setCdiEnabled(false);
        }

        appContext.set(WebBeansContext.class, webBeansContext);
        appContext.setWebBeansContext(webBeansContext);
    }

    private TransactionPolicyFactory createTransactionPolicyFactory(EjbJarInfo ejbJar, ClassLoader classLoader) {
        TransactionPolicyFactory factory = null;

        Object value = ejbJar.properties.get(TransactionPolicyFactory.class.getName());
        if (value instanceof TransactionPolicyFactory) {
            factory = (TransactionPolicyFactory) value;
        } else if (value instanceof String) {
            try {
                String[] parts = ((String)value).split(":", 2);

                ResourceFinder finder = new ResourceFinder("META-INF", classLoader);
                Map<String,Class<? extends TransactionPolicyFactory>> plugins = finder.mapAvailableImplementations(TransactionPolicyFactory.class);
                Class<? extends TransactionPolicyFactory> clazz = plugins.get(parts[0]);
                if (clazz != null) {
                    if (parts.length == 1) {
                        factory = clazz.getConstructor(String.class).newInstance(parts[1]);
                    } else {
                        factory = clazz.newInstance();
                    }
                }
            } catch (Exception ignored) {
                // couldn't determine the plugins, which isn't fatal
            }
        }

        if (factory == null) {
             factory = new JtaTransactionPolicyFactory(transactionManager);
        }
        return factory;
    }

    private static List<BeanContext> sort(List<BeanContext> deployments) {
        // Sort all the singletons to the back of the list.  We want to make sure
        // all non-singletons are created first so that if a singleton refers to them
        // they are available.
        Collections.sort(deployments, new Comparator<BeanContext>() {
            public int compare(BeanContext a, BeanContext b) {
                int aa = (a.getComponentType() == BeanType.SINGLETON) ? 1 : 0;
                int bb = (b.getComponentType() == BeanType.SINGLETON) ? 1 : 0;
                return aa - bb;
            }
        });

        // Sort all the beans with references to the back of the list.  Beans
        // without references to ther beans will be deployed first.
        deployments = References.sort(deployments, new References.Visitor<BeanContext>() {
            public String getName(BeanContext t) {
                return (String) t.getDeploymentID();
            }

            public Set<String> getReferences(BeanContext t) {
                return t.getDependsOn();
            }
        });

        // Now Sort all the MDBs to the back of the list.  The Resource Adapter
        // may attempt to use the MDB on endpointActivation and the MDB may have
        // references to other ejbs that would need to be available first.
        Collections.sort(deployments, new Comparator<BeanContext>(){
            public int compare(BeanContext a, BeanContext b) {
                int aa = (a.getComponentType() == BeanType.MESSAGE_DRIVEN) ? 1 : 0;
                int bb = (b.getComponentType() == BeanType.MESSAGE_DRIVEN) ? 1 : 0;
                return aa - bb;
            }
        });

        return deployments;
    }

    public synchronized void destroy() {

        try {
            EjbTimerServiceImpl.shutdown();
        } catch (Exception e) {
            logger.warning("Unable to shutdown scheduler", e);
        }

        logger.debug("Undeploying Applications");
        Assembler assembler = this;
        for (AppInfo appInfo : assembler.getDeployedApplications()) {
            try {
                assembler.destroyApplication(appInfo.path);
            } catch (UndeployException e) {
                logger.error("Undeployment failed: " + appInfo.path, e);
            } catch (NoSuchApplicationException e) {
            }
        }

        final Iterator<ObjectName> it = containersObjectNames.iterator();
        final MBeanServer server = LocalMBeanServer.get();
        while (it.hasNext()) {
            try {
                server.unregisterMBean(it.next());
            } catch (Exception ignored) {
                // no-op
            }
            it.remove();
        }

        NamingEnumeration<Binding> namingEnumeration = null;
        try {
            namingEnumeration = containerSystem.getJNDIContext().listBindings("openejb/Resource");
        } catch (NamingException ignored) {
            // no resource adapters were created
        }
        while (namingEnumeration != null && namingEnumeration.hasMoreElements()) {
            Binding binding = namingEnumeration.nextElement();
            Object object = binding.getObject();
            destroyResource(binding.getName(), binding.getClassName(), object);
        }

        SystemInstance.get().removeComponent(OpenEjbConfiguration.class);
        SystemInstance.get().removeComponent(JtaEntityManagerRegistry.class);
        SystemInstance.get().removeComponent(TransactionSynchronizationRegistry.class);
        SystemInstance.get().removeComponent(EjbResolver.class);
        SystemInstance.get().fireEvent(new AssemblerDestroyed());
        SystemInstance.reset();
    }

    private void destroyResource(final String name, final String className, final Object object) {
        if (object instanceof ResourceAdapter) {
            ResourceAdapter resourceAdapter = (ResourceAdapter) object;
            try {
                logger.info("Stopping ResourceAdapter: " + name);

                if (logger.isDebugEnabled()) {
                    logger.debug("Stopping ResourceAdapter: " + className);
                }

                resourceAdapter.stop();
            } catch (Throwable t) {
                logger.fatal("ResourceAdapter Shutdown Failed: " + name, t);
            }
        } else if (object instanceof org.apache.commons.dbcp.BasicDataSource) {
            logger.info("Closing DataSource: " + name);

            try {
                ((org.apache.commons.dbcp.BasicDataSource) object).close();
            } catch (Throwable t) {
                //Ignore
            }

        } else if (logger.isDebugEnabled()) {
            logger.debug("Not processing resource on destroy: " + className);
        }
    }

    public synchronized void destroyApplication(String filePath) throws UndeployException, NoSuchApplicationException {
        AppInfo appInfo = deployedApplications.remove(filePath);
        if (appInfo == null) {
            throw new NoSuchApplicationException(filePath);
        }
        destroyApplication(appInfo);
    }

    public synchronized void destroyApplication(AppContext appContext) throws UndeployException {
        AppInfo appInfo = deployedApplications.remove(appContext.getId());
        if (appInfo == null) {
            throw new IllegalStateException(String.format("Cannot find AppInfo for app: %s", appContext.getId()));
        }
        destroyApplication(appInfo);
    }

    public synchronized void destroyApplication(AppInfo appInfo) throws UndeployException {
        deployedApplications.remove(appInfo.path);
        logger.info("destroyApplication.start", appInfo.path);

        SystemInstance.get().fireEvent(new AssemblerBeforeApplicationDestroyed(appInfo));

        final AppContext appContext = containerSystem.getAppContext(appInfo.appId);

        for (Map.Entry<String, Object> value : appContext.getBindings().entrySet()) {
            String path = value.getKey();
            if (path.startsWith("global")) {
                path = "java:" + path;
            }
            if (!path.startsWith("java:global")) {
                continue;
            }

            try {
                containerSystem.getJNDIContext().unbind(path);
            } catch (NamingException ignored) {
                // no-op
            }
        }
        try {
            containerSystem.getJNDIContext().unbind("java:global");
        } catch (NamingException ignored) {
            // no-op
        }

        EjbResolver globalResolver = new EjbResolver(null, EjbResolver.Scope.GLOBAL);
        for (AppInfo info : deployedApplications.values()) {
            globalResolver.addAll(info.ejbJars);
        }
        SystemInstance.get().setComponent(EjbResolver.class, globalResolver);


        Context globalContext = containerSystem.getJNDIContext();
        UndeployException undeployException = new UndeployException(messages.format("destroyApplication.failed", appInfo.path));

        WebAppBuilder webAppBuilder = SystemInstance.get().getComponent(WebAppBuilder.class);
        if (webAppBuilder != null) {
            try {
                webAppBuilder.undeployWebApps(appInfo);
            } catch (Exception e) {
                undeployException.getCauses().add(new Exception("App: " + appInfo.path + ": " + e.getMessage(), e));
            }
        }

        // get all of the ejb deployments
        List<BeanContext> deployments = new ArrayList<BeanContext>();
        for (EjbJarInfo ejbJarInfo : appInfo.ejbJars) {
            for (EnterpriseBeanInfo beanInfo : ejbJarInfo.enterpriseBeans) {
                String deploymentId = beanInfo.ejbDeploymentId;
                BeanContext beanContext = containerSystem.getBeanContext(deploymentId);
                if (beanContext == null) {
                    undeployException.getCauses().add(new Exception("deployment not found: " + deploymentId));
                } else {
                    deployments.add(beanContext);
                }
            }
        }

        // Just as with startup we need to get things in an
        // order that respects the singleton @DependsOn information
        // Theoreticlly if a Singleton depends on something in its
        // @PostConstruct, it can depend on it in its @PreDestroy.
        // Therefore we want to make sure that if A dependsOn B,
        // that we destroy A first then B so that B will still be
        // usable in the @PreDestroy method of A.

        // Sort them into the original starting order
        deployments = sort(deployments);
        // reverse that to get the stopping order
        Collections.reverse(deployments);

        // stop
        for (BeanContext deployment : deployments) {
            String deploymentID = deployment.getDeploymentID() + "";
            try {
                Container container = deployment.getContainer();
                container.stop(deployment);
            } catch (Throwable t) {
                undeployException.getCauses().add(new Exception("bean: " + deploymentID + ": " + t.getMessage(), t));
            }
        }

        // undeploy
        for (BeanContext bean : deployments) {
            String deploymentID = bean.getDeploymentID() + "";
            try {
                Container container = bean.getContainer();
                container.undeploy(bean);
                bean.setContainer(null);
            } catch (Throwable t) {
                undeployException.getCauses().add(new Exception("bean: " + deploymentID + ": " + t.getMessage(), t));
            } finally {
                bean.setDestroyed(true);
            }
        }

        // get the client ids
        List<String> clientIds = new ArrayList<String>();
        for (ClientInfo clientInfo : appInfo.clients) {
            clientIds.add(clientInfo.moduleId);
            for (String className : clientInfo.localClients) {
                clientIds.add(className);
            }
            for (String className : clientInfo.remoteClients) {
                clientIds.add(className);
            }
        }

        if (appContext != null) for (WebContext webContext : appContext.getWebContexts()) {
            containerSystem.removeWebContext(webContext);
        }

    // Clear out naming for all components first
        for (BeanContext deployment : deployments) {
            String deploymentID = deployment.getDeploymentID() + "";
            try {
                containerSystem.removeBeanContext(deployment);
            } catch (Throwable t) {
                undeployException.getCauses().add(new Exception(deploymentID, t));
            }

            JndiBuilder.Bindings bindings = deployment.get(JndiBuilder.Bindings.class);
            if (bindings != null) for (String name : bindings.getBindings()) {
                try {
                    globalContext.unbind(name);
                } catch (Throwable t) {
                    undeployException.getCauses().add(new Exception("bean: " + deploymentID + ": " + t.getMessage(), t));
                }
            }
        }

        for (String sId : moduleIds) {
            try {
                globalContext.unbind(VALIDATOR_FACTORY_NAMING_CONTEXT + sId);
                globalContext.unbind(VALIDATOR_NAMING_CONTEXT + sId);
            } catch (NamingException e) {
                undeployException.getCauses().add(new Exception("validator: " + sId + ": " + e.getMessage(), e));
            }
        }
        moduleIds.clear();

        try {
            if (globalContext instanceof IvmContext) {
                IvmContext ivmContext = (IvmContext) globalContext;
                ivmContext.prune("openejb/Deployment");
                ivmContext.prune("openejb/local");
                ivmContext.prune("openejb/remote");
                ivmContext.prune("openejb/global");
            }
        } catch (NamingException e) {
            undeployException.getCauses().add(new Exception("Unable to prune openejb/Deployments and openejb/local namespaces, this could cause future deployments to fail.", e));
        }

        deployments.clear();

        for (String clientId : clientIds) {
            try {
                globalContext.unbind("/openejb/client/" + clientId);
            } catch (Throwable t) {
                undeployException.getCauses().add(new Exception("client: " + clientId + ": " + t.getMessage(), t));
            }
        }

        // mbeans
        MBeanServer server = LocalMBeanServer.get();
        for (Object objectName : appInfo.jmx.values()) {
            try {
              ObjectName on = new ObjectName((String) objectName);
              if (server.isRegistered(on)) {
                      server.unregisterMBean(on);
              }
            } catch (InstanceNotFoundException e) {
                logger.warning("can't unregister " + objectName + " because the mbean was not found", e);
            } catch (MBeanRegistrationException e) {
                logger.warning("can't unregister " + objectName, e);
            } catch (MalformedObjectNameException mone) {
                logger.warning("can't unregister because the ObjectName is malformed: " + objectName, mone);
            }
        }

        final Context ic = containerSystem.getJNDIContext();
        for (String id : appInfo.resourceIds) {
            final String name = OPENEJB_RESOURCE_JNDI_PREFIX + id;
            try {
                final Object object = ic.lookup(name);
                final String clazz;
                if (object == null) { // should it be possible?
                    clazz = "?";
                } else {
                    clazz = object.getClass().getName();
                }
                destroyResource(id, clazz, object);
                ic.unbind(name);
            } catch (NamingException e) {
                logger.warning("can't unbind resource '{0}'", id);
            }
        }

        for (PersistenceUnitInfo unitInfo : appInfo.persistenceUnits) {
            try {
                Object object = globalContext.lookup(PERSISTENCE_UNIT_NAMING_CONTEXT + unitInfo.id);
                globalContext.unbind(PERSISTENCE_UNIT_NAMING_CONTEXT + unitInfo.id);

                // close EMF so all resources are released
                ReloadableEntityManagerFactory remf = ((ReloadableEntityManagerFactory) object);
                remf.close();
                persistenceClassLoaderHandler.destroy(unitInfo.id);
                remf.unregister();
            } catch (Throwable t) {
                undeployException.getCauses().add(new Exception("persistence-unit: " + unitInfo.id + ": " + t.getMessage(), t));
            }
        }

        containerSystem.removeAppContext(appInfo.appId);

        ClassLoaderUtil.destroyClassLoader(appInfo.path);

        if (undeployException.getCauses().size() > 0) {
            throw undeployException;
        }

        logger.debug("destroyApplication.success", appInfo.path);
    }

    public ClassLoader createAppClassLoader(AppInfo appInfo) throws OpenEJBException, IOException {
        List<URL> jars = new ArrayList<URL>();
        for (EjbJarInfo info : appInfo.ejbJars) {
            if (info.path != null) jars.add(toUrl(info.path));
        }
        for (ClientInfo info : appInfo.clients) {
            if (info.path != null) jars.add(toUrl(info.path));
        }
        for (ConnectorInfo info : appInfo.connectors) {
            for (String jarPath : info.libs) {
                jars.add(toUrl(jarPath));
            }
        }
        for (String jarPath : appInfo.libs) {
            jars.add(toUrl(jarPath));
        }

        // Create the class loader
        ClassLoader classLoader = ClassLoaderUtil.createClassLoader(appInfo.path, jars.toArray(new URL[jars.size()]), OpenEJB.class.getClassLoader());
        return classLoader;
    }

    public void createExternalContext(JndiContextInfo contextInfo) throws OpenEJBException {
        logger.getChildLogger("service").info("createService", contextInfo.service, contextInfo.id, contextInfo.className);

        final InitialContext initialContext;
        try {
            initialContext = new InitialContext(contextInfo.properties);
        } catch (NamingException ne) {
            throw new OpenEJBException(String.format("JndiProvider(id=\"%s\") could not be created.  Failed to create the InitialContext using the supplied properties", contextInfo.id), ne);
        }

        try {
            containerSystem.getJNDIContext().bind("openejb/remote_jndi_contexts/" + contextInfo.id, initialContext);
        } catch (NamingException e) {
            throw new OpenEJBException("Cannot bind " + contextInfo.service + " with id " + contextInfo.id, e);
        }

        // Update the config tree
        config.facilities.remoteJndiContexts.add(contextInfo);

        logger.getChildLogger("service").debug("createService.success", contextInfo.service, contextInfo.id, contextInfo.className);
    }

    public void createContainer(ContainerInfo serviceInfo) throws OpenEJBException {

        ObjectRecipe serviceRecipe = createRecipe(serviceInfo);

        serviceRecipe.setProperty("id", serviceInfo.id);
        serviceRecipe.setProperty("transactionManager", props.get(TransactionManager.class.getName()));
        serviceRecipe.setProperty("securityService", props.get(SecurityService.class.getName()));
        serviceRecipe.setProperty("properties", new UnsetPropertiesRecipe());

        // MDB container has a resource adapter string name that
        // must be replaced with the real resource adapter instance
        replaceResourceAdapterProperty(serviceRecipe);

        Object service = serviceRecipe.create();

        logUnusedProperties(serviceRecipe, serviceInfo);

        Class interfce = serviceInterfaces.get(serviceInfo.service);
        checkImplementation(interfce, service.getClass(), serviceInfo.service, serviceInfo.id);

        bindService(serviceInfo, service);

        setSystemInstanceComponent(interfce, service);

        props.put(interfce.getName(), service);
        props.put(serviceInfo.service, service);
        props.put(serviceInfo.id, service);

        containerSystem.addContainer(serviceInfo.id, (Container) service);

        // Update the config tree
        config.containerSystem.containers.add(serviceInfo);

        logger.getChildLogger("service").debug("createService.success", serviceInfo.service, serviceInfo.id, serviceInfo.className);

        if (service instanceof Container) {
            final ObjectName objectName = ObjectNameBuilder.uniqueName("containers", serviceInfo.id, service);
            try {
                LocalMBeanServer.get().registerMBean(new DynamicMBeanWrapper(new JMXContainer(serviceInfo, (Container) service)), objectName);
                containersObjectNames.add(objectName);
            } catch (Exception e) {
                // no-op
            } catch (NoClassDefFoundError ncdfe) { // OSGi
                // no-op
            }
        }
    }

    private void bindService(ServiceInfo serviceInfo, Object service) throws OpenEJBException {
        try {
            this.containerSystem.getJNDIContext().bind(JAVA_OPENEJB_NAMING_CONTEXT + serviceInfo.service + "/" + serviceInfo.id, service);
        } catch (NamingException e) {
            throw new OpenEJBException(messages.format("assembler.cannotBindServiceWithId", serviceInfo.service, serviceInfo.id), e);
        }
    }

    public void removeContainer(String containerId) {
        containerSystem.removeContainer(containerId);

        // Update the config tree
        for (Iterator<ContainerInfo> iterator = config.containerSystem.containers.iterator(); iterator.hasNext();) {
            ContainerInfo containerInfo = iterator.next();
            if (containerInfo.id.equals(containerId)) {
                iterator.remove();
                try {
                    this.containerSystem.getJNDIContext().unbind(JAVA_OPENEJB_NAMING_CONTEXT + containerInfo.service + "/" + containerInfo.id);
                } catch (Exception e) {
                    logger.error("removeContainer.unbindFailed", containerId);
                }
            }
        }
    }

    public void createService(ServiceInfo serviceInfo) throws OpenEJBException {
        ObjectRecipe serviceRecipe = createRecipe(serviceInfo);

        Object service = serviceRecipe.create();
        SystemInstance.get().addObserver(service);

        Class serviceClass = service.getClass();

        logUnusedProperties(serviceRecipe, serviceInfo);

        getContext().put(serviceClass.getName(), service);

        props.put(serviceClass.getName(), service);
        props.put(serviceInfo.service, service);
        props.put(serviceInfo.id, service);

        config.facilities.services.add(serviceInfo);

        logger.getChildLogger("service").debug("createService.success", serviceInfo.service, serviceInfo.id, serviceInfo.className);
    }

    public void createProxyFactory(ProxyFactoryInfo serviceInfo) throws OpenEJBException {

        ObjectRecipe serviceRecipe = createRecipe(serviceInfo);

        Object service = serviceRecipe.create();

        logUnusedProperties(serviceRecipe, serviceInfo);

        Class interfce = serviceInterfaces.get(serviceInfo.service);
        checkImplementation(interfce, service.getClass(), serviceInfo.service, serviceInfo.id);

        ProxyManager.registerFactory(serviceInfo.id, (ProxyFactory) service);
        ProxyManager.setDefaultFactory(serviceInfo.id);

        bindService(serviceInfo, service);

        setSystemInstanceComponent(interfce, service);

        getContext().put(interfce.getName(), service);

        props.put(interfce.getName(), service);
        props.put(serviceInfo.service, service);
        props.put(serviceInfo.id, service);

        // Update the config tree
        config.facilities.intraVmServer = serviceInfo;

        logger.getChildLogger("service").debug("createService.success", serviceInfo.service, serviceInfo.id, serviceInfo.className);
    }

    private void replaceResourceAdapterProperty(ObjectRecipe serviceRecipe) throws OpenEJBException {
        Object resourceAdapterId = serviceRecipe.getProperty("ResourceAdapter");
        if (resourceAdapterId instanceof String)  {
            String id = (String) resourceAdapterId;
            id = id.trim();

            Object resourceAdapter = null;
            try {
                resourceAdapter = containerSystem.getJNDIContext().lookup("openejb/Resource/" + id);
            } catch (NamingException e) {
                // handled below
            }

            if (resourceAdapter == null) {
                throw new OpenEJBException("No existing resource adapter defined with id '" + id + "'.");
            }
            if (!(resourceAdapter instanceof ResourceAdapter)) {
                throw new OpenEJBException(messages.format("assembler.resourceAdapterNotResourceAdapter", id, resourceAdapter.getClass()));
            }
            serviceRecipe.setProperty("ResourceAdapter", resourceAdapter);
        }
    }

    public void createResource(ResourceInfo serviceInfo) throws OpenEJBException {
        ObjectRecipe serviceRecipe = createRecipe(serviceInfo);
        serviceRecipe.setProperty("transactionManager", transactionManager);
        serviceRecipe.setProperty("ServiceId", serviceInfo.id);
        serviceRecipe.setProperty("properties", new UnsetPropertiesRecipe());

        replaceResourceAdapterProperty(serviceRecipe);

        Object service = serviceRecipe.create();


        // Java Connector spec ResourceAdapters and ManagedConnectionFactories need special activation
        if (service instanceof ResourceAdapter) {
            ResourceAdapter resourceAdapter = (ResourceAdapter) service;

            // Create a thead pool for work manager
            int threadPoolSize = getIntProperty(serviceInfo.properties, "threadPoolSize", 30);
            Executor threadPool;
            if (threadPoolSize <= 0) {
                threadPool = Executors.newCachedThreadPool(new ResourceAdapterThreadFactory(serviceInfo.id));
            } else {
                threadPool = Executors.newFixedThreadPool(threadPoolSize, new ResourceAdapterThreadFactory(serviceInfo.id));
            }

            // WorkManager: the resource adapter can use this to dispatch messages or perform tasks
            WorkManager workManager;
            if (transactionManager instanceof GeronimoTransactionManager) {
                GeronimoTransactionManager geronimoTransactionManager = (GeronimoTransactionManager) transactionManager;
                TransactionContextHandler txWorkContextHandler = new TransactionContextHandler(geronimoTransactionManager);
                
                // use id as default realm name if realm is not specified in service properties
                String securityRealmName = getStringProperty(serviceInfo.properties, "realm", serviceInfo.id);
                                
                SecurityContextHandler securityContextHandler = new SecurityContextHandler(securityRealmName);
                HintsContextHandler hintsContextHandler = new HintsContextHandler();
                
                Collection<WorkContextHandler> workContextHandlers = new ArrayList<WorkContextHandler>();
                workContextHandlers.add(txWorkContextHandler);
                workContextHandlers.add(securityContextHandler);
                workContextHandlers.add(hintsContextHandler);
                
                workManager = new GeronimoWorkManager(threadPool, threadPool, threadPool, workContextHandlers);
            } else {
                workManager = new SimpleWorkManager(threadPool);
            }


            // BootstrapContext: wraps the WorkMananger and XATerminator
            BootstrapContext bootstrapContext;
            if (transactionManager instanceof GeronimoTransactionManager) {
            	bootstrapContext = new GeronimoBootstrapContext((GeronimoWorkManager)workManager, (GeronimoTransactionManager)transactionManager, (GeronimoTransactionManager)transactionManager);
            } else if (transactionManager instanceof XATerminator) {
                bootstrapContext = new SimpleBootstrapContext(workManager, (XATerminator) transactionManager);
            } else {
                bootstrapContext = new SimpleBootstrapContext(workManager);
            }

            // start the resource adapter
            try {
            	logger.debug("createResource.startingResourceAdapter", serviceInfo.id, service.getClass().getName());
                resourceAdapter.start(bootstrapContext);
            } catch (ResourceAdapterInternalException e) {
                throw new OpenEJBException(e);
            }

            Map<String, Object> unset = serviceRecipe.getUnsetProperties();
            unset.remove("threadPoolSize");
            logUnusedProperties(unset, serviceInfo);
        } else if (service instanceof ManagedConnectionFactory) {
            ManagedConnectionFactory managedConnectionFactory = (ManagedConnectionFactory) service;

            // connection manager is constructed via a recipe so we automatically expose all cmf properties
            ObjectRecipe connectionManagerRecipe = new ObjectRecipe(GeronimoConnectionManagerFactory.class, "create");
            connectionManagerRecipe.allow(Option.CASE_INSENSITIVE_PROPERTIES);
            connectionManagerRecipe.allow(Option.IGNORE_MISSING_PROPERTIES);
            connectionManagerRecipe.setAllProperties(serviceInfo.properties);
            connectionManagerRecipe.setProperty("name", serviceInfo.id);
            connectionManagerRecipe.setProperty("mcf", managedConnectionFactory);

            // standard properties
            connectionManagerRecipe.setProperty("transactionManager", transactionManager);
            ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
            if (classLoader == null) classLoader = getClass().getClassLoader();
            if (classLoader == null) classLoader = ClassLoader.getSystemClassLoader();
            connectionManagerRecipe.setProperty("classLoader", classLoader);

            logger.getChildLogger("service").info("createResource.createConnectionManager", serviceInfo.id, service.getClass().getName());

            // create the connection manager
            ConnectionManager connectionManager = (ConnectionManager) connectionManagerRecipe.create();
            if (connectionManager == null) {
                throw new OpenEJBRuntimeException(messages.format("assembler.invalidConnectionManager", serviceInfo.id));
            }

            Map<String, Object> unsetA = serviceRecipe.getUnsetProperties();
            Map<String, Object> unsetB = connectionManagerRecipe.getUnsetProperties();
            Map<String, Object> unset = new HashMap<String, Object>();
            for (Map.Entry<String, Object> entry : unsetA.entrySet()) {
                if (unsetB.containsKey(entry.getKey())) unset.put(entry.getKey(),entry.getValue());
            }
            logUnusedProperties(unset, serviceInfo);

            // service becomes a ConnectorReference which merges connection manager and mcf
            service = new ConnectorReference(connectionManager, managedConnectionFactory);
        } else {
            if (service instanceof DataSource) {
                ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
                if (classLoader == null) {
                    classLoader = getClass().getClassLoader();
                }

                final ImportSql importer = new ImportSql(classLoader, serviceInfo.id, (DataSource) service);
                if (importer.hasSomethingToImport()) {
                    importer.doImport();
                }
            }

            logUnusedProperties(serviceRecipe, serviceInfo);
        }

        final String name = OPENEJB_RESOURCE_JNDI_PREFIX + serviceInfo.id;
        try {
            containerSystem.getJNDIContext().bind(name, service);
        } catch (NameAlreadyBoundException nabe) {
            logger.warning("unbounding resource " + name + " can happen because of a redeployment or because of a duplicated id");
            try {
                containerSystem.getJNDIContext().unbind(name);
                containerSystem.getJNDIContext().bind(name, service);
            } catch (NamingException e) {
                throw new OpenEJBException("Cannot bind resource adapter with id " + serviceInfo.id, e);
            }
        } catch (NamingException e) {
            throw new OpenEJBException("Cannot bind resource adapter with id " + serviceInfo.id, e);
        }

        // Update the config tree
        config.facilities.resources.add(serviceInfo);

        logger.getChildLogger("service").debug("createService.success", serviceInfo.service, serviceInfo.id, serviceInfo.className);
    }

    private int getIntProperty(Properties properties, String propertyName, int defaultValue) {
        String propertyValue = getStringProperty(properties, propertyName, Integer.toString(defaultValue));
        if (propertyValue == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(propertyValue);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(propertyName + " is not an integer " + propertyValue, e);
        }
    }

    private String getStringProperty(Properties properties, String propertyName, String defaultValue) {
        String propertyValue = properties.getProperty(propertyName);
        if (propertyValue == null) {
            return defaultValue;
        }

        return propertyValue;
    }
    
    public void createConnectionManager(ConnectionManagerInfo serviceInfo) throws OpenEJBException {

        ObjectRecipe serviceRecipe = createRecipe(serviceInfo);

        Object object = props.get("TransactionManager");
        serviceRecipe.setProperty("transactionManager", object);

        Object service = serviceRecipe.create();

        logUnusedProperties(serviceRecipe, serviceInfo);

        Class interfce = serviceInterfaces.get(serviceInfo.service);
        checkImplementation(interfce, service.getClass(), serviceInfo.service, serviceInfo.id);

        bindService(serviceInfo, service);

        setSystemInstanceComponent(interfce, service);

        getContext().put(interfce.getName(), service);

        props.put(interfce.getName(), service);
        props.put(serviceInfo.service, service);
        props.put(serviceInfo.id, service);

        // Update the config tree
        config.facilities.connectionManagers.add(serviceInfo);

        logger.getChildLogger("service").debug("createService.success", serviceInfo.service, serviceInfo.id, serviceInfo.className);
    }

    public void createSecurityService(SecurityServiceInfo serviceInfo) throws OpenEJBException {

        ObjectRecipe serviceRecipe = createRecipe(serviceInfo);

        Object service = serviceRecipe.create();

        logUnusedProperties(serviceRecipe, serviceInfo);

        Class interfce = serviceInterfaces.get(serviceInfo.service);
        checkImplementation(interfce, service.getClass(), serviceInfo.service, serviceInfo.id);

        try {
            this.containerSystem.getJNDIContext().bind(JAVA_OPENEJB_NAMING_CONTEXT + serviceInfo.service, service);
        } catch (NamingException e) {
            throw new OpenEJBException("Cannot bind " + serviceInfo.service + " with id " + serviceInfo.id, e);
        }

        setSystemInstanceComponent(interfce, service);

        getContext().put(interfce.getName(), service);

        props.put(interfce.getName(), service);
        props.put(serviceInfo.service, service);
        props.put(serviceInfo.id, service);

        this.securityService = (SecurityService) service;

        // Update the config tree
        config.facilities.securityService = serviceInfo;

        logger.getChildLogger("service").debug("createService.success", serviceInfo.service, serviceInfo.id, serviceInfo.className);
    }

    public void createTransactionManager(TransactionServiceInfo serviceInfo) throws OpenEJBException {

        ObjectRecipe serviceRecipe = createRecipe(serviceInfo);

        Object service = serviceRecipe.create();

        logUnusedProperties(serviceRecipe, serviceInfo);

        Class interfce = serviceInterfaces.get(serviceInfo.service);
        checkImplementation(interfce, service.getClass(), serviceInfo.service, serviceInfo.id);

        try {
            this.containerSystem.getJNDIContext().bind(JAVA_OPENEJB_NAMING_CONTEXT + serviceInfo.service, service);
            this.containerSystem.getJNDIContext().bind("comp/UserTransaction", new CoreUserTransaction((TransactionManager) service));
            this.containerSystem.getJNDIContext().bind("comp/TransactionManager", service);
        } catch (NamingException e) {
            throw new OpenEJBException("Cannot bind " + serviceInfo.service + " with id " + serviceInfo.id, e);
        }

        setSystemInstanceComponent(interfce, service);

        getContext().put(interfce.getName(), service);

        props.put(interfce.getName(), service);
        props.put(serviceInfo.service, service);
        props.put(serviceInfo.id, service);

        this.transactionManager = (TransactionManager) service;

        // Update the config tree
        config.facilities.transactionService = serviceInfo;

        // todo find a better place for this

        // TransactionSynchronizationRegistry
        TransactionSynchronizationRegistry synchronizationRegistry;
        if (transactionManager instanceof TransactionSynchronizationRegistry) {
            synchronizationRegistry = (TransactionSynchronizationRegistry) transactionManager;
        } else {
            // todo this should be built
            synchronizationRegistry = new SimpleTransactionSynchronizationRegistry(transactionManager);
        }

        Assembler.getContext().put(TransactionSynchronizationRegistry.class.getName(), synchronizationRegistry);
        SystemInstance.get().setComponent(TransactionSynchronizationRegistry.class, synchronizationRegistry);

        try {
            this.containerSystem.getJNDIContext().bind("comp/TransactionSynchronizationRegistry", new TransactionSynchronizationRegistryWrapper());
        } catch (NamingException e) {
            throw new OpenEJBException("Cannot bind java:comp/TransactionSynchronizationRegistry", e);
        }


        // JtaEntityManagerRegistry
        // todo this should be built
        JtaEntityManagerRegistry jtaEntityManagerRegistry = new JtaEntityManagerRegistry(synchronizationRegistry);
        Assembler.getContext().put(JtaEntityManagerRegistry.class.getName(), jtaEntityManagerRegistry);
        SystemInstance.get().setComponent(JtaEntityManagerRegistry.class, jtaEntityManagerRegistry);

        logger.getChildLogger("service").debug("createService.success", serviceInfo.service, serviceInfo.id, serviceInfo.className);
    }

    private void logUnusedProperties(ObjectRecipe serviceRecipe, ServiceInfo info) {
        Map<String, Object> unsetProperties = serviceRecipe.getUnsetProperties();
        logUnusedProperties(unsetProperties, info);
    }

    private void logUnusedProperties(Map<String, Object> unsetProperties, ServiceInfo info) {
        for (String property : unsetProperties.keySet()) {
            //TODO: DMB: Make more robust later
            if (property.equalsIgnoreCase("JndiName")) return;
            if (property.equalsIgnoreCase("Origin")) return;
            if (property.equalsIgnoreCase("DatabaseName")) return;
            if (property.equalsIgnoreCase("connectionAttributes")) return;

            if (property.equalsIgnoreCase("properties")) return;
            if (property.equalsIgnoreCase("ApplicationWide")) return;
            if (property.equalsIgnoreCase("transactionManager")) return;
            if (info.types.contains("javax.mail.Session")) return;
            //---

            if (info.types.isEmpty() && "class".equalsIgnoreCase(property)) continue; // inline service (no sp)

            logger.getChildLogger("service").warning("unusedProperty", property, info.id);
        }
    }

    private ObjectRecipe createRecipe(ServiceInfo info) {
        Logger serviceLogger = logger.getChildLogger("service");
        serviceLogger.info("createService", info.service, info.id, info.className);
        String[] constructorArgs = info.constructorArgs.toArray(new String[info.constructorArgs.size()]);
        ObjectRecipe serviceRecipe = new ObjectRecipe(info.className, info.factoryMethod, constructorArgs, null);
        serviceRecipe.allow(Option.CASE_INSENSITIVE_PROPERTIES);
        serviceRecipe.allow(Option.IGNORE_MISSING_PROPERTIES);
        serviceRecipe.setAllProperties(info.properties);

        if (serviceLogger.isDebugEnabled()){
            for (Map.Entry<String, Object> entry : serviceRecipe.getProperties().entrySet()) {
                serviceLogger.debug("createService.props", entry.getKey(), entry.getValue());
            }
        }
        return serviceRecipe;
    }

    @SuppressWarnings({"unchecked"})
    private void setSystemInstanceComponent(Class interfce, Object service) {
        SystemInstance.get().setComponent(interfce, service);
    }

    private URL toUrl(String jarPath) throws OpenEJBException {
        try {
            return new File(jarPath).toURI().toURL();
        } catch (MalformedURLException e) {
            throw new OpenEJBException(messages.format("cl0001", jarPath, e.getMessage()), e);
        }
    }

    private static class PersistenceClassLoaderHandlerImpl implements PersistenceClassLoaderHandler {
        private final Map<String,List<ClassFileTransformer>> transformers = new TreeMap<String, List<ClassFileTransformer>> ();

        public void addTransformer(String unitId, ClassLoader classLoader, ClassFileTransformer classFileTransformer) {
            Instrumentation instrumentation = Agent.getInstrumentation();
            if (instrumentation != null) {
                instrumentation.addTransformer(classFileTransformer);

                if (unitId != null) {
                    List<ClassFileTransformer> transformers = this.transformers.get(unitId);
                    if (transformers == null) {
                        transformers = new ArrayList<ClassFileTransformer>(1);
                        this.transformers.put(unitId, transformers);
                    }
                    transformers.add(classFileTransformer);
                }
            } else {
                logger.error("assembler.noAgent");
            }
        }

        public void destroy(String unitId) {
            List<ClassFileTransformer> transformers = this.transformers.remove(unitId);
            if (transformers != null) {
                Instrumentation instrumentation = Agent.getInstrumentation();
                if (instrumentation != null) {
                    for (ClassFileTransformer transformer : transformers) {
                        instrumentation.removeTransformer(transformer);
                    }
                } else {
                    logger.error("assembler.noAgent");
                }
            }
        }

        public ClassLoader getNewTempClassLoader(ClassLoader classLoader) {
            return ClassLoaderUtil.createTempClassLoader(classLoader);
        }
    }

    // Based on edu.emory.mathcs.backport.java.util.concurrent.Executors.DefaultThreadFactory
    // Which is freely licensed as follows.
    // "Use, modify, and redistribute this code in any way without acknowledgement"
    private static class ResourceAdapterThreadFactory implements ThreadFactory {
        private final ThreadGroup group;
        private final String namePrefix;
        private final AtomicInteger threadNumber = new AtomicInteger(1);

        ResourceAdapterThreadFactory(String resourceAdapterName) {
            SecurityManager securityManager = System.getSecurityManager();
            if (securityManager != null) {
                group = securityManager.getThreadGroup();
            } else {
                group = Thread.currentThread().getThreadGroup();
            }

            namePrefix = resourceAdapterName + "-worker-";
        }

        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(group, runnable, namePrefix + threadNumber.getAndIncrement(), 0);
            if (!thread.isDaemon()) thread.setDaemon(true);
            if (thread.getPriority() != Thread.NORM_PRIORITY) thread.setPriority(Thread.NORM_PRIORITY);
            return thread;
        }
    }

    private static class DeploymentListenerObserver {
        private final DeploymentListener delegate;

        public DeploymentListenerObserver(final DeploymentListener deploymentListener) {
            delegate = deploymentListener;
        }

        public void afterApplicationCreated(@Observes AssemblerAfterApplicationCreated event) {
            delegate.afterApplicationCreated(event.getApp());
        }

        public void beforeApplicationDestroyed(@Observes AssemblerBeforeApplicationDestroyed event) {
            delegate.beforeApplicationDestroyed(event.getApp());
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof DeploymentListenerObserver)) {
                return false;
            }

            final DeploymentListenerObserver that = (DeploymentListenerObserver) o;

            return !(delegate != null ? !delegate.equals(that.delegate) : that.delegate != null);
        }

        @Override
        public int hashCode() {
            return delegate != null ? delegate.hashCode() : 0;
        }
    }
}
