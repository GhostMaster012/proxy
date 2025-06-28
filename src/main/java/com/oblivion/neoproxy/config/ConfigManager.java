package com.oblivion.neoproxy.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.LoaderOptions; // Import LoaderOptions


import jakarta.annotation.PostConstruct; // Preferred for Spring Boot 3+
import java.io.InputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList; // Added
import java.util.Arrays;    // Added
import java.util.HashMap;   // Added
import java.util.List;      // Added
import java.util.Map;       // Added

@Service
public class ConfigManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConfigManager.class);
    private static final String CONFIG_FILE_NAME = "config.yml";

    private ProxyConfig configuration;

    @PostConstruct
    public void loadConfig() {
        Path configPath = Paths.get(CONFIG_FILE_NAME);
        LoaderOptions loaderOptions = new LoaderOptions();
        Constructor proxyConstructor = new Constructor(ProxyConfig.class, loaderOptions);
        Yaml yaml = new Yaml(proxyConstructor);

        if (Files.exists(configPath)) {
            try (InputStream in = Files.newInputStream(configPath)) {
                configuration = yaml.load(in);
                LOGGER.info("Successfully loaded configuration from {}", configPath.toAbsolutePath());
            } catch (IOException | YAMLException e) {
                LOGGER.error("Error loading configuration from file {}: {}. Attempting to create and use a default config.",
                             configPath.toAbsolutePath(), e.getMessage(), e);
                createAndLoadDefaultConfig(yaml, configPath);
            }
        } else {
            // This is the primary path for creating the default config if it's missing from the working directory.
            LOGGER.info("Configuration file {} not found. Creating default configuration.", configPath.toAbsolutePath());
            // Attempt to load from classpath first as a template to copy, if available.
            try (InputStream classpathStream = getClass().getClassLoader().getResourceAsStream(CONFIG_FILE_NAME)) {
                if (classpathStream != null) {
                    LOGGER.info("Found {} in classpath. Using it as a template for the working directory.", CONFIG_FILE_NAME);
                    // Copy from classpath to working directory's configPath
                    Path parentDir = configPath.getParent();
                    if (parentDir != null) {
                        Files.createDirectories(parentDir);
                    }
                    Files.copy(classpathStream, configPath);
                    LOGGER.info("Copied default configuration from classpath to {}", configPath.toAbsolutePath());
                    // Now load the copied file
                    try (InputStream copiedFileStream = Files.newInputStream(configPath)) {
                        configuration = yaml.load(copiedFileStream);
                        LOGGER.info("Successfully loaded configuration from copied classpath file: {}", configPath.toAbsolutePath());
                    } catch (IOException | YAMLException ex) {
                        LOGGER.error("Failed to load the copied classpath config from {}: {}. Falling back to programmatic default.",
                                     configPath.toAbsolutePath(), ex.getMessage(), ex);
                        createAndLoadDefaultConfig(yaml, configPath); // Fallback to creating programmatic default
                    }
                } else {
                    // Classpath resource not found, so create the default programmatically.
                    LOGGER.info("{} not found in classpath. Creating a new default config.yml programmatically.", CONFIG_FILE_NAME);
                    createAndLoadDefaultConfig(yaml, configPath);
                }
            } catch (IOException | YAMLException e) {
                LOGGER.error("Error processing or copying configuration from classpath: {}. Creating a new default config.yml programmatically.",
                             e.getMessage(), e);
                createAndLoadDefaultConfig(yaml, configPath);
            } catch (Exception e) {
                 LOGGER.error("An unexpected error occurred while attempting to use classpath configuration: {}. Creating a new default config.yml programmatically.",
                              e.getMessage(), e);
                 createAndLoadDefaultConfig(yaml, configPath);
            }
        }

        // Final fallback if all attempts failed (e.g. disk write failure for default)
        if (configuration == null) {
             LOGGER.error("CRITICAL: Configuration is still null after all loading and creation attempts. Using an empty placeholder config.");
             configuration = createEmptyConfig(); // This is the "last resort" empty config.
        }
    }

    private void createAndLoadDefaultConfig(Yaml yaml, Path configPath) {
        ProxyConfig defaultConfig = createDefaultProxyConfigObject();
        writeDefaultConfigToFile(defaultConfig, configPath);
        // After writing, try to load it. If this fails, something is seriously wrong.
        try (InputStream in = Files.newInputStream(configPath)) {
            configuration = yaml.load(in);
            // The message "Successfully created default config.yml in working directory" is logged by writeDefaultConfigToFile.
            // This log confirms the loading of that (or any) config.yml.
            LOGGER.info("Successfully loaded configuration from {}", configPath.getFileName());
        } catch (IOException | YAMLException e) {
            LOGGER.error("CRITICAL: Failed to load the newly created default config.yml from {}: {}. Using empty placeholder config.",
                         configPath.toAbsolutePath(), e.getMessage(), e);
            configuration = createEmptyConfig(); // Fallback to empty if even the self-created one fails to load
        }
    }

    private ProxyConfig createEmptyConfig() {
        // Create a truly minimal or empty config to prevent NullPointerExceptions
        // This should match the structure expected by other parts of your application
        // or provide sensible defaults.
        ProxyConfig defaultConfig = new ProxyConfig();
        // Populate with essential defaults if necessary, e.g., an empty listener list
        // defaultConfig.setListeners(new java.util.ArrayList<>());
        // defaultConfig.setServers(new java.util.HashMap<>());
        // defaultConfig.setPermissions(new java.util.HashMap<>());
        LOGGER.info("Initialized with an empty/minimal default ProxyConfig object.");
        return defaultConfig;
    }

    private void writeDefaultConfigToFile(ProxyConfig defaultConfig, Path configPath) {
        // Use DumperOptions to make the YAML output more readable if desired
        // DumperOptions options = new DumperOptions();
        // options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        // options.setPrettyFlow(true);
        // Yaml yaml = new Yaml(options);

        Yaml yaml = new Yaml(); // Standard YAML instance for dumping
        try {
            Path parentDir = configPath.getParent();
            if (parentDir != null) {
                Files.createDirectories(parentDir);
            }
            try (java.io.Writer writer = Files.newBufferedWriter(configPath, java.nio.charset.StandardCharsets.UTF_8)) {
                yaml.dump(defaultConfig, writer);
                LOGGER.info("Successfully created default config.yml in working directory: {}", configPath.toAbsolutePath());
            }
        } catch (IOException e) {
            LOGGER.error("Could not write default config.yml to {}: {}", configPath.toAbsolutePath(), e.getMessage(), e);
        }
    }

    private ProxyConfig createDefaultProxyConfigObject() {
        ProxyConfig defaultConfig = new ProxyConfig();

        // Default Servers
        Map<String, ServerInfo> servers = new HashMap<>();
        servers.put("lobby", new ServerInfo("localhost:25566", false));
        servers.put("survival", new ServerInfo("localhost:25567", false));
        servers.put("creative", new ServerInfo("localhost:25568", false));
        defaultConfig.setServers(servers);

        // Default Listener
        List<ListenerConfig> listeners = new ArrayList<>();
        ListenerConfig listener = new ListenerConfig();
        listener.setHost("0.0.0.0:25565");
        listener.setMotd("§6NeoProxy §7- §bA Next Generation Minecraft Proxy");
        listener.setMax_players(1000);
        listener.setQuery_port(0); // As per current config.yml, adjust if needed
        listener.setTab_list("GLOBAL_PING");
        listener.setForced_hosts(new HashMap<>()); // Empty by default
        listeners.add(listener);
        defaultConfig.setListeners(listeners);

        // Default Permissions
        Map<String, List<String>> permissions = new HashMap<>();
        permissions.put("default", Arrays.asList("neoproxy.command.server"));
        permissions.put("admin", Arrays.asList(
                "neoproxy.command.list",
                "neoproxy.command.find",
                "neoproxy.command.alert"
        ));
        defaultConfig.setPermissions(permissions);

        LOGGER.info("Created structured default ProxyConfig object programmatically.");
        return defaultConfig;
    }

    public ProxyConfig getConfiguration() {
        if (this.configuration == null) {
            // This should ideally not happen if @PostConstruct loadConfig worked or fell back.
            LOGGER.warn("Configuration accessed before it was loaded or after a failed load. Returning a new empty config.");
            return createEmptyConfig();
        }
        return configuration;
    }

    // Example: Method to get a specific listener (you might want the first one by default)
    public ListenerConfig getDefaultListener() {
        if (configuration != null && configuration.getListeners() != null && !configuration.getListeners().isEmpty()) {
            // For now, just return the first listener. Logic for selecting a default can be more complex.
            return configuration.getListeners().get(0);
        }
        LOGGER.warn("No listeners configured or configuration not loaded. Returning null for default listener.");
        return null; // Or throw an exception, or return a default listener object
    }
}
