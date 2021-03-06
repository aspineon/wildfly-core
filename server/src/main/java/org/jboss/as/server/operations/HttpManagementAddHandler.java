/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2010, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.as.server.operations;


import static org.jboss.as.remoting.RemotingHttpUpgradeService.HTTP_UPGRADE_REGISTRY;
import static org.jboss.as.server.mgmt.HttpManagementResourceDefinition.SECURE_SOCKET_BINDING;
import static org.jboss.as.server.mgmt.HttpManagementResourceDefinition.SOCKET_BINDING;
import static org.jboss.as.server.mgmt.HttpManagementResourceDefinition.SOCKET_BINDING_CAPABILITY_NAME;
import static org.jboss.as.server.mgmt.UndertowHttpManagementService.EXTENSIBLE_HTTP_MANAGEMENT_CAPABILITY;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;

import javax.net.ssl.SSLContext;

import org.jboss.as.controller.CapabilityServiceBuilder;
import org.jboss.as.controller.CapabilityServiceTarget;
import org.jboss.as.controller.ControlledProcessStateService;
import org.jboss.as.controller.ModelController;
import org.jboss.as.controller.OperationContext;
import org.jboss.as.controller.OperationFailedException;
import org.jboss.as.controller.ProcessType;
import org.jboss.as.controller.RunningMode;
import org.jboss.as.controller.management.BaseHttpInterfaceAddStepHandler;
import org.jboss.as.controller.management.HttpInterfaceCommonPolicy;
import org.jboss.as.controller.registry.Resource;
import org.jboss.as.domain.http.server.ConsoleMode;
import org.jboss.as.domain.http.server.ManagementHttpRequestProcessor;
import org.jboss.as.domain.management.SecurityRealm;
import org.jboss.as.network.SocketBinding;
import org.jboss.as.network.SocketBindingManager;
import org.jboss.as.remoting.RemotingHttpUpgradeService;
import org.jboss.as.remoting.RemotingServices;
import org.jboss.as.remoting.management.ManagementChannelRegistryService;
import org.jboss.as.remoting.management.ManagementRemotingServices;
import org.jboss.as.server.ExternalManagementRequestExecutor;
import org.jboss.as.server.ServerEnvironment;
import org.jboss.as.server.ServerEnvironmentService;
import org.jboss.as.server.Services;
import org.jboss.as.server.logging.ServerLogger;
import org.jboss.as.server.mgmt.HttpManagementRequestsService;
import org.jboss.as.server.mgmt.HttpManagementResourceDefinition;
import org.jboss.as.server.mgmt.HttpShutdownService;
import org.jboss.as.server.mgmt.ManagementWorkerService;
import org.jboss.as.server.mgmt.UndertowHttpManagementService;
import org.jboss.as.server.mgmt.domain.HttpManagement;
import org.jboss.dmr.ModelNode;
import org.jboss.msc.service.ServiceController;
import org.jboss.msc.service.ServiceName;
import org.wildfly.security.auth.server.HttpAuthenticationFactory;
import org.wildfly.security.manager.WildFlySecurityManager;
import org.xnio.XnioWorker;

import io.undertow.server.ListenerRegistry;


/**
 * A handler that activates the HTTP management API on a Server.
 *
 * @author Jason T. Greene
 * @author <a href="mailto:darran.lofthouse@jboss.com">Darran Lofthouse</a>
 */
public class HttpManagementAddHandler extends BaseHttpInterfaceAddStepHandler {

    private static final String HTTP_AUTHENTICATION_FACTORY_CAPABILITY = "org.wildfly.security.http-authentication-factory";

    public static final HttpManagementAddHandler INSTANCE = new HttpManagementAddHandler();

    private HttpManagementAddHandler() {
        super(HttpManagementResourceDefinition.ATTRIBUTE_DEFINITIONS);
    }

    @Override
    protected void populateModel(OperationContext context, ModelNode operation, Resource resource)
            throws OperationFailedException {
        super.populateModel(context, operation, resource);
        HttpManagementResourceDefinition.addAttributeValidator(context);
    }

    @Override
    protected boolean requiresRuntime(OperationContext context) {
        return super.requiresRuntime(context)
                && (context.getProcessType() != ProcessType.EMBEDDED_SERVER || context.getRunningMode() != RunningMode.ADMIN_ONLY);
    }

    @Override
    protected List<ServiceName> installServices(OperationContext context, HttpInterfaceCommonPolicy commonPolicy, ModelNode model) throws OperationFailedException {
        final CapabilityServiceTarget serviceTarget = context.getCapabilityServiceTarget();

        // Socket-binding reference based config
        final String socketBindingName = SOCKET_BINDING.resolveModelAttribute(context, model).asStringOrNull();
        final String secureSocketBindingName = SECURE_SOCKET_BINDING.resolveModelAttribute(context, model).asStringOrNull();

        // Log the config
        if (socketBindingName != null) {
            if (secureSocketBindingName != null) {
                ServerLogger.ROOT_LOGGER.creatingHttpManagementServiceOnSocketAndSecureSocket(socketBindingName,
                        secureSocketBindingName);
            } else {
                ServerLogger.ROOT_LOGGER.creatingHttpManagementServiceOnSocket(socketBindingName);
            }
        } else if (secureSocketBindingName != null) {
            ServerLogger.ROOT_LOGGER.creatingHttpManagementServiceOnSecureSocket(secureSocketBindingName);
        }

        ConsoleMode consoleMode = consoleMode(commonPolicy.isConsoleEnabled(), context.getRunningMode() == RunningMode.ADMIN_ONLY);

        // Track active requests
        final ServiceName requestProcessorName = UndertowHttpManagementService.SERVICE_NAME.append("requests");
        HttpManagementRequestsService.installService(requestProcessorName, serviceTarget);

        NativeManagementServices.installManagementWorkerService(serviceTarget, context.getServiceRegistry(false));

        ServerEnvironment environment = (ServerEnvironment) context.getServiceRegistry(false).getRequiredService(ServerEnvironmentService.SERVICE_NAME).getValue();
        final UndertowHttpManagementService undertowService = new UndertowHttpManagementService(consoleMode, environment.getProductConfig().getConsoleSlot());
        CapabilityServiceBuilder<HttpManagement> undertowBuilder = serviceTarget.addCapability(EXTENSIBLE_HTTP_MANAGEMENT_CAPABILITY, undertowService).addDependency(Services.JBOSS_SERVER_CONTROLLER, ModelController.class, undertowService.getModelControllerInjector())
                .addCapabilityRequirement("org.wildfly.management.socket-binding-manager", SocketBindingManager.class, undertowService.getSocketBindingManagerInjector())
                .addDependency(ControlledProcessStateService.SERVICE_NAME, ControlledProcessStateService.class, undertowService.getControlledProcessStateServiceInjector())
                .addDependency(RemotingServices.HTTP_LISTENER_REGISTRY, ListenerRegistry.class, undertowService.getListenerRegistry())
                .addDependency(requestProcessorName, ManagementHttpRequestProcessor.class, undertowService.getRequestProcessorValue())
                .addDependency(ManagementWorkerService.SERVICE_NAME, XnioWorker.class, undertowService.getWorker())
                .addDependency(ExternalManagementRequestExecutor.SERVICE_NAME, Executor.class, undertowService.getManagementExecutor())
                .addInjection(undertowService.getAllowedOriginsInjector(), commonPolicy.getAllowedOrigins());

        if (socketBindingName != null) {
            undertowBuilder.addCapabilityRequirement(SOCKET_BINDING_CAPABILITY_NAME, SocketBinding.class, undertowService.getSocketBindingInjector(), socketBindingName);
        }
        if (secureSocketBindingName != null) {
            undertowBuilder.addCapabilityRequirement(SOCKET_BINDING_CAPABILITY_NAME, SocketBinding.class, undertowService.getSecureSocketBindingInjector(), secureSocketBindingName);
        }

        String httpAuthenticationFactory = commonPolicy.getHttpAuthenticationFactory();
        String securityRealm = commonPolicy.getSecurityRealm();
        if (httpAuthenticationFactory != null) {
            undertowBuilder.addCapabilityRequirement(HTTP_AUTHENTICATION_FACTORY_CAPABILITY,
                    HttpAuthenticationFactory.class, undertowService.getHttpAuthenticationFactoryInjector(), httpAuthenticationFactory);
        }
        if (securityRealm != null) {
            SecurityRealm.ServiceUtil.addDependency(undertowBuilder, undertowService.getSecurityRealmInjector(), securityRealm);
        }
        if(httpAuthenticationFactory == null && securityRealm == null) {
            ServerLogger.ROOT_LOGGER.httpManagementInterfaceIsUnsecured();
        }
        String sslContext = commonPolicy.getSSLContext();
        if (sslContext != null) {
            undertowBuilder.addCapabilityRequirement(SSL_CONTEXT_CAPABILITY, SSLContext.class, undertowService.getSSLContextInjector(), sslContext);
        }

        undertowBuilder.install();

        // Add service preventing the server from shutting down
        final HttpShutdownService shutdownService = new HttpShutdownService();
        final ServiceName shutdownName = UndertowHttpManagementService.SERVICE_NAME.append("shutdown");
        serviceTarget.addService(shutdownName, shutdownService)
                .addDependency(requestProcessorName, ManagementHttpRequestProcessor.class, shutdownService.getProcessorValue())
                .addDependency(Services.JBOSS_SERVER_EXECUTOR, Executor.class, shutdownService.getExecutorValue())
                .addDependency(ManagementChannelRegistryService.SERVICE_NAME, ManagementChannelRegistryService.class, shutdownService.getMgmtChannelRegistry())
                .addDependency(UndertowHttpManagementService.SERVICE_NAME)
                .setInitialMode(ServiceController.Mode.ACTIVE)
                .install();

        if(commonPolicy.isHttpUpgradeEnabled()) {
            final String hostName = WildFlySecurityManager.getPropertyPrivileged(ServerEnvironment.NODE_NAME, null);

            NativeManagementServices.installRemotingServicesIfNotInstalled(serviceTarget, hostName, context.getServiceRegistry(false));
            final String httpConnectorName;
            if (socketBindingName != null || (secureSocketBindingName == null)) {
                httpConnectorName = ManagementRemotingServices.HTTP_CONNECTOR;
            } else {
                httpConnectorName = ManagementRemotingServices.HTTPS_CONNECTOR;
            }

            RemotingHttpUpgradeService.installServices(context, ManagementRemotingServices.HTTP_CONNECTOR, httpConnectorName,
                    ManagementRemotingServices.MANAGEMENT_ENDPOINT, commonPolicy.getConnectorOptions(), securityRealm, commonPolicy.getSaslAuthenticationFactory());

            return Arrays.asList(UndertowHttpManagementService.SERVICE_NAME, HTTP_UPGRADE_REGISTRY.append(httpConnectorName));
        }
        return Collections.singletonList(UndertowHttpManagementService.SERVICE_NAME);
    }

    private ConsoleMode consoleMode(boolean consoleEnabled, boolean adminOnly) {
        return consoleEnabled ? adminOnly ?  ConsoleMode.ADMIN_ONLY : ConsoleMode.CONSOLE : ConsoleMode.NO_CONSOLE;
    }

}
