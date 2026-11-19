package com.universium.webcraft.config;

import java.util.List;
import java.util.Objects;

public record webcraftConfig(
        Server server,
        Frontend frontend,
        Logging logging
) {
    public webcraftConfig {
        server = Objects.requireNonNull(server, "server");
        frontend = Objects.requireNonNull(frontend, "frontend");
        logging = Objects.requireNonNull(logging, "logging");
    }

    public record Server(
            boolean enabled,
            boolean autoPort,
            boolean killExistingOnStart,
            boolean useHttps,
            String host,
            int port,
            String publicUrl,
            String minecraftVersion,
            int neoforgeBuild,
            String serverName,
            String minecraftServerName,
        String minecraftServerAddress,
        String keystorePath,
        String keystorePassword,
            String keystoreType,
            String webrootDir,
            String webrootIndex,
            boolean httpBootstrapAsync,
            boolean generateResourcesAtStart,
            boolean resourcesWarmupAsync,
            int resourcesWarmupBatchSize,
            int resourcesWarmupPauseMs,
            int resourcesCacheMaxAgeSeconds,
            String resourcePacksDir,
            String resourcePacksList,
            List<String> resourcePacksWebOrder
    ) {
        public Server {
            host = Objects.requireNonNull(host, "host");
            publicUrl = Objects.requireNonNull(publicUrl, "publicUrl");
            minecraftVersion = Objects.requireNonNull(minecraftVersion, "minecraftVersion");
            serverName = Objects.requireNonNull(serverName, "serverName");
            minecraftServerName = Objects.requireNonNull(minecraftServerName, "minecraftServerName");
            minecraftServerAddress = Objects.requireNonNull(minecraftServerAddress, "minecraftServerAddress");
            keystorePath = Objects.requireNonNull(keystorePath, "keystorePath");
            keystorePassword = Objects.requireNonNull(keystorePassword, "keystorePassword");
            keystoreType = Objects.requireNonNull(keystoreType, "keystoreType");
            webrootDir = Objects.requireNonNull(webrootDir, "webrootDir");
            webrootIndex = Objects.requireNonNull(webrootIndex, "webrootIndex");
            resourcesCacheMaxAgeSeconds = Math.max(0, resourcesCacheMaxAgeSeconds);
            resourcesWarmupBatchSize = Math.max(1, resourcesWarmupBatchSize);
            resourcesWarmupPauseMs = Math.max(0, resourcesWarmupPauseMs);
            resourcePacksDir = Objects.requireNonNull(resourcePacksDir, "resourcePacksDir");
            resourcePacksList = Objects.requireNonNull(resourcePacksList, "resourcePacksList");
            resourcePacksWebOrder = List.copyOf(Objects.requireNonNull(resourcePacksWebOrder, "resourcePacksWebOrder"));
        }
    }

    public record Frontend(
            String mode,
            String theme
    ) {
        public Frontend {
            mode = Objects.requireNonNull(mode, "mode");
            theme = Objects.requireNonNull(theme, "theme");
        }
    }

    public record Logging(
            boolean accessEnabled,
            boolean errorEnabled,
            String accessFile,
            String errorFile
    ) {
        public Logging {
            accessFile = Objects.requireNonNull(accessFile, "accessFile");
            errorFile = Objects.requireNonNull(errorFile, "errorFile");
        }
    }
}


