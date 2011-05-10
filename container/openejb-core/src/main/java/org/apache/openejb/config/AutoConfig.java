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
package org.apache.openejb.config;

import static org.apache.openejb.config.ServiceUtils.NONE;
import static org.apache.openejb.config.ServiceUtils.ANY;

import static java.util.Arrays.asList;

import static org.apache.openejb.config.ServiceUtils.hasServiceProvider;
import org.apache.openejb.OpenEJBException;
import org.apache.openejb.config.sys.Resource;
import org.apache.openejb.assembler.classic.ContainerInfo;
import org.apache.openejb.assembler.classic.ResourceInfo;
import org.apache.openejb.util.LinkResolver;
import org.apache.openejb.util.URLs;
import org.apache.openejb.util.UniqueDefaultLinkResolver;
import org.apache.openejb.jee.MessageDrivenBean;
import org.apache.openejb.jee.ActivationConfig;
import org.apache.openejb.jee.EnterpriseBean;
import org.apache.openejb.jee.MessageDestination;
import org.apache.openejb.jee.AssemblyDescriptor;
import org.apache.openejb.jee.PersistenceType;
import org.apache.openejb.jee.SessionType;
import org.apache.openejb.jee.MessageDestinationRef;
import org.apache.openejb.jee.JndiReference;
import org.apache.openejb.jee.ResourceRef;
import org.apache.openejb.jee.JndiConsumer;
import org.apache.openejb.jee.Connector;
import org.apache.openejb.jee.ResourceAdapter;
import org.apache.openejb.jee.OutboundResourceAdapter;
import org.apache.openejb.jee.ConnectionDefinition;
import org.apache.openejb.jee.InboundResource;
import org.apache.openejb.jee.MessageListener;
import org.apache.openejb.jee.AdminObject;
import org.apache.openejb.jee.PersistenceContextRef;
import org.apache.openejb.jee.PersistenceRef;
import org.apache.openejb.jee.ActivationConfigProperty;
import org.apache.openejb.jee.jpa.unit.Persistence;
import org.apache.openejb.jee.jpa.unit.PersistenceUnit;
import org.apache.openejb.jee.oejb3.EjbDeployment;
import org.apache.openejb.jee.oejb3.OpenejbJar;
import org.apache.openejb.jee.oejb3.ResourceLink;
import org.apache.openejb.util.LogCategory;
import org.apache.openejb.util.Logger;
import org.apache.openejb.util.URISupport;
import org.apache.openejb.util.SuperProperties;
import static org.apache.openejb.util.Join.join;

import javax.jms.Queue;
import javax.jms.Topic;
import javax.validation.Validator;
import javax.validation.ValidatorFactory;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.HashMap;
import java.util.TreeMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Collection;
import java.util.Iterator;
import java.net.URI;

public class AutoConfig implements DynamicDeployer {

    public static Logger logger = Logger.getInstance(LogCategory.OPENEJB_STARTUP_CONFIG, AutoConfig.class);

    private static Set<String> ignoredReferenceTypes = new TreeSet<String>();
    static{
        // Context objects are automatically handled
        ignoredReferenceTypes.add("javax.ejb.SessionContext");
        ignoredReferenceTypes.add("javax.ejb.EntityContext");
        ignoredReferenceTypes.add("javax.ejb.MessageDrivenContext");
        ignoredReferenceTypes.add("javax.xml.ws.WebServiceContext");
        // URLs are automatically handled
        ignoredReferenceTypes.add("java.net.URL");
        // User transaction is automatically handled
        ignoredReferenceTypes.add("javax.transaction.UserTransaction");
        ignoredReferenceTypes.add("javax.ejb.TimerService");
        // Bean Validation is automatically handled
        ignoredReferenceTypes.add(Validator.class.getName());
        ignoredReferenceTypes.add(ValidatorFactory.class.getName());
    }

    private final ConfigurationFactory configFactory;
    private boolean autoCreateContainers = true;
    private boolean autoCreateResources = true;

    public AutoConfig(ConfigurationFactory configFactory) {
        this.configFactory = configFactory;
    }

    public synchronized boolean autoCreateResources() {
        return autoCreateResources;
    }

    public synchronized void autoCreateResources(boolean autoCreateResources) {
        this.autoCreateResources = autoCreateResources;
    }

    public synchronized boolean autoCreateContainers() {
        return autoCreateContainers;
    }

    public synchronized void autoCreateContainers(boolean autoCreateContainers) {
        this.autoCreateContainers = autoCreateContainers;
    }

    public void init() throws OpenEJBException {
    }

    public synchronized AppModule deploy(AppModule appModule) throws OpenEJBException {
        AppResources appResources = new AppResources(appModule);

        for (EjbModule ejbModule : appModule.getEjbModules()) {
            processActivationConfig(ejbModule);
        }
        resolveDestinationLinks(appModule);

        resolvePersistenceRefs(appModule);

        for (EjbModule ejbModule : appModule.getEjbModules()) {
            deploy(ejbModule, appResources);
        }
        for (ClientModule clientModule : appModule.getClientModules()) {
            deploy(clientModule, appResources);
        }
        for (ConnectorModule connectorModule : appModule.getResourceModules()) {
            deploy(connectorModule);
        }
        for (WebModule webModule : appModule.getWebModules()) {
            deploy(webModule, appResources);
        }
        for (PersistenceModule persistenceModule : appModule.getPersistenceModules()) {
            deploy(appModule, persistenceModule);
        }
        return appModule;
    }

    private void resolvePersistenceRefs(AppModule appModule) {
        LinkResolver<PersistenceUnit> persistenceUnits = new UniqueDefaultLinkResolver<PersistenceUnit>();

        for (PersistenceModule module : appModule.getPersistenceModules()) {
            String rootUrl = module.getRootUrl();
            for (PersistenceUnit unit : module.getPersistence().getPersistenceUnit()) {
                unit.setId(unit.getName() + " " + rootUrl.hashCode());
                persistenceUnits.add(rootUrl, unit.getName(), unit);
            }
        }

        for (EjbModule ejbModule : appModule.getEjbModules()) {
            URI moduleURI = URI.create(ejbModule.getModuleId());

            for (JndiConsumer component : ejbModule.getEjbJar().getEnterpriseBeans()) {
                processPersistenceRefs(component, ejbModule, persistenceUnits, moduleURI);
            }

        }

        for (ClientModule clientModule : appModule.getClientModules()) {
            URI moduleURI = URI.create(clientModule.getModuleId());
            processPersistenceRefs(clientModule.getApplicationClient(), clientModule, persistenceUnits, moduleURI);
        }

        for (WebModule webModule : appModule.getWebModules()) {
            URI moduleURI = URI.create(webModule.getModuleId());
            processPersistenceRefs(webModule.getWebApp(), webModule, persistenceUnits, moduleURI);
        }
    }

    private void processPersistenceRefs(JndiConsumer component, DeploymentModule module, LinkResolver<PersistenceUnit> persistenceUnits, URI moduleURI) {

        String componentName = component.getJndiConsumerName();

        ValidationContext validation = module.getValidation();

        for (PersistenceRef ref : component.getPersistenceUnitRef()) {

            processPersistenceRef(persistenceUnits, ref, moduleURI, componentName, validation);
        }

        for (PersistenceRef ref : component.getPersistenceContextRef()) {

            processPersistenceRef(persistenceUnits, ref, moduleURI, componentName, validation);
        }
    }

    private PersistenceUnit processPersistenceRef(LinkResolver<PersistenceUnit> persistenceUnits, PersistenceRef ref, URI moduleURI, String componentName, ValidationContext validation) {

        if (ref.getMappedName() != null && ref.getMappedName().startsWith("jndi:")){
            return null;
        }

        PersistenceUnit unit = persistenceUnits.resolveLink(ref.getPersistenceUnitName(), moduleURI);

        // Explicitly check if we messed up the "if there's only one,
        // that's what you get" rule by adding our "cmp" unit.
        Collection<PersistenceUnit> cmpUnits = persistenceUnits.values("cmp");
        if (unit == null && cmpUnits.size() > 0 && persistenceUnits.values().size() - cmpUnits.size() == 1) {
            // We did, there is exactly one non-cmp unit.  Let's find it.
            for (PersistenceUnit persistenceUnit : persistenceUnits.values()) {
                if (!persistenceUnit.getName().equals("cmp")){
                    // Found it
                    unit = persistenceUnit;
                    break;
                }
            }
        }


        // try again using the ref name
        if (unit == null){
            unit = persistenceUnits.resolveLink(ref.getName(), moduleURI);
        }

        // try again using the ref name with any prefix removed
        if (unit == null){
            String shortName = ref.getName().replaceFirst(".*/", "");
            unit = persistenceUnits.resolveLink(shortName, moduleURI);
        }

        if (unit != null){
            ref.setPersistenceUnitName(unit.getName());
            ref.setMappedName(unit.getId());
        } else {

            // ----------------------------------------------
            //  Nothing was found.  Let's try and figure out
            //  what went wrong and log a validation message
            // ----------------------------------------------

            String refType = "persistence";
            if (ref instanceof PersistenceContextRef){
                refType += "ContextRef";
            } else refType += "UnitRef";

            String refShortName = ref.getName();
            if (refShortName.matches(".*\\..*/.*")){
                refShortName = refShortName.replaceFirst(".*/", "");
            }

            List<String> availableUnits = new ArrayList<String>();
            for (PersistenceUnit persistenceUnit : persistenceUnits.values()) {
                availableUnits.add(persistenceUnit.getName());
            }

            Collections.sort(availableUnits);

            String unitName = ref.getPersistenceUnitName();

            if (availableUnits.size() == 0){
                // Print a sample persistence.xml using their data
                if (unitName == null){
                    unitName = refShortName;
                }
                validation.fail(componentName, refType + ".noPersistenceUnits", refShortName, unitName);
            } else if (ref.getPersistenceUnitName() == null && availableUnits.size() > 1) {
                // Print a correct example of unitName in a ref
                // DMB: Idea, the ability to set a default unit-name in openejb-jar.xml via a property
                String sampleUnitName = availableUnits.get(0);
                validation.fail(componentName, refType + ".noUnitName", refShortName, join(", ", availableUnits), sampleUnitName );
            } else {
                Collection<PersistenceUnit> vagueMatches = persistenceUnits.values(ref.getPersistenceUnitName());
                if (vagueMatches.size() != 0) {
                    // Print the full rootUrls

                    List<String> possibleUnits = new ArrayList<String>();
                    for (PersistenceUnit persistenceUnit : persistenceUnits.values()) {
                        try {
                            URI unitURI = URI.create(persistenceUnit.getId());
                            unitURI = URISupport.relativize(moduleURI, unitURI);
                            possibleUnits.add(unitURI.toString());
                        } catch (Exception e) {
                            // id is typically not a valid URI
                            possibleUnits.add(persistenceUnit.getId());
                        }
                    }

                    Collections.sort(possibleUnits);

                    validation.fail(componentName, refType + ".vagueMatches", refShortName, unitName, possibleUnits.size(), join("\n", possibleUnits));
                } else {
                    validation.fail(componentName, refType + ".noMatches", refShortName, unitName, join(", ", availableUnits));
                }
            }
        }
        return unit;
    }


    /**
     * Set destination, destinationType, clientId and subscriptionName in the MDB activation config.
     */
    private void processActivationConfig(EjbModule ejbModule) throws OpenEJBException {
        OpenejbJar openejbJar;
        if (ejbModule.getOpenejbJar() != null) {
            openejbJar = ejbModule.getOpenejbJar();
        } else {
            openejbJar = new OpenejbJar();
            ejbModule.setOpenejbJar(openejbJar);
        }

        Map<String, EjbDeployment> deployments = openejbJar.getDeploymentsByEjbName();

        for (EnterpriseBean bean : ejbModule.getEjbJar().getEnterpriseBeans()) {
            if (bean instanceof MessageDrivenBean) {
                MessageDrivenBean mdb = (MessageDrivenBean) bean;

                if (mdb.getActivationConfig() == null) {
                    mdb.setActivationConfig(new ActivationConfig());
                }

                if (!isJms(mdb)) continue;

                EjbDeployment ejbDeployment = deployments.get(bean.getEjbName());
                if (ejbDeployment == null) {
                    throw new OpenEJBException("No ejb deployment found for ejb " + bean.getEjbName());
                }

                Properties properties = mdb.getActivationConfig().toProperties();

                String destination = properties.getProperty("destinationName");

                if (destination != null) {
                    mdb.getActivationConfig().addProperty("destination", destination);

                    // Remove destinationName as it is not in the standard ActivationSpec 
                    List<ActivationConfigProperty> list = mdb.getActivationConfig().getActivationConfigProperty();
                    Iterator<ActivationConfigProperty> iterator = list.iterator();
                    while (iterator.hasNext()) {
                        ActivationConfigProperty configProperty = iterator.next();
                        if (configProperty.getActivationConfigPropertyName().equals("destinationName")){
                            iterator.remove();
                            break;
                        }
                    }
                } else {
                    destination = properties.getProperty("destination");
                }

                // destination
//                String destination = properties.getProperty("destination", properties.getProperty("destinationName"));
                if (destination == null) {
                    destination = ejbDeployment.getDeploymentId();
                    mdb.getActivationConfig().addProperty("destination", destination);
                }

                // destination identifier
                ResourceLink link = ejbDeployment.getResourceLink("openejb/destination");
                if (link == null && mdb.getMessageDestinationLink() == null) {
                    link = new ResourceLink();
                    link.setResId(destination);
                    link.setResRefName("openejb/destination");
                    ejbDeployment.addResourceLink(link);
                }

                // destination type
                String destinationType = properties.getProperty("destinationType");
                if (destinationType == null && mdb.getMessageDestinationType() != null) {
                    destinationType = mdb.getMessageDestinationType();
                    mdb.getActivationConfig().addProperty("destinationType", destinationType);
                }
                if (mdb.getMessageDestinationType() == null) {
                    mdb.setMessageDestinationType(destinationType);
                }

                // topics need a clientId and subscriptionName
                if ("javax.jms.Topic".equals(destinationType)) {
                    if (!properties.containsKey("clientId")) {
                        mdb.getActivationConfig().addProperty("clientId", ejbDeployment.getDeploymentId());
                    }
                    if (!properties.containsKey("subscriptionName")) {
                        mdb.getActivationConfig().addProperty("subscriptionName", ejbDeployment.getDeploymentId() + "_subscription");
                    }
                }

            }
        }
    }

    private boolean isJms(MessageDrivenBean mdb) {
        String messagingType = mdb.getMessagingType();
        return (messagingType != null && messagingType.startsWith("javax.jms"));
    }

    /**
     * Set resource id in all message-destination-refs and MDBs that are using message destination links.
     */
    private void resolveDestinationLinks(AppModule appModule) throws OpenEJBException {
        // build up a link resolver
        LinkResolver<MessageDestination> destinationResolver = new LinkResolver<MessageDestination>();
        for (EjbModule ejbModule : appModule.getEjbModules()) {
            AssemblyDescriptor assembly = ejbModule.getEjbJar().getAssemblyDescriptor();
            if (assembly != null) {
                String moduleId = ejbModule.getModuleId();
                for (MessageDestination destination : assembly.getMessageDestination()) {
                    destinationResolver.add(moduleId, destination.getMessageDestinationName(), destination);
                }
            }
        }
        for (ClientModule clientModule : appModule.getClientModules()) {
            String moduleId = appModule.getModuleId();
            for (MessageDestination destination : clientModule.getApplicationClient().getMessageDestination()) {
                destinationResolver.add(moduleId, destination.getMessageDestinationName(), destination);
            }
        }
        for (WebModule webModule : appModule.getWebModules()) {
            String moduleId = appModule.getModuleId();
            for (MessageDestination destination : webModule.getWebApp().getMessageDestination()) {
                destinationResolver.add(moduleId, destination.getMessageDestinationName(), destination);
            }
        }

        // remember the type of each destination so we can use it to fillin MDBs that don't declare destination type
        Map<MessageDestination,String> destinationTypes = new HashMap<MessageDestination,String>();

        // resolve all MDBs with destination links
        // if MessageDestination does not have a mapped name assigned, give it the destination from the MDB
        for (EjbModule ejbModule : appModule.getEjbModules()) {
            AssemblyDescriptor assembly = ejbModule.getEjbJar().getAssemblyDescriptor();
            if (assembly == null) {
                continue;
            }

            URI moduleUri = URI.create(appModule.getModuleId());
            OpenejbJar openejbJar = ejbModule.getOpenejbJar();

            for (EnterpriseBean bean : ejbModule.getEjbJar().getEnterpriseBeans()) {
                // MDB destination is deploymentId if none set
                if (bean instanceof MessageDrivenBean) {
                    MessageDrivenBean mdb = (MessageDrivenBean) bean;

                    EjbDeployment ejbDeployment = openejbJar.getDeploymentsByEjbName().get(bean.getEjbName());
                    if (ejbDeployment == null) {
                        throw new OpenEJBException("No ejb deployment found for ejb " + bean.getEjbName());
                    }

                    // skip destination refs without a destination link
                    String link = mdb.getMessageDestinationLink();
                    if (link == null || link.length() == 0) {
                        continue;
                    }

                    // resolve the destination... if we don't find one it is a configuration bug
                    MessageDestination destination = destinationResolver.resolveLink(link, moduleUri);
                    if (destination == null) {
                        throw new OpenEJBException("Message destination " + link + " for message driven bean " + mdb.getEjbName()  + " not found");
                    }

                    // get the destinationId is the mapped name
                    String destinationId = destination.getMappedName();
                    if (destinationId == null) {
                        // if we don't have a mapped name use the destination of the mdb
                        Properties properties = mdb.getActivationConfig().toProperties();
                        destinationId = properties.getProperty("destination");
                        destination.setMappedName(destinationId);
                    }

                    if (mdb.getMessageDestinationType() != null && !destinationTypes.containsKey(destination)) {
                        destinationTypes.put(destination, mdb.getMessageDestinationType());
                    }

                    // destination identifier
                    ResourceLink resourceLink = ejbDeployment.getResourceLink("openejb/destination");
                    if (resourceLink == null) {
                        resourceLink = new ResourceLink();
                        resourceLink.setResRefName("openejb/destination");
                        ejbDeployment.addResourceLink(resourceLink);
                    }
                    resourceLink.setResId(destinationId);
                }
            }
        }

        // resolve all message destination refs with links and assign a ref id to the reference
        for (EjbModule ejbModule : appModule.getEjbModules()) {
            AssemblyDescriptor assembly = ejbModule.getEjbJar().getAssemblyDescriptor();
            if (assembly == null) {
                continue;
            }

            URI moduleUri = URI.create(appModule.getModuleId());
            OpenejbJar openejbJar = ejbModule.getOpenejbJar();

            for (EnterpriseBean bean : ejbModule.getEjbJar().getEnterpriseBeans()) {
                EjbDeployment ejbDeployment = openejbJar.getDeploymentsByEjbName().get(bean.getEjbName());
                if (ejbDeployment == null) {
                    throw new OpenEJBException("No ejb deployment found for ejb " + bean.getEjbName());
                }

                for (MessageDestinationRef ref : bean.getMessageDestinationRef()) {
                    // skip destination refs with a resource link already assigned
                    if (ref.getMappedName() == null && ejbDeployment.getResourceLink(ref.getName()) == null) {
                        String destinationId = resolveDestinationId(ref, moduleUri, destinationResolver, destinationTypes);
                        if (destinationId != null) {
                            // build the link and add it
                            ResourceLink resourceLink = new ResourceLink();
                            resourceLink.setResId(destinationId);
                            resourceLink.setResRefName(ref.getName());
                            ejbDeployment.addResourceLink(resourceLink);
                        }

                    }
                }
            }
        }

        for (ClientModule clientModule : appModule.getClientModules()) {
            URI moduleUri = URI.create(appModule.getModuleId());
            for (MessageDestinationRef ref : clientModule.getApplicationClient().getMessageDestinationRef()) {
                String destinationId = resolveDestinationId(ref, moduleUri, destinationResolver, destinationTypes);
                if (destinationId != null) {
                    // for client modules we put the destinationId in the mapped name
                    ref.setMappedName(destinationId);
                }
            }
        }

        for (WebModule webModule : appModule.getWebModules()) {
            URI moduleUri = URI.create(appModule.getModuleId());
            for (MessageDestinationRef ref : webModule.getWebApp().getMessageDestinationRef()) {
                String destinationId = resolveDestinationId(ref, moduleUri, destinationResolver, destinationTypes);
                if (destinationId != null) {
                    // for web modules we put the destinationId in the mapped name
                    ref.setMappedName(destinationId);
                }
            }
        }

        // Process MDBs one more time...
        // this time fill in the destination type (if not alreday specified) with
        // the info from the destination (which got filled in from the references)
        for (EjbModule ejbModule : appModule.getEjbModules()) {
            AssemblyDescriptor assembly = ejbModule.getEjbJar().getAssemblyDescriptor();
            if (assembly == null) {
                continue;
            }

            URI moduleUri = URI.create(appModule.getModuleId());
            OpenejbJar openejbJar = ejbModule.getOpenejbJar();

            for (EnterpriseBean bean : ejbModule.getEjbJar().getEnterpriseBeans()) {
                // MDB destination is deploymentId if none set
                if (bean instanceof MessageDrivenBean) {
                    MessageDrivenBean mdb = (MessageDrivenBean) bean;

                    if (!isJms(mdb)) continue;

                    EjbDeployment ejbDeployment = openejbJar.getDeploymentsByEjbName().get(bean.getEjbName());
                    if (ejbDeployment == null) {
                        throw new OpenEJBException("No ejb deployment found for ejb " + bean.getEjbName());
                    }

                    // if destination type is already set in, continue
                    String destinationType = mdb.getMessageDestinationType();
                    if (destinationType != null) {
                        continue;
                    }

                    String link = mdb.getMessageDestinationLink();
                    if (link != null && link.length() != 0) {
                        // resolve the destination... if we don't find one it is a configuration bug
                        MessageDestination destination = destinationResolver.resolveLink(link, moduleUri);
                        if (destination == null) {
                            throw new OpenEJBException("Message destination " + link + " for message driven bean " + mdb.getEjbName()  + " not found");
                        }
                        destinationType = destinationTypes.get(destination);
                    }

                    if (destinationType == null) {
                        // couldn't determine type... we'll have to guess

                        // if destination name contains the string "queue" or "topic" we use that
                        Properties properties = mdb.getActivationConfig().toProperties();
                        String destination = properties.getProperty("destination").toLowerCase();
                        if (destination.indexOf("queue") >= 0) {
                            destinationType = Queue.class.getName();
                        } else if (destination.indexOf("topic") >= 0) {
                            destinationType = Topic.class.getName();
                        } else {
                            // Queue is the default
                            destinationType = Queue.class.getName();
                        }
                        logger.info("Auto-configuring a message driven bean " + ejbDeployment.getDeploymentId() + " destination " + properties.getProperty("destination") + " to be destinationType " + destinationType);
                    }

                    if (destinationType != null) {
                        mdb.getActivationConfig().addProperty("destinationType", destinationType);
                        mdb.setMessageDestinationType(destinationType);

                        // topics need a clientId and subscriptionName
                        if ("javax.jms.Topic".equals(destinationType)) {
                            Properties properties = mdb.getActivationConfig().toProperties();
                            if (!properties.containsKey("clientId")) {
                                mdb.getActivationConfig().addProperty("clientId", ejbDeployment.getDeploymentId());
                            }
                            if (!properties.containsKey("subscriptionName")) {
                                mdb.getActivationConfig().addProperty("subscriptionName", ejbDeployment.getDeploymentId() + "_subscription");
                            }
                        }
                    }
                }
            }
        }

    }

    private String resolveDestinationId(MessageDestinationRef ref, URI moduleUri, LinkResolver<MessageDestination> destinationResolver, Map<MessageDestination,String> destinationTypes) throws OpenEJBException {
        // skip destination refs without a destination link
        String link = ref.getMessageDestinationLink();
        if (link == null || link.length() == 0) {
            return null;
        }

        // resolve the destination... if we don't find one it is a configuration bug
        MessageDestination destination = destinationResolver.resolveLink(link, moduleUri);
        if (destination == null) {
            throw new OpenEJBException("Message destination " + link + " for message-destination-ref " + ref.getMessageDestinationRefName()  + " not found");
        }

        // remember the type of each destination so we can use it to fillin MDBs that don't declare destination type
        if (ref.getMessageDestinationType() != null && !destinationTypes.containsKey(destination)) {
            destinationTypes.put(destination, ref.getMessageDestinationType());
        }

        // get the destinationId
        String destinationId = destination.getMappedName();
        if (destinationId == null) destination.getMessageDestinationName();
        return destinationId;
    }

    private void deploy(ClientModule clientModule, AppResources appResources) throws OpenEJBException {
        processJndiRefs(clientModule.getModuleId(), clientModule.getApplicationClient(), appResources);
    }

    @SuppressWarnings({"UnusedDeclaration"})
    private void deploy(ConnectorModule connectorModule) throws OpenEJBException {
        // Nothing to process for resource modules
    }

    private void deploy(WebModule webModule, AppResources appResources) throws OpenEJBException {
        processJndiRefs(webModule.getModuleId(), webModule.getWebApp(), appResources);
    }

    private void processJndiRefs(String moduleId, JndiConsumer jndiConsumer, AppResources appResources) throws OpenEJBException {
        // Resource reference
        for (ResourceRef ref : jndiConsumer.getResourceRef()) {
            // skip references such as URLs which are automatically handled by the server
            if (ignoredReferenceTypes.contains(ref.getType())) {
                continue;
            }

            // skip destinations with a global jndi name
            String mappedName = ref.getMappedName();
            if (mappedName == null) mappedName = "";
            if (mappedName.startsWith("jndi:")){
                continue;
            }

            String destinationId = (mappedName.length() == 0) ? ref.getName() : mappedName;
            destinationId = getResourceId(moduleId, destinationId, ref.getType(), appResources);
            ref.setMappedName(destinationId);
        }

        // Resource env reference
        for (JndiReference ref : jndiConsumer.getResourceEnvRef()) {
            // skip references such as URLs which are automatically handled by the server
            if (ignoredReferenceTypes.contains(ref.getType())) {
                continue;
            }

            // skip destinations with a global jndi name
            String mappedName = ref.getMappedName();
            if (mappedName == null) mappedName = "";
            if (mappedName.startsWith("jndi:")){
                continue;
            }

            String destinationId = (mappedName.length() == 0) ? ref.getName() : mappedName;
            destinationId = getResourceEnvId(moduleId, destinationId, ref.getType(), appResources);
            ref.setMappedName(destinationId);
        }

        // Message destination reference
        for (MessageDestinationRef ref : jndiConsumer.getMessageDestinationRef()) {
            // skip destinations with a global jndi name
            String mappedName = ref.getMappedName() + "";
            if (mappedName.startsWith("jndi:")){
                continue;
            }

            String destinationId = (mappedName.length() == 0) ? ref.getName() : mappedName;
            destinationId = getResourceEnvId(moduleId, destinationId, ref.getType(), appResources);
            ref.setMappedName(destinationId);
        }
    }

    private void deploy(EjbModule ejbModule, AppResources appResources) throws OpenEJBException {
        OpenejbJar openejbJar;
        if (ejbModule.getOpenejbJar() != null) {
            openejbJar = ejbModule.getOpenejbJar();
        } else {
            openejbJar = new OpenejbJar();
            ejbModule.setOpenejbJar(openejbJar);
        }

        Map<String, EjbDeployment> deployments = openejbJar.getDeploymentsByEjbName();

        for (EnterpriseBean bean : ejbModule.getEjbJar().getEnterpriseBeans()) {
            EjbDeployment ejbDeployment = deployments.get(bean.getEjbName());
            if (ejbDeployment == null) {
                throw new OpenEJBException("No ejb deployment found for ejb " + bean.getEjbName());
            }

            Class<? extends ContainerInfo> containerInfoType = ConfigurationFactory.getContainerInfoType(getType(bean));
            if (ejbDeployment.getContainerId() == null && !skipMdb(bean)) {
                String containerId = getUsableContainer(containerInfoType, bean, appResources);
                if (containerId == null){
                    containerId = createContainer(containerInfoType, ejbDeployment, bean);
                }
                ejbDeployment.setContainerId(containerId);
            }

            // create the container if it doesn't exist
            List<String> containerIds = configFactory.getContainerIds();
            containerIds.addAll(appResources.getContainerIds());
            if (!containerIds.contains(ejbDeployment.getContainerId()) && !skipMdb(bean)) {
                createContainer(containerInfoType, ejbDeployment, bean);
            }

            // Resource reference
            for (ResourceRef ref : bean.getResourceRef()) {
                processResourceRef(ref, ejbDeployment, appResources);
            }

            // Resource env reference
            for (JndiReference ref : bean.getResourceEnvRef()) {
                processResourceEnvRef(ref, ejbDeployment, appResources);
            }

            // Message destination reference
            for (MessageDestinationRef ref : bean.getMessageDestinationRef()) {
                processResourceEnvRef(ref, ejbDeployment, appResources);
            }


            // mdb message destination id
            if (autoCreateResources && bean instanceof MessageDrivenBean) {
                MessageDrivenBean mdb = (MessageDrivenBean) bean;

                ResourceLink resourceLink = ejbDeployment.getResourceLink("openejb/destination");
                if (resourceLink != null) {
                    try {
                        String destinationId = getResourceEnvId(bean.getEjbName(), resourceLink.getResId(), mdb.getMessageDestinationType(), appResources);
                        resourceLink.setResId(destinationId);
                    } catch (OpenEJBException e) {
                        // The MDB doesn't need the auto configured "openejb/destination" env entry
                        ejbDeployment.removeResourceLink("openejb/destination");
                    }
                }
            }

        }
    }

    private String createContainer(Class<? extends ContainerInfo> containerInfoType, EjbDeployment ejbDeployment, EnterpriseBean bean) throws OpenEJBException {
        if (!autoCreateContainers) {
            throw new OpenEJBException("A container of type " + getType(bean) + " must be declared in the configuration file for bean: " + bean.getEjbName());
        }

        // get the container info (data used to build the container)
        ContainerInfo containerInfo = configFactory.configureService(containerInfoType);
        logger.info("Auto-creating a container for bean " + ejbDeployment.getDeploymentId() + ": Container(type=" + getType(bean) + ", id=" + containerInfo.id + ")");

        // if the is an MDB container we need to resolve the resource adapter
        String resourceAdapterId = containerInfo.properties.getProperty("ResourceAdapter");
        if (resourceAdapterId != null) {
            String newResourceId = getResourceId(ejbDeployment.getDeploymentId(), resourceAdapterId, null, null);
            if (resourceAdapterId != newResourceId) {
                containerInfo.properties.setProperty("ResourceAdapter", newResourceId);
            }
        }

        // install the container
        configFactory.install(containerInfo);
        return containerInfo.id;
    }

    private void processResourceRef(ResourceRef ref, EjbDeployment ejbDeployment, AppResources appResources) throws OpenEJBException {
        // skip destinations with a global jndi name
        String mappedName = ref.getMappedName();
        if (mappedName == null) mappedName = "";
        if ((mappedName).startsWith("jndi:")){
            return;
        }

        String refName = ref.getName();
        String refType = ref.getType();

        // skip references such as URLs which are automatically handled by the server
        if (ignoredReferenceTypes.contains(refType)) {
            return;
        }

        ResourceLink link = ejbDeployment.getResourceLink(refName);
        if (link == null) {
            String id = (mappedName.length() == 0) ? ref.getName() : mappedName;
            id = getResourceId(ejbDeployment.getDeploymentId(), id, refType, appResources);
            logger.info("Auto-linking resource-ref '" + refName + "' in bean " + ejbDeployment.getDeploymentId() + " to Resource(id=" + id + ")");

            link = new ResourceLink();
            link.setResId(id);
            link.setResRefName(refName);
            ejbDeployment.addResourceLink(link);
        } else {
            String id = getResourceId(ejbDeployment.getDeploymentId(), link.getResId(), refType, appResources);
            link.setResId(id);
            link.setResRefName(refName);
        }
    }

    private void processResourceEnvRef(JndiReference ref, EjbDeployment ejbDeployment, AppResources appResources) throws OpenEJBException {
        // skip destinations with a global jndi name
        String mappedName = (ref.getMappedName() == null)? "": ref.getMappedName();
        if (mappedName.startsWith("jndi:")){
            return;
        }

        String refName = ref.getName();
        String refType = ref.getType();

        // skip references such as SessionContext which are automatically handled by the server
        if (ignoredReferenceTypes.contains(refType)) {
            return;
        }

        ResourceLink link = ejbDeployment.getResourceLink(refName);
        if (link == null) {

            String id = (mappedName.length() == 0) ? refName : mappedName;
            id = getResourceEnvId(ejbDeployment.getDeploymentId(), id, refType, appResources);
            if (id == null) {
                // could be a session context ref
                return;
            }
            logger.info("Auto-linking resource-env-ref '" + refName + "' in bean " + ejbDeployment.getDeploymentId() + " to Resource(id=" + id + ")");

            link = new ResourceLink();
            link.setResId(id);
            link.setResRefName(refName);
            ejbDeployment.addResourceLink(link);
        } else {
            String id = getResourceEnvId(ejbDeployment.getDeploymentId(), link.getResId(), refType, appResources);
            link.setResId(id);
            link.setResRefName(refName);
        }
    }

    private static boolean skipMdb(Object bean) {
        return bean instanceof MessageDrivenBean && System.getProperty("duct tape") != null;
    }

    private static String getType(EnterpriseBean enterpriseBean) throws OpenEJBException {
        if (enterpriseBean instanceof org.apache.openejb.jee.EntityBean) {
            if (((org.apache.openejb.jee.EntityBean)enterpriseBean).getPersistenceType() == PersistenceType.CONTAINER) {
                return BeanTypes.CMP_ENTITY;
            } else {
                return BeanTypes.BMP_ENTITY;
            }
        } else if (enterpriseBean instanceof org.apache.openejb.jee.SessionBean) {
            if (((org.apache.openejb.jee.SessionBean) enterpriseBean).getSessionType() == SessionType.STATEFUL) {
                return BeanTypes.STATEFUL;
            } else if (((org.apache.openejb.jee.SessionBean) enterpriseBean).getSessionType() == SessionType.SINGLETON) {
                return BeanTypes.SINGLETON;
            } else if (((org.apache.openejb.jee.SessionBean) enterpriseBean).getSessionType() == SessionType.MANAGED) {
                return BeanTypes.MANAGED;
            } else {
                return BeanTypes.STATELESS;
            }
        } else if (enterpriseBean instanceof org.apache.openejb.jee.MessageDrivenBean) {
            return BeanTypes.MESSAGE;
        }
        throw new OpenEJBException("Unknown enterprise bean type " + enterpriseBean.getClass().getName());
    }

    private void deploy(AppModule app, PersistenceModule persistenceModule) throws OpenEJBException {
        if (!autoCreateResources) {
            return;
        }

        Persistence persistence = persistenceModule.getPersistence();
        for (PersistenceUnit unit : persistence.getPersistenceUnit()) {
            if (unit.getProvider() != null){
                logger.info("Configuring PersistenceUnit(name="+unit.getName()+", provider="+unit.getProvider()+")");
            } else {
                logger.info("Configuring PersistenceUnit(name="+unit.getName()+")");
            }

            Properties required = new Properties();

//            if (unit.getJtaDataSource() == null && unit.getNonJtaDataSource() == null){
//                unit.setJtaDataSource("JtaDataSource");
//                unit.setNonJtaDataSource("NonJtaDataSource");
//            } else if (unit.getJtaDataSource() == null){
//                unit.setJtaDataSource(unit.getNonJtaDataSource()+"Jta");
//            } else if (unit.getNonJtaDataSource() == null){
//                unit.setNonJtaDataSource(unit.getJtaDataSource()+"NonJta");
//            }

            logger.debug("raw <jta-data-source>" + unit.getJtaDataSource() + "</jta-datasource>");
            logger.debug("raw <non-jta-data-source>" + unit.getNonJtaDataSource() + "</non-jta-datasource>");

            unit.setJtaDataSource(normalizeResourceId(unit.getJtaDataSource()));
            unit.setNonJtaDataSource(normalizeResourceId(unit.getNonJtaDataSource()));

            logger.debug("normalized <jta-data-source>" + unit.getJtaDataSource() + "</jta-datasource>");
            logger.debug("normalized <non-jta-data-source>" + unit.getNonJtaDataSource() + "</non-jta-datasource>");

            if (logger.isDebugEnabled()){
                required.put("JtaManaged", "true");
                List<String> managed = configFactory.getResourceIds("DataSource", required);

                required.put("JtaManaged", "false");
                List<String> unmanaged = configFactory.getResourceIds("DataSource", required);

                required.clear();
                List<String> unknown = configFactory.getResourceIds("DataSource", required);

                logger.debug("Available DataSources");
                for (String name : managed) {
                    logger.debug("DataSource(name=" + name + ", JtaManaged=true)");
                }
                for (String name : unmanaged) {
                    logger.debug("DataSource(name=" + name + ", JtaManaged=false)");
                }
                for (String name : unknown) {
                    if (managed.contains(name)) continue;
                    if (unmanaged.contains(name)) continue;
                    logger.debug("DataSource(name=" + name + ", JtaManaged=<unknown>)");
                }
            }

            required.put("JtaManaged", "true");
            String jtaDataSourceId = findResourceId(unit.getJtaDataSource(), "DataSource", required, null);

            required.put("JtaManaged", "false");
            String nonJtaDataSourceId = findResourceId(unit.getNonJtaDataSource(), "DataSource", required, null);

            if (jtaDataSourceId != null && nonJtaDataSourceId != null){
                // Both DataSources were explicitly configured.
                setJtaDataSource(unit, jtaDataSourceId);
                setNonJtaDataSource(unit, nonJtaDataSourceId);
                continue;
            }

            //
            //  If the jta-data-source or the non-jta-data-source link to
            //  third party resources, then we can't do any auto config
            //  for them.  We give them what they asked for and move on.
            //
            if (jtaDataSourceId == null && nonJtaDataSourceId == null) {
                required.put("JtaManaged", NONE);

                jtaDataSourceId = findResourceId(unit.getJtaDataSource(), "DataSource", required, null);
                nonJtaDataSourceId = findResourceId(unit.getNonJtaDataSource(), "DataSource", required, null);

                if (jtaDataSourceId != null || nonJtaDataSourceId != null) {
                    if (jtaDataSourceId != null) setJtaDataSource(unit, jtaDataSourceId);
                    if (nonJtaDataSourceId != null) setNonJtaDataSource(unit, nonJtaDataSourceId);
                    continue;
                }
            }


            //  We are done with the most optimal configuration.
            //
            //  If both the jta-data-source and non-jta-data-source
            //  references were explicitly and correctly configured
            //  to existing datasource, we wouldn't get this far.
            //
            //  At this point we see if either we can't figure out
            //  if there's an issue with their configuration or
            //  if we can't intelligently complete their configuration.



            //
            //  Do both the jta-data-source and non-jta-data-source references
            //  point to the same datasource?
            //
            //  If so, then unlink the invalid one so defaulting rules can
            //  possibly fill in a good value.
            //

            required.put("JtaManaged", ANY);
            String possibleJta = findResourceId(unit.getJtaDataSource(), "DataSource", required, null);
            String possibleNonJta = findResourceId(unit.getNonJtaDataSource(), "DataSource", required, null);
            if (possibleJta != null && possibleJta == possibleNonJta){
                ResourceInfo dataSource = configFactory.getResourceInfo(possibleJta);

                String jtaManaged = (String) dataSource.properties.get("JtaManaged");

                logger.warning("PeristenceUnit(name=" + unit.getName() + ") invalidly refers to Resource(id=" + dataSource.id + ") as both its <jta-data-source> and <non-jta-data-source>.");

                if ("true".equalsIgnoreCase(jtaManaged)){
                    nonJtaDataSourceId = null;
                    unit.setNonJtaDataSource(null);

                } else if ("false".equalsIgnoreCase(jtaManaged)){
                    jtaDataSourceId = null;
                    unit.setJtaDataSource(null);
                }
            }

            //
            //  Do the jta-data-source and non-jta-data-source references
            //  point to innapropriately configured Resources?
            //
            checkUnitDataSourceRefs(unit);

            //
            //  Do either the jta-data-source and non-jta-data-source
            //  references point to the explicit name of a ServiceProvider?
            //
            if (jtaDataSourceId == null && nonJtaDataSourceId == null){
                jtaDataSourceId = findResourceProviderId(unit.getJtaDataSource());
                nonJtaDataSourceId = findResourceProviderId(unit.getNonJtaDataSource());

                // if one of them is not null we have a match on at least one
                // we can just create the second resource using the first as a template
                if (jtaDataSourceId != null || nonJtaDataSourceId != null){
                    Resource jtaResource = new Resource(jtaDataSourceId, "DataSource", jtaDataSourceId);
                    jtaResource.getProperties().setProperty("JtaManaged", "true");

                    Resource nonJtaResource = new Resource(nonJtaDataSourceId, "DataSource", nonJtaDataSourceId);
                    nonJtaResource.getProperties().setProperty("JtaManaged", "false");

                    if (jtaDataSourceId == null){
                        jtaResource.setId(nonJtaDataSourceId+"Jta");
                        jtaResource.setProvider(nonJtaDataSourceId);
                    } else if (nonJtaDataSourceId == null){
                        nonJtaResource.setId(jtaDataSourceId+"NonJta");
                        nonJtaResource.setProvider(jtaDataSourceId);
                    }

                    ResourceInfo jtaResourceInfo = configFactory.configureService(jtaResource, ResourceInfo.class);
                    ResourceInfo nonJtaResourceInfo = configFactory.configureService(nonJtaResource, ResourceInfo.class);

                    logAutoCreateResource(jtaResourceInfo, "DataSource", unit.getName());
                    jtaDataSourceId = installResource(unit.getName(), jtaResourceInfo);

                    logAutoCreateResource(nonJtaResourceInfo, "DataSource", unit.getName());
                    nonJtaDataSourceId = installResource(unit.getName(), nonJtaResourceInfo);

                    setJtaDataSource(unit, jtaDataSourceId);
                    setNonJtaDataSource(unit, nonJtaDataSourceId);
                    continue;
                }
            }

            // No data sources were specified: 
            // Look for defaults, see https://issues.apache.org/jira/browse/OPENEJB-1027
            if (jtaDataSourceId == null && nonJtaDataSourceId == null) {
                // We check for data sources matching the following names:
                // 1. The persistence unit id
                // 2. The web module id
                // 3. The web module context root
                // 4. The application module id
                List<String> ids = new ArrayList<String>();
                ids.add(unit.getName());
                for (WebModule webModule : app.getWebModules()) {
                    ids.add(webModule.getModuleId());
                    ids.add(webModule.getContextRoot());
                }
                ids.add(app.getModuleId());
                
                // Search for a matching data source
                for (String id : ids) {
                    //Try finding a jta managed data source
                    required.put("JtaManaged", "true");
                    jtaDataSourceId = findResourceId(id, "DataSource", required, null);

                    if (jtaDataSourceId == null) {
                        //No jta managed data source found. Try finding a non-jta managed
                        required.clear();
                        required.put("JtaManaged", "false");
                        nonJtaDataSourceId = findResourceId(id, "DataSource", required, null);
                    }
                    
                    if (jtaDataSourceId == null && nonJtaDataSourceId == null) {
                        // Neither jta nor non-jta managed data sources were found. try to find one with it unset
                        required.clear();
                        required.put("JtaManaged", NONE);
                        jtaDataSourceId = findResourceId(id, "DataSource", required, null);
                    }
                    
                    if (jtaDataSourceId != null || nonJtaDataSourceId != null) {
                        //We have found a default. Exit the loop
                        break;
                    }
                }
            }
            
            //
            //  If neither of the references are valid yet, then let's take
            //  the first valid datasource.
            //
            //  We won't fill in both jta-data-source and non-jta-data-source
            //  this way as the following code does a great job at determining
            //  if any of the existing data sources are a good match or if
            //  one needs to be generated.
            //
            if (jtaDataSourceId == null && nonJtaDataSourceId == null){

                required.clear();
                required.put("JtaManaged", "true");
                jtaDataSourceId = firstMatching("DataSource", required, null);

                if (jtaDataSourceId == null){
                    required.clear();
                    required.put("JtaManaged", "false");
                    nonJtaDataSourceId = firstMatching("DataSource", required, null);
                }
            }


            //
            //  Does the jta-data-source reference point an existing
            //  Resource in the system with JtaManaged=true?
            //
            //  If so, we can search for an existing datasource
            //  configured with identical properties and use it.
            //
            //  If that doesn't work, we can copy the jta-data-source
            //  and auto-create the missing non-jta-data-source
            //  using it as a template, applying the overrides,
            //  and finally setting JtaManaged=false
            //

            if (jtaDataSourceId != null && nonJtaDataSourceId == null){

                ResourceInfo jtaResourceInfo = configFactory.getResourceInfo(jtaDataSourceId);

                Properties jtaProperties = jtaResourceInfo.properties;

                if (jtaProperties.containsKey("JtaManaged")){

                    // Strategy 1: Best match search

                    required.clear();
                    required.put("JtaManaged", "false");

                    for (String key : asList("JdbcDriver", "JdbcUrl")) {
                        if (jtaProperties.containsKey(key)) required.put(key, jtaProperties.get(key));
                    }

                    nonJtaDataSourceId = firstMatching("DataSource", required, null);

                    // Strategy 2: Copy

                    if (nonJtaDataSourceId == null) {
                        ResourceInfo nonJtaResourceInfo = copy(jtaResourceInfo);
                        nonJtaResourceInfo.id = jtaResourceInfo.id + "NonJta";

                        Properties overrides = ConfigurationFactory.getSystemProperties(nonJtaResourceInfo.id, nonJtaResourceInfo.service);
                        nonJtaResourceInfo.properties.putAll(overrides);
                        nonJtaResourceInfo.properties.setProperty("JtaManaged", "false");

                        logAutoCreateResource(nonJtaResourceInfo, "DataSource", unit.getName());
                        logger.info("configureService.configuring", nonJtaResourceInfo.id, nonJtaResourceInfo.service, jtaResourceInfo.id);

                        nonJtaDataSourceId = installResource(unit.getName(), nonJtaResourceInfo);
                    }
                }

            }

            //
            //  Does the jta-data-source reference point an existing
            //  Resource in the system with JtaManaged=false?
            //
            //  If so, we can search for an existing datasource
            //  configured with identical properties and use it.
            //
            //  If that doesn't work, we can copy the jta-data-source
            //  and auto-create the missing non-jta-data-source
            //  using it as a template, applying the overrides,
            //  and finally setting JtaManaged=false
            //

            if (nonJtaDataSourceId != null && jtaDataSourceId == null){

                ResourceInfo nonJtaResourceInfo = configFactory.getResourceInfo(nonJtaDataSourceId);

                Properties nonJtaProperties = nonJtaResourceInfo.properties;

                if (nonJtaProperties.containsKey("JtaManaged")){

                    // Strategy 1: Best match search

                    required.clear();
                    required.put("JtaManaged", "true");

                    for (String key : asList("JdbcDriver", "JdbcUrl")) {
                        if (nonJtaProperties.containsKey(key)) required.put(key, nonJtaProperties.get(key));
                    }

                    jtaDataSourceId = firstMatching("DataSource", required, null);

                    // Strategy 2: Copy

                    if (jtaDataSourceId == null) {
                        ResourceInfo jtaResourceInfo = copy(nonJtaResourceInfo);
                        jtaResourceInfo.id = nonJtaResourceInfo.id + "Jta";

                        Properties overrides = ConfigurationFactory.getSystemProperties(jtaResourceInfo.id, jtaResourceInfo.service);
                        jtaResourceInfo.properties.putAll(overrides);
                        jtaResourceInfo.properties.setProperty("JtaManaged", "true");

                        logAutoCreateResource(jtaResourceInfo, "DataSource", unit.getName());
                        logger.info("configureService.configuring", jtaResourceInfo.id, jtaResourceInfo.service, nonJtaResourceInfo.id);

                        jtaDataSourceId = installResource(unit.getName(), jtaResourceInfo);
                    }
                }

            }

            //
            //  By this point if we've found anything at all, both
            //  jta-data-source and non-jta-data-source should be
            //  filled in (provided they aren't using a third party
            //  data source).
            //
            //  Should both references still be null
            //  we can just take a shot in the dark and auto-create
            //  them them both using the built-in templates for jta
            //  and non-jta default datasources.  These are supplied
            //  via the service-jar.xml file.
            //
            if (jtaDataSourceId == null && nonJtaDataSourceId == null){
                required.put("JtaManaged", "true");
                jtaDataSourceId = autoCreateResource("DataSource", required, unit.getName());

                required.put("JtaManaged", "false");
                nonJtaDataSourceId = autoCreateResource("DataSource", required, unit.getName());
            }

            if (jtaDataSourceId != null) setJtaDataSource(unit, jtaDataSourceId);
            if (nonJtaDataSourceId != null) setNonJtaDataSource(unit, nonJtaDataSourceId);
        }

        // Attempt to resolve the jar-file references to things in the actual classpath
        // Usually these paths are relative to the persistence unit root inside the archive
        // but in a classpath things are spread out side-by-side
        if ("classpath.ear".equals(app.getModuleId())) {
            Map<String, File> map = null;
            for (PersistenceUnit unit : persistence.getPersistenceUnit()) {
                
                List<String> jarFiles = unit.getJarFile();
                for (int i = 0; i < jarFiles.size(); i++) {
                    
                    String earRelativeJarFileLocation = jarFiles.get(i);
                    if (map == null) {
                        if (app.getAdditionalLibraries().size() > 0) {
                            map = mapLibs(app.getAdditionalLibraries());
                        } else {
                            map = mapLibs(classPathLibs(app));
                        }
                    }

                    String resolvedPath = resolveJarFileRef(map, earRelativeJarFileLocation);


                    if (resolvedPath != null) {
                        logger.info("Adjusting PersistenceUnit(id="+unit.getName()+") <jar-file>"+earRelativeJarFileLocation+"</jar-file> to <jar-file>"+resolvedPath+"</jar-file>");

                        jarFiles.set(i, resolvedPath);
                    }

                }
            }
        }
    }

    private String resolveJarFileRef(Map<String, File> map, String earRelativeJarFileLocation) {
        File resolved = map.get(earRelativeJarFileLocation);

        if (resolved != null) return resolved.getAbsolutePath();

        String relative = earRelativeJarFileLocation;

        search: while (resolved == null) {
            for (String path : map.keySet()) {
                if (path.endsWith(relative)) {
                    resolved = map.get(path);
                    break search;
                }
            }

            // Trim off a part of the path and try again
            int i = relative.indexOf('/');
            if (i >= 0) {
                relative = relative.substring(i+1);
            } else {
                return null;
            }
        }

        return (resolved == null) ? null : resolved.getAbsolutePath();
    }


    private List<URL> classPathLibs(AppModule app) {
        try {
            return DeploymentsResolver.filteredClasspath(app.getClassLoader());
        } catch (IOException e) {
            logger.warning("Unable to determine jars in classpath for resolving persistenceUnit.jarFile list");
            return Collections.EMPTY_LIST;
        }
    }

    private Map<String, File> mapLibs(List<URL> libs) {
        Map<String, File> map = new HashMap<String, File>();

        for (URL url : libs) {
            File file = URLs.toFile(url);
            map.put(file.getAbsolutePath(), file);
        }
        
        return map;
    }


    private void setNonJtaDataSource(PersistenceUnit unit, String current) {


        String previous = unit.getNonJtaDataSource();

        if (!current.equals(previous)) {

            logger.info("Adjusting PersistenceUnit " + unit.getName() + " <non-jta-data-source> to Resource ID '" + current + "' from '" + previous + "'");

        }

        unit.setNonJtaDataSource(current);
    }

    private void setJtaDataSource(PersistenceUnit unit, String current) {


        String previous = unit.getJtaDataSource();

        if (!current.equals(previous)) {

            logger.info("Adjusting PersistenceUnit " + unit.getName() + " <jta-data-source> to Resource ID '" + current + "' from '" + previous + "'");

        }

        unit.setJtaDataSource(current);
    }

    private ResourceInfo copy(ResourceInfo a) {
        ResourceInfo b = new ResourceInfo();
        b.id = a.id;
        b.service = a.service;
        b.className = a.className;
        b.codebase = a.codebase;
        b.displayName = a.displayName;
        b.description = a.description;
        b.factoryMethod = a.factoryMethod;
        b.constructorArgs.addAll(a.constructorArgs);
        b.types.addAll(a.types);
        b.properties = new SuperProperties();
        b.properties.putAll(a.properties);

        return b;
    }

    private void checkUnitDataSourceRefs(PersistenceUnit unit) throws OpenEJBException {
        Properties required = new Properties();

        // check that non-jta-data-source does NOT point to a JtaManaged=true datasource

        required.put("JtaManaged", "true");

        String invalidNonJta = findResourceId(unit.getNonJtaDataSource(), "DataSource", required, null);

        if (invalidNonJta != null){
            throw new OpenEJBException("PeristenceUnit "+unit.getName()+" <non-jta-data-source> points to a jta managed Resource.  Update Resource \""+invalidNonJta +"\" to \"JtaManaged=false\", use a different Resource, or delete the <non-jta-data-source> element and a default will be supplied if possible.");
        }


        // check that jta-data-source does NOT point to a JtaManaged=false datasource

        required.put("JtaManaged", "false");

        String invalidJta = findResourceId(unit.getJtaDataSource(), "DataSource", required, null);

        if (invalidJta != null){
            throw new OpenEJBException("PeristenceUnit "+unit.getName()+" <jta-data-source> points to a non jta managed Resource.  Update Resource \""+invalidJta +"\" to \"JtaManaged=true\", use a different Resource, or delete the <jta-data-source> element and a default will be supplied if possible.");
        }
    }

    private String findResourceProviderId(String resourceId) throws OpenEJBException {
        if (resourceId == null) return null;

        if (hasServiceProvider(resourceId)) {
            return resourceId;
        }

        resourceId = toShortName(resourceId);
        if (hasServiceProvider(resourceId)) {
            return resourceId;
        }

        return null;
    }

    private String getResourceId(String beanName, String resourceId, String type, AppResources appResources) throws OpenEJBException {
        return getResourceId(beanName, resourceId, type, null, appResources);
    }

    private String getResourceId(String beanName, String resourceId, String type, Properties required, AppResources appResources) throws OpenEJBException {
        resourceId = normalizeResourceId(resourceId);

        if(resourceId == null){
            return null;
        }

        if (appResources == null) appResources = new AppResources();

        // skip references such as URL which are automatically handled by the server
        if (type != null && ignoredReferenceTypes.contains(type)) {
            return null;
        }


        // check for existing resource with specified resourceId and type and properties
        String id = findResourceId(resourceId, type, required, appResources);
        if (id != null) return id;

        // expand search to any type -- may be asking for a reference to a sub-type
        id = findResourceId(resourceId, null, required, appResources);
        if (id != null) return id;


        // throw an exception or log an error
        String shortName = toShortName(resourceId);
        String message = "No existing resource found while attempting to Auto-link unmapped resource-ref '" + resourceId + "' of type '" + type  + "' for '" + beanName + "'.  Looked for Resource(id=" + resourceId + ") and Resource(id=" + shortName + ")";
        if (!autoCreateResources){
            throw new OpenEJBException(message);
        }
        logger.debug(message);

        // if there is a provider with the specified name. use it
        if (hasServiceProvider(resourceId)) {
            ResourceInfo resourceInfo = configFactory.configureService(resourceId, ResourceInfo.class);
            return installResource(beanName, resourceInfo);
        } else if (hasServiceProvider(shortName)) {
            ResourceInfo resourceInfo = configFactory.configureService(shortName, ResourceInfo.class);
            return installResource(beanName, resourceInfo);
        }

        // if there are any resources of the desired type, use the first one
        id = firstMatching(type, required, appResources);
        if (id != null) return id;

        // Auto create a resource using the first provider that can supply a resource of the desired type
        return autoCreateResource(type, required, beanName);
    }

    private String autoCreateResource(String type, Properties required, String beanName) throws OpenEJBException {
        String resourceId;
        resourceId = ServiceUtils.getServiceProviderId(type, required);
        if (resourceId == null) {
            throw new OpenEJBException("No provider available for resource-ref '" + resourceId + "' of type '" + type + "' for '" + beanName + "'.");
        }
        ResourceInfo resourceInfo = configFactory.configureService(resourceId, ResourceInfo.class);

        logAutoCreateResource(resourceInfo, type, beanName);
        return installResource(beanName, resourceInfo);
    }

    private void logAutoCreateResource(ResourceInfo resourceInfo, String type, String beanName) {
        logger.info("Auto-creating a Resource with id '" + resourceInfo.id +  "' of type '" + type  + " for '" + beanName + "'.");
    }

    private String firstMatching(String type, Properties required, AppResources appResources) {
        List<String> resourceIds = getResourceIds(appResources, type, required);
        String idd = null;
        if (resourceIds.size() > 0) {
            idd = resourceIds.get(0);
        }
        return idd;
    }

    private String findResourceId(String resourceId, String type, Properties required, AppResources appResources) {
        if (resourceId == null) return null;

        resourceId = normalizeResourceId(resourceId);

        // check for existing resource with specified resourceId
        List<String> resourceIds = getResourceIds(appResources, type, required);
        for (String id : resourceIds) {
            if (id.equalsIgnoreCase(resourceId)) return id;
        }

        // check for existing resource with shortName
        String shortName = toShortName(resourceId);
        for (String id : resourceIds) {
            if (id.equalsIgnoreCase(shortName)) return id;
        }
        return null;
    }

    private List<String> getResourceIds(AppResources appResources, String type, Properties required) {
        List<String> resourceIds;
        resourceIds = new ArrayList<String>();
        if (appResources != null) resourceIds.addAll(appResources.getResourceIds(type));
        resourceIds.addAll(configFactory.getResourceIds(type, required));
        return resourceIds;
    }

    private String toShortName(String resourceId) {
        // check for an existing resource using the short name (everything ever the final '/')
        String shortName = resourceId.replaceFirst(".*/", "");
        return shortName;
    }

    private String normalizeResourceId(String resourceId) {
        if (resourceId == null) return null;

        // strip off "java:comp/env"
        if (resourceId.startsWith("java:comp/env")) {
            resourceId = resourceId.substring("java:comp/env".length());
        }

        // strip off "java:openejb/Resource"
        if (resourceId.startsWith("java:openejb/Resource")) {
            resourceId = resourceId.substring("java:openejb/Resource".length());
        }

        // strip off "java:openejb/Connector"
        if (resourceId.startsWith("java:openejb/Connector")) {
            resourceId = resourceId.substring("java:openejb/Connector".length());
        }

        return resourceId;
    }

    private String installResource(String beanName, ResourceInfo resourceInfo) throws OpenEJBException {
        String resourceAdapterId = resourceInfo.properties.getProperty("ResourceAdapter");
        if (resourceAdapterId != null) {
            String newResourceId = getResourceId(beanName, resourceAdapterId, null, null);
            if (resourceAdapterId != newResourceId) {
                resourceInfo.properties.setProperty("ResourceAdapter", newResourceId);
            }
        }
        String dataSourceId = resourceInfo.properties.getProperty("DataSource");
        if (dataSourceId != null && dataSourceId.length() > 0) {
            String newResourceId = getResourceId(beanName, dataSourceId, null, null);
            if (dataSourceId != newResourceId) {
                resourceInfo.properties.setProperty("DataSource", newResourceId);
            }
        }

        configFactory.install(resourceInfo);
        return resourceInfo.id;
    }

    private String getResourceEnvId(String beanName, String resourceId, String type, AppResources appResources) throws OpenEJBException {
        if(resourceId == null){
            return null;
        }
        if (appResources == null) appResources = new AppResources();

        // skip references such as URLs which are automatically handled by the server
        if (ignoredReferenceTypes.contains(type)) {
            return null;
        }

        resourceId = normalizeResourceId(resourceId);

        // check for existing resource with specified resourceId
        List<String> resourceEnvIds = getResourceIds(appResources, type, null);
        for (String id : resourceEnvIds) {
            if (id.equalsIgnoreCase(resourceId)) return id;
        }

        // throw an exception or log an error
        String message = "No existing resource found while attempting to Auto-link unmapped resource-env-ref '" + resourceId + "' of type '" + type  + "' for '" + beanName + "'.  Looked for Resource(id=" + resourceId + ")";
        if (!autoCreateResources){
            throw new OpenEJBException(message);
        }
        logger.debug(message);


        // Auto create a resource using the first provider that can supply a resource of the desired type
        String providerId = ServiceUtils.getServiceProviderId(type);
        if (providerId == null) {
            // if there are any existing resources of the desired type, use the first one
            if (resourceEnvIds.size() > 0) {
                return resourceEnvIds.get(0);
            }
            throw new OpenEJBException("No provider available for resource-env-ref '" + resourceId + "' of type '" + type + "' for '" + beanName + "'.");
        }

        Resource resource = new Resource(resourceId, null, providerId);
        resource.getProperties().setProperty("destination", resourceId);

        ResourceInfo resourceInfo = configFactory.configureService(resource, ResourceInfo.class);
        logAutoCreateResource(resourceInfo, type, beanName);
        return installResource(beanName, resourceInfo);
    }

    private String getUsableContainer(Class<? extends ContainerInfo> containerInfoType, Object bean, AppResources appResources) {
        if (bean instanceof MessageDrivenBean) {
            MessageDrivenBean messageDrivenBean = (MessageDrivenBean) bean;
            String messagingType = messageDrivenBean.getMessagingType();
            List<String> containerIds = appResources.containerIdsByType.get(messagingType);
            if (containerIds != null && !containerIds.isEmpty()) {
                return containerIds.get(0);
            }
        }

        for (ContainerInfo containerInfo : configFactory.getContainerInfos()) {
            if (containerInfo.getClass().equals(containerInfoType)){
                // MDBs must match message listener interface type
                if (bean instanceof MessageDrivenBean) {
                    MessageDrivenBean messageDrivenBean = (MessageDrivenBean) bean;
                    String messagingType = messageDrivenBean.getMessagingType();
                    if (containerInfo.properties.get("MessageListenerInterface").equals(messagingType)) {
                        return containerInfo.id;
                    }
                } else {
                    return containerInfo.id;
                }
            }
        }

        return null;
    }

    private static class AppResources {
        @SuppressWarnings({"MismatchedQueryAndUpdateOfCollection"})
        private final Set<String> resourceAdapterIds = new TreeSet<String>();
        private final Map<String,List<String>> resourceIdsByType = new TreeMap<String,List<String>>();
        private final Map<String,List<String>> resourceEnvIdsByType = new TreeMap<String,List<String>>();
        private final Map<String,List<String>> containerIdsByType = new TreeMap<String,List<String>>();

        public AppResources() {
        }

        public AppResources(AppModule appModule) {

            //
            // DEVELOPERS NOTE:  if you change the id generation code here, you must change
            // the id generation code in ConfigurationFactory.configureApplication(AppModule appModule)
            //

            for (ConnectorModule connectorModule : appModule.getResourceModules()) {
                Connector connector = connectorModule.getConnector();

                ResourceAdapter resourceAdapter = connector.getResourceAdapter();
                if (resourceAdapter.getResourceAdapterClass() != null) {
                    String resourceAdapterId;
                    if (resourceAdapter.getId() != null) {
                        resourceAdapterId = resourceAdapter.getId();
                    } else {
                        resourceAdapterId = connectorModule.getModuleId() + "RA";
                    }
                    resourceAdapterIds.add(resourceAdapterId);
                }

                OutboundResourceAdapter outbound = resourceAdapter.getOutboundResourceAdapter();
                if (outbound != null) {
                    for (ConnectionDefinition connection : outbound.getConnectionDefinition()) {
                        String type = connection.getConnectionFactoryInterface();

                        String resourceId;
                        if (connection.getId() != null) {
                            resourceId = connection.getId();
                        } else if (outbound.getConnectionDefinition().size() == 1) {
                            resourceId = connectorModule.getModuleId();
                        } else {
                            resourceId = connectorModule.getModuleId() + "-" + type;
                        }

                        List<String> resourceIds = resourceIdsByType.get(type);
                        if (resourceIds == null) {
                            resourceIds = new ArrayList<String>();
                            resourceIdsByType.put(type, resourceIds);
                        }
                        resourceIds.add(resourceId);
                    }
                }

                InboundResource inbound = resourceAdapter.getInboundResourceAdapter();
                if (inbound != null) {
                    for (MessageListener messageListener : inbound.getMessageAdapter().getMessageListener()) {
                        String type = messageListener.getMessageListenerType();

                        String containerId;
                        if (messageListener.getId() != null) {
                            containerId = messageListener.getId();
                        } else if (inbound.getMessageAdapter().getMessageListener().size() == 1) {
                            containerId = connectorModule.getModuleId();
                        } else {
                            containerId = connectorModule.getModuleId() + "-" + type;
                        }

                        List<String> containerIds = containerIdsByType.get(type);
                        if (containerIds == null) {
                            containerIds = new ArrayList<String>();
                            containerIdsByType.put(type, containerIds);
                        }
                        containerIds.add(containerId);
                    }
                }

                for (AdminObject adminObject : resourceAdapter.getAdminObject()) {
                    String type = adminObject.getAdminObjectInterface();

                    String resourceEnvId;
                    if (adminObject.getId() != null) {
                        resourceEnvId = adminObject.getId();
                    } else if (resourceAdapter.getAdminObject().size() == 1) {
                        resourceEnvId = connectorModule.getModuleId();
                    } else {
                        resourceEnvId = connectorModule.getModuleId() + "-" + type;
                    }

                    List<String> resourceEnvIds = resourceEnvIdsByType.get(type);
                    if (resourceEnvIds == null) {
                        resourceEnvIds = new ArrayList<String>();
                        resourceEnvIdsByType.put(type, resourceEnvIds);
                    }
                    resourceEnvIds.add(resourceEnvId);
                }
            }

        }

        public List<String> getResourceIds(String type) {
            if (type == null) {
                List<String> allResourceIds = new ArrayList<String>();
                for (List<String> resourceIds : resourceIdsByType.values()) {
                    allResourceIds.addAll(resourceIds);
                }
                return allResourceIds;
            }

            List<String> resourceIds = resourceIdsByType.get(type);
            if (resourceIds != null) {
                return resourceIds;
            }
            return Collections.emptyList();
        }

        public List<String> getResourceEnvIds(String type) {
            if (type != null) {
                List<String> resourceIds = resourceEnvIdsByType.get(type);
                if (resourceIds != null) {
                    return resourceIds;
                }
            }
            return Collections.emptyList();
        }

        public List<String> getContainerIds() {
            ArrayList<String> ids = new ArrayList<String>();
            for (List<String> list : containerIdsByType.values()) {
                ids.addAll(list);
            }
            return ids;
        }
    }
}