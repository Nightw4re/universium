package com.universium.webcraft.core;

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.toml.TomlParser;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

final class ModpackMetadata {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_FILE_BYTES = 256 * 1024;
    private static final String BCC_CLASS = "dev.wuffs.bcc.BetterCompatibilityChecker";
    private static final String METADATA_FILE = "config/metadata.json";
    private static final List<Source> AUTO_SOURCES = List.of(Source.BCC_RUNTIME, Source.BCC_TOML,
            Source.BCC_JSON, Source.METADATA_JSON, Source.PACKWIZ);

    enum Source { AUTO, BCC_RUNTIME, BCC_TOML, BCC_JSON, METADATA_JSON, PACKWIZ, CURSEFORGE_MANIFEST, CUSTOM }

    record Identity(String name, String version) {
        Identity {
            name = clean(name);
            version = clean(version);
            if (name == null || version == null) {
                throw new IllegalArgumentException("Modpack name or version is missing");
            }
        }

        private static String clean(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            String trimmed = value.strip();
            return "CHANGE_ME".equalsIgnoreCase(trimmed) || "??".equals(trimmed) ? null : trimmed;
        }
    }

    record Selection(Source source, String file, String nameKey, String versionKey) {}

    private final Path instance;
    private final Supplier<Optional<Identity>> runningBcc;

    ModpackMetadata(Path instance, Supplier<Optional<Identity>> runningBcc) {
        this.instance = instance;
        this.runningBcc = runningBcc;
    }

    Optional<Identity> resolve(Selection settings) {
        boolean automatic = settings.source() == Source.AUTO;
        for (Source source : automatic ? AUTO_SOURCES : List.of(settings.source())) {
            try {
                Optional<Identity> identity = read(source, settings);
                if (identity.isPresent()) {
                    LOGGER.info("WebCraft modpack metadata source: {}", source);
                    return identity;
                }
                if (!automatic) {
                    LOGGER.warn("WebCraft modpack source {} is unavailable", source);
                }
            } catch (IOException | RuntimeException exception) {
                if (!automatic || !(exception instanceof NoSuchFileException)) {
                    LOGGER.warn("WebCraft could not read modpack source {} ({})", source,
                            exception.getClass().getSimpleName());
                }
            }
        }
        if (automatic) {
            LOGGER.info("WebCraft found no usable modpack metadata source");
        }
        return Optional.empty();
    }

    private Optional<Identity> read(Source source, Selection settings) throws IOException {
        return switch (source) {
            case AUTO -> throw new IllegalArgumentException("AUTO is not a metadata source");
            case BCC_RUNTIME -> runningBcc.get();
            case BCC_TOML -> {
                Config config = toml("config/bcc-common.toml");
                yield Optional.of(Boolean.TRUE.equals(config.get("general.useMetadata"))
                        ? metadata()
                        : new Identity(string(config.get("general.modpackName")), string(config.get("general.modpackVersion"))));
            }
            case BCC_JSON -> {
                JsonElement config = json("config/bcc-common.json");
                JsonElement useMetadata = jsonValue(config, "useMetadata.value");
                yield Optional.of(useMetadata != null && useMetadata.isJsonPrimitive()
                        && useMetadata.getAsJsonPrimitive().isBoolean() && useMetadata.getAsBoolean()
                        ? metadata()
                        : new Identity(jsonString(config, "modpackName.value"), jsonString(config, "modpackVersion.value")));
            }
            case METADATA_JSON -> Optional.of(metadata());
            case PACKWIZ -> {
                Config config = toml("pack.toml");
                Object format = config.get("pack-format");
                yield format instanceof String name && name.startsWith("packwiz:")
                        ? Optional.of(new Identity(string(config.get("name")), string(config.get("version"))))
                        : Optional.empty();
            }
            case CURSEFORGE_MANIFEST -> {
                JsonElement manifest = json("manifest.json");
                yield "minecraftModpack".equals(jsonString(manifest, "manifestType"))
                        ? Optional.of(new Identity(jsonString(manifest, "name"), jsonString(manifest, "version")))
                        : Optional.empty();
            }
            case CUSTOM -> Optional.of(readCustom(settings));
        };
    }

    private Identity metadata() throws IOException {
        JsonElement metadata = json(METADATA_FILE);
        return new Identity(jsonString(metadata, "name"), jsonString(metadata, "version.name"));
    }

    private Identity readCustom(Selection settings) throws IOException {
        String lower = settings.file().toLowerCase(Locale.ROOT);
        if (lower.endsWith(".json")) {
            JsonElement json = json(settings.file());
            return new Identity(jsonString(json, settings.nameKey()), jsonString(json, settings.versionKey()));
        }
        if (lower.endsWith(".toml")) {
            Config config = toml(settings.file());
            return new Identity(string(config.get(settings.nameKey())), string(config.get(settings.versionKey())));
        }
        throw new IllegalArgumentException("Expected a JSON or TOML file");
    }

    private JsonElement json(String file) throws IOException {
        return JsonParser.parseString(readFile(file));
    }

    private Config toml(String file) throws IOException {
        return new TomlParser().parse(new StringReader(readFile(file)));
    }

    private String readFile(String file) throws IOException {
        Path relative = Path.of(file);
        if (file.isBlank() || relative.isAbsolute() || relative.getRoot() != null || relative.normalize().startsWith("..")) {
            throw new IllegalArgumentException("Expected a path within the Minecraft instance");
        }
        Path root = instance.toRealPath();
        Path resolved = root.resolve(relative.normalize()).toRealPath();
        if (!resolved.startsWith(root) || !Files.isRegularFile(resolved)) {
            throw new IOException("Expected a regular file within the Minecraft instance");
        }
        try (var input = Files.newInputStream(resolved)) {
            byte[] bytes = input.readNBytes(MAX_FILE_BYTES + 1);
            if (bytes.length > MAX_FILE_BYTES) {
                throw new IOException("Modpack metadata file exceeds the size limit");
            }
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static String string(Object value) {
        return value instanceof String text ? text : null;
    }

    private static String jsonString(JsonElement root, String path) {
        JsonElement value = jsonValue(root, path);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString() : null;
    }

    private static JsonElement jsonValue(JsonElement root, String path) {
        JsonElement value = root;
        for (String key : path.split("\\.", -1)) {
            if (key.isEmpty() || value == null || !value.isJsonObject()) {
                return null;
            }
            value = value.getAsJsonObject().get(key);
        }
        return value;
    }

    static Optional<Identity> readRunningBcc() {
        if (!ModList.get().isLoaded("bcc")) {
            return Optional.empty();
        }
        try {
            // Keep BCC optional and read the status it resolved during its own startup.
            Class<?> bcc = Class.forName(BCC_CLASS, false, ModpackMetadata.class.getClassLoader());
            Object status = bcc.getMethod("getBetterStatus").invoke(null);
            if (status == null) {
                return Optional.empty();
            }
            Class<?> type = status.getClass();
            return Optional.of(new Identity(string(type.getMethod("name").invoke(status)),
                    string(type.getMethod("version").invoke(status))));
        } catch (ReflectiveOperationException | LinkageError exception) {
            LOGGER.warn("WebCraft could not read the loaded Better Compatibility Checker status ({})",
                    exception.getClass().getSimpleName());
            return Optional.empty();
        }
    }
}
