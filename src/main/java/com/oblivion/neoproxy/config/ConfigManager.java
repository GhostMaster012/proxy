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
                LOGGER.error("Error loading configuration from file {}: {}. Attempting to create a default config.",
                             CONFIG_FILE_NAME, e.getMessage(), e);
                // If loading existing file fails, it might be corrupted.
                // We could back it up and write a new default one.
                // For now, just proceed to create default.
                createAndLoadDefaultConfig(yaml, configPath);
            }
        } else {
            LOGGER.warn("Configuration file {} not found in the working directory. Attempting to load from classpath.", CONFIG_FILE_NAME);
            try (InputStream classpathStream = getClass().getClassLoader().getResourceAsStream(CONFIG_FILE_NAME)) {
                if (classpathStream != null) {
                    configuration = yaml.load(classpathStream);
                    LOGGER.info("Successfully loaded configuration from classpath resource {}.", CONFIG_FILE_NAME);
                    // Optionally write the classpath config to the filesystem if it wasn't there
                    try {
                        Files.createDirectories(configPath.getParent());
                        Files.copy(getClass().getClassLoader().getResourceAsStream(CONFIG_FILE_NAME), configPath);
                        LOGGER.info("Copied configuration from classpath to {}", configPath.toAbsolutePath());
                    } catch (IOException e) {
                        LOGGER.warn("Could not copy classpath config to filesystem: {}", e.getMessage());
                    }
                } else {
                    LOGGER.warn("Default configuration file {} not found in classpath. Creating a new default config.yml.", CONFIG_FILE_NAME);
                    createAndLoadDefaultConfig(yaml, configPath);
                }
            } catch (YAMLException | IOException e) { // IOException for Files.copy
                LOGGER.error("Error processing configuration from classpath resource {}: {}. Creating a new default config.yml.",
                             CONFIG_FILE_NAME, e.getMessage(), e);
                createAndLoadDefaultConfig(yaml, configPath);
            } catch (Exception e) {
                 LOGGER.error("An unexpected error occurred while loading configuration from classpath: {}. Creating a new default config.yml.",
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
            LOGGER.info("Successfully loaded newly created default configuration from {}", configPath.toAbsolutePath());
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
            Files.createDirectories(configPath.getParent()); // Ensure parent directory exists
            try (java.io.Writer writer = Files.newBufferedWriter(configPath, java.nio.charset.StandardCharsets.UTF_8)) {
                yaml.dump(defaultConfig, writer);
                LOGGER.info("Successfully wrote default configuration to {}", configPath.toAbsolutePath());
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
