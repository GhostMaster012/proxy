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

@Service
public class ConfigManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConfigManager.class);
    private static final String CONFIG_FILE_NAME = "config.yml";

    private ProxyConfig configuration;

    @PostConstruct
    public void loadConfig() {
        Path configPath = Paths.get(CONFIG_FILE_NAME);
        // For SnakeYAML 2.0+ it's safer to provide LoaderOptions
        LoaderOptions loaderOptions = new LoaderOptions();
        // You can configure loaderOptions here if needed, e.g., setCodePointLimit
        Constructor proxyConstructor = new Constructor(ProxyConfig.class, loaderOptions);
        Yaml yaml = new Yaml(proxyConstructor);

        if (Files.exists(configPath)) {
            try (InputStream in = Files.newInputStream(configPath)) {
                configuration = yaml.load(in);
                LOGGER.info("Successfully loaded configuration from {}", configPath.toAbsolutePath());
            } catch (IOException | YAMLException e) {
                LOGGER.error("Error loading configuration from file {}: {}", CONFIG_FILE_NAME, e.getMessage(), e);
                loadDefaultConfig(yaml);
            }
        } else {
            LOGGER.warn("Configuration file {} not found in the working directory. Attempting to load from classpath.", CONFIG_FILE_NAME);
            try (InputStream in = getClass().getClassLoader().getResourceAsStream(CONFIG_FILE_NAME)) {
                if (in == null) {
                    LOGGER.error("Default configuration file {} not found in classpath. Using empty default configuration.", CONFIG_FILE_NAME);
                    configuration = createEmptyConfig(); // Create an empty or minimal default config
                    // Optionally, write this default config to disk here if desired
                    // writeDefaultConfig(yaml, configPath);
                    return;
                }
                configuration = yaml.load(in);
                LOGGER.info("Successfully loaded default configuration from classpath resource {}.", CONFIG_FILE_NAME);
                 // Optionally write the classpath config to the filesystem if it wasn't there
                // if (!Files.exists(configPath)) {
                //    Files.copy(getClass().getClassLoader().getResourceAsStream(CONFIG_FILE_NAME), configPath);
                //    LOGGER.info("Copied default config.yml to {}", configPath.toAbsolutePath());
                // }
            } catch (YAMLException | NullPointerException e) { // NullPointerException if getResourceAsStream returns null and isn't checked
                LOGGER.error("Error loading default configuration from classpath resource {}: {}", CONFIG_FILE_NAME, e.getMessage(), e);
                loadDefaultConfig(yaml); // Fallback to hardcoded defaults
            } catch (Exception e) {
                 LOGGER.error("An unexpected error occurred while loading configuration from classpath: {}", e.getMessage(), e);
                 loadDefaultConfig(yaml);
            }
        }

        if (configuration == null) { // Final fallback
             LOGGER.warn("Configuration is still null after all loading attempts. Using hardcoded minimal defaults.");
             configuration = createEmptyConfig();
        }

        // You might want to log parts of the loaded config for verification
        // LOGGER.debug("Loaded configuration: {}", configuration);
    }

    private void loadDefaultConfig(Yaml yaml) {
        LOGGER.warn("Falling back to a minimal, hardcoded default configuration due to previous errors.");
        configuration = createEmptyConfig();
        // Potentially try to write this minimal config to disk
        // Path configPath = Paths.get(CONFIG_FILE_NAME);
        // try {
        //     if (!Files.exists(configPath)) {
        //        Files.createDirectories(configPath.getParent()); // Ensure parent directory exists
        //        String defaultConfigContent = yaml.dump(configuration);
        //        Files.writeString(configPath, defaultConfigContent);
        //        LOGGER.info("Written minimal default configuration to {}", configPath.toAbsolutePath());
        //     }
        // } catch (IOException e) {
        //     LOGGER.error("Could not write default config.yml: {}", e.getMessage(), e);
        // }
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
