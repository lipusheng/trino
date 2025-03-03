/*
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
package io.trino.server;

import com.google.common.collect.ImmutableList;
import io.airlift.log.Logger;
import io.trino.connector.ConnectorManager;
import io.trino.eventlistener.EventListenerManager;
import io.trino.execution.resourcegroups.ResourceGroupManager;
import io.trino.filesystem.FileSystemClientManager;
import io.trino.metadata.MetadataManager;
import io.trino.queryeditorui.store.connectors.ConnectorCache;
import io.trino.security.AccessControlManager;
import io.trino.security.GroupProviderManager;
import io.trino.server.security.CertificateAuthenticatorManager;
import io.trino.server.security.PasswordAuthenticatorManager;
import io.trino.spi.Plugin;
import io.trino.spi.block.BlockEncoding;
import io.trino.spi.classloader.ThreadContextClassLoader;
import io.trino.spi.connector.ConnectorFactory;
import io.trino.spi.eventlistener.EventListenerFactory;
import io.trino.spi.filesystem.FileSystemClientFactory;
import io.trino.spi.resourcegroups.ResourceGroupConfigurationManagerFactory;
import io.trino.spi.security.CertificateAuthenticatorFactory;
import io.trino.spi.security.GroupProviderFactory;
import io.trino.spi.security.PasswordAuthenticatorFactory;
import io.trino.spi.security.SystemAccessControlFactory;
import io.trino.spi.session.SessionPropertyConfigurationManagerFactory;
import io.trino.spi.type.ParametricType;
import io.trino.spi.type.Type;

import javax.annotation.concurrent.ThreadSafe;
import javax.inject.Inject;

import java.net.URL;
import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static com.google.common.base.Preconditions.checkState;
import static io.trino.metadata.FunctionExtractor.extractFunctions;
import static java.util.Objects.requireNonNull;

@ThreadSafe
public class PluginManager
{
    private static final ImmutableList<String> SPI_PACKAGES = ImmutableList.<String>builder()
            .add("io.trino.spi.")
            .add("com.fasterxml.jackson.annotation.")
            .add("io.airlift.slice.")
            .add("org.openjdk.jol.")
            .build();

    private static final Logger log = Logger.get(PluginManager.class);

    private final PluginsProvider pluginsProvider;
    private final ConnectorManager connectorManager;
    private final MetadataManager metadataManager;
    private final ResourceGroupManager<?> resourceGroupManager;
    private final AccessControlManager accessControlManager;
    private final Optional<PasswordAuthenticatorManager> passwordAuthenticatorManager;
    private final CertificateAuthenticatorManager certificateAuthenticatorManager;
    private final EventListenerManager eventListenerManager;
    private final GroupProviderManager groupProviderManager;
    private final SessionPropertyDefaults sessionPropertyDefaults;
    private final FileSystemClientManager fileSystemClientManager;
    private final AtomicBoolean pluginsLoading = new AtomicBoolean();
    private final AtomicBoolean pluginsLoaded = new AtomicBoolean();

    @Inject
    public PluginManager(
            PluginsProvider pluginsProvider,
            ConnectorManager connectorManager,
            MetadataManager metadataManager,
            ResourceGroupManager<?> resourceGroupManager,
            AccessControlManager accessControlManager,
            Optional<PasswordAuthenticatorManager> passwordAuthenticatorManager,
            CertificateAuthenticatorManager certificateAuthenticatorManager,
            EventListenerManager eventListenerManager,
            GroupProviderManager groupProviderManager,
            SessionPropertyDefaults sessionPropertyDefaults,
            FileSystemClientManager fileSystemClientManager)
    {
        this.pluginsProvider = requireNonNull(pluginsProvider, "pluginsProvider is null");
        this.connectorManager = requireNonNull(connectorManager, "connectorManager is null");
        this.metadataManager = requireNonNull(metadataManager, "metadataManager is null");
        this.resourceGroupManager = requireNonNull(resourceGroupManager, "resourceGroupManager is null");
        this.accessControlManager = requireNonNull(accessControlManager, "accessControlManager is null");
        this.passwordAuthenticatorManager = requireNonNull(passwordAuthenticatorManager, "passwordAuthenticatorManager is null");
        this.certificateAuthenticatorManager = requireNonNull(certificateAuthenticatorManager, "certificateAuthenticatorManager is null");
        this.eventListenerManager = requireNonNull(eventListenerManager, "eventListenerManager is null");
        this.groupProviderManager = requireNonNull(groupProviderManager, "groupProviderManager is null");
        this.sessionPropertyDefaults = requireNonNull(sessionPropertyDefaults, "sessionPropertyDefaults is null");
        this.fileSystemClientManager = requireNonNull(fileSystemClientManager, "fileSystemClientManager is null");
    }

    public void loadPlugins()
    {
        if (!pluginsLoading.compareAndSet(false, true)) {
            return;
        }

        pluginsProvider.loadPlugins(this::loadPlugin, this::createClassLoader);

        metadataManager.verifyTypes();

        pluginsLoaded.set(true);
    }

    private void loadPlugin(String plugin, Supplier<PluginClassLoader> createClassLoader)
    {
        log.info("-- Loading plugin %s --", plugin);

        PluginClassLoader pluginClassLoader = createClassLoader.get();

        log.debug("Classpath for plugin:");
        for (URL url : pluginClassLoader.getURLs()) {
            log.debug("    %s", url.getPath());
        }

        try (ThreadContextClassLoader ignored = new ThreadContextClassLoader(pluginClassLoader)) {
            loadPlugin(pluginClassLoader);
        }

        log.info("-- Finished loading plugin %s --", plugin);
    }

    private void loadPlugin(PluginClassLoader pluginClassLoader)
    {
        ServiceLoader<Plugin> serviceLoader = ServiceLoader.load(Plugin.class, pluginClassLoader);
        List<Plugin> plugins = ImmutableList.copyOf(serviceLoader);
        checkState(!plugins.isEmpty(), "No service providers of type %s", Plugin.class.getName());

        for (Plugin plugin : plugins) {
            log.info("Installing %s", plugin.getClass().getName());
            installPlugin(plugin, pluginClassLoader::duplicate);
        }
    }

    public void installPlugin(Plugin plugin, Supplier<ClassLoader> duplicatePluginClassLoaderFactory)
    {
        installPluginInternal(plugin, duplicatePluginClassLoaderFactory);
        metadataManager.verifyTypes();
    }

    private void installPluginInternal(Plugin plugin, Supplier<ClassLoader> duplicatePluginClassLoaderFactory)
    {
        for (BlockEncoding blockEncoding : plugin.getBlockEncodings()) {
            log.info("Registering block encoding %s", blockEncoding.getName());
            metadataManager.addBlockEncoding(blockEncoding);
        }

        for (Type type : plugin.getTypes()) {
            log.info("Registering type %s", type.getTypeSignature());
            metadataManager.addType(type);
        }

        for (ParametricType parametricType : plugin.getParametricTypes()) {
            log.info("Registering parametric type %s", parametricType.getName());
            metadataManager.addParametricType(parametricType);
        }

        for (ConnectorFactory connectorFactory : plugin.getConnectorFactories()) {
            log.info("Registering connector %s", connectorFactory.getName());
            connectorManager.addConnectorFactory(connectorFactory, duplicatePluginClassLoaderFactory);
            ConnectorCache.addCatalogConfig(plugin, connectorFactory.getName());
        }

        for (Class<?> functionClass : plugin.getFunctions()) {
            log.info("Registering functions from %s", functionClass.getName());
            metadataManager.addFunctions(extractFunctions(functionClass));
        }

        for (SessionPropertyConfigurationManagerFactory sessionConfigFactory : plugin.getSessionPropertyConfigurationManagerFactories()) {
            log.info("Registering session property configuration manager %s", sessionConfigFactory.getName());
            sessionPropertyDefaults.addConfigurationManagerFactory(sessionConfigFactory);
        }

        for (ResourceGroupConfigurationManagerFactory configurationManagerFactory : plugin.getResourceGroupConfigurationManagerFactories()) {
            log.info("Registering resource group configuration manager %s", configurationManagerFactory.getName());
            resourceGroupManager.addConfigurationManagerFactory(configurationManagerFactory);
        }

        for (SystemAccessControlFactory accessControlFactory : plugin.getSystemAccessControlFactories()) {
            log.info("Registering system access control %s", accessControlFactory.getName());
            accessControlManager.addSystemAccessControlFactory(accessControlFactory);
        }

        passwordAuthenticatorManager.ifPresent(authenticationManager -> {
            for (PasswordAuthenticatorFactory authenticatorFactory : plugin.getPasswordAuthenticatorFactories()) {
                log.info("Registering password authenticator %s", authenticatorFactory.getName());
                authenticationManager.addPasswordAuthenticatorFactory(authenticatorFactory);
            }
        });

        for (CertificateAuthenticatorFactory authenticatorFactory : plugin.getCertificateAuthenticatorFactories()) {
            log.info("Registering certificate authenticator %s", authenticatorFactory.getName());
            certificateAuthenticatorManager.addCertificateAuthenticatorFactory(authenticatorFactory);
        }

        for (EventListenerFactory eventListenerFactory : plugin.getEventListenerFactories()) {
            log.info("Registering event listener %s", eventListenerFactory.getName());
            eventListenerManager.addEventListenerFactory(eventListenerFactory);
        }

        for (GroupProviderFactory groupProviderFactory : plugin.getGroupProviderFactories()) {
            log.info("Registering group provider %s", groupProviderFactory.getName());
            groupProviderManager.addGroupProviderFactory(groupProviderFactory);
        }

        for (FileSystemClientFactory fileSystemClientFactory : plugin.getFileSystemClientFactory()) {
            log.info("Registering file system provider %s", fileSystemClientFactory.getName());
            fileSystemClientManager.addFileSystemClientFactories(fileSystemClientFactory);
        }
    }

    private PluginClassLoader createClassLoader(List<URL> urls)
    {
        ClassLoader parent = getClass().getClassLoader();
        return new PluginClassLoader(urls, parent, SPI_PACKAGES);
    }

    public interface PluginsProvider
    {
        void loadPlugins(Loader loader, ClassLoaderFactory createClassLoader);

        interface Loader
        {
            void load(String description, Supplier<PluginClassLoader> getClassLoader);
        }

        interface ClassLoaderFactory
        {
            PluginClassLoader create(List<URL> urls);
        }
    }
}
