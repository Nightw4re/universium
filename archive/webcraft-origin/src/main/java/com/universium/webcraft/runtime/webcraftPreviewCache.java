package com.universium.webcraft.runtime;

import com.universium.webcraft.playerinfo.PlayerInfoItem;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class webcraftPreviewCache {
    private static final String CACHE_VERSION = "5";
    private final webcraftResourcePackRegistry resourcePackRegistry;
    private final Path cacheRoot;

    public webcraftPreviewCache(webcraftResourcePackRegistry resourcePackRegistry, Path cacheRoot) {
        this.resourcePackRegistry = Objects.requireNonNull(resourcePackRegistry, "resourcePackRegistry");
        this.cacheRoot = Objects.requireNonNull(cacheRoot, "cacheRoot");
    }

    public void warm() {
        warm(32, 0);
    }

    public void warm(int batchSize, int pauseMs) {
        try {
            Files.createDirectories(cacheRoot);
            Path versionFile = cacheRoot.resolve(".cache-version");
            String currentVersion = Files.exists(versionFile) ? Files.readString(versionFile).trim() : "";
            if (!CACHE_VERSION.equals(currentVersion)) {
                clearCacheRoot();
                Files.createDirectories(cacheRoot);
                Files.writeString(versionFile, CACHE_VERSION);
            }
            List<String> textures = new ArrayList<>(resourcePackRegistry.textureIndex().keySet().stream()
                    .filter(key -> key.endsWith(".png"))
                    .toList());
            int effectiveBatchSize = Math.max(1, batchSize);
            int effectivePauseMs = Math.max(0, pauseMs);
            for (int i = 0; i < textures.size(); i++) {
                ensurePreviewForTexture(textures.get(i));
                if (effectivePauseMs > 0 && (i + 1) % effectiveBatchSize == 0) {
                    try {
                        Thread.sleep(effectivePauseMs);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        } catch (IOException ignored) {
        }
    }

    public String previewUrlFor(PlayerInfoItem item) {
        if (item == null || item.id() == null || item.id().isBlank()) {
            return "";
        }
        String path = resolvePreviewRelativePath(item);
        ensurePreviewForTexture(path);
        return "/resources/previews/" + path;
    }

    public byte[] resolvePreview(String relativePath) {
        String normalized = normalizeRelativePath(relativePath);
        Path cached = cacheRoot.resolve(normalized).normalize();
        if (Files.exists(cached)) {
            try {
                return Files.readAllBytes(cached);
            } catch (IOException ignored) {
            }
        }
        ensurePreviewForTexture(normalized);
        if (Files.exists(cached)) {
            try {
                return Files.readAllBytes(cached);
            } catch (IOException ignored) {
            }
        }
        return null;
    }

    private void ensurePreviewForTexture(String relativePath) {
        String normalized = normalizeRelativePath(relativePath);
        Path cached = cacheRoot.resolve(normalized).normalize();
        try {
            byte[] sourceBytes = resourcePackRegistry.resolveTexture(normalized);
            if (sourceBytes == null || sourceBytes.length == 0) {
                return;
            }
            Files.createDirectories(cached.getParent());
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(sourceBytes));
            if (image == null) {
                return;
            }
            BufferedImage preview = renderPreview(normalized, image);
            ImageIO.write(preview, "PNG", cached.toFile());
        } catch (IOException ignored) {
        }
    }

    private void clearCacheRoot() {
        try {
            if (!Files.exists(cacheRoot)) {
                return;
            }
            try (var paths = Files.walk(cacheRoot)) {
                paths.sorted((left, right) -> right.compareTo(left)).forEach(path -> {
                    if (path.equals(cacheRoot)) {
                        return;
                    }
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                    }
                });
            }
        } catch (IOException ignored) {
        }
    }

    private BufferedImage renderPreview(String relativePath, BufferedImage source) {
        BufferedImage canvas = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = canvas.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            drawImage(graphics, source, resolveRenderSpec(relativePath));
        } finally {
            graphics.dispose();
        }
        return canvas;
    }

    private void drawImage(Graphics2D graphics, BufferedImage source, RenderSpec spec) {
        int canvasWidth = 64;
        int canvasHeight = 64;
        int sourceWidth = source.getWidth();
        int sourceHeight = source.getHeight();
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            return;
        }

        double scaleX = canvasWidth / (double) sourceWidth;
        double scaleY = canvasHeight / (double) sourceHeight;
        if ("cover".equals(spec.fit())) {
            double scale = Math.max(scaleX, scaleY);
            double drawWidth = sourceWidth * scale;
            double drawHeight = sourceHeight * scale;
            double x = anchorOffset(spec.anchorX(), canvasWidth, drawWidth);
            double y = anchorOffset(spec.anchorY(), canvasHeight, drawHeight);
            AffineTransform transform = AffineTransform.getTranslateInstance(x, y);
            transform.scale(scale, scale);
            graphics.drawRenderedImage(source, transform);
            return;
        }
        if ("stretch".equals(spec.fit())) {
            AffineTransform transform = AffineTransform.getTranslateInstance(0, 0);
            transform.scale(scaleX, scaleY);
            graphics.drawRenderedImage(source, transform);
            return;
        }
        double scale = Math.min(scaleX, scaleY);
        double drawWidth = sourceWidth * scale;
        double drawHeight = sourceHeight * scale;
        double x = anchorOffset(spec.anchorX(), canvasWidth, drawWidth);
        double y = anchorOffset(spec.anchorY(), canvasHeight, drawHeight);
        AffineTransform transform = AffineTransform.getTranslateInstance(x, y);
        transform.scale(scale, scale);
        graphics.drawRenderedImage(source, transform);
    }

    private static double anchorOffset(String anchor, int canvasSize, double drawSize) {
        String value = anchor == null ? "center" : anchor.toLowerCase(java.util.Locale.ROOT);
        return switch (value) {
            case "left", "top", "left center" -> 0.0;
            case "right", "bottom", "right center" -> canvasSize - drawSize;
            default -> (canvasSize - drawSize) / 2.0;
        };
    }

    private RenderSpec resolveRenderSpec(String relativePath) {
        return new RenderSpec("item", "center", "center", "contain");
    }

    private String resolvePreviewRelativePath(PlayerInfoItem item) {
        if (item.previewPath() != null && !item.previewPath().isBlank()) {
            return normalizeRelativePath(item.previewPath()).replaceFirst("^api/resources/previews/", "").replaceFirst("^resources/previews/", "");
        }
        String texturePath = normalizeRelativePath(item.texturePath());
        if (texturePath.isBlank()) {
            String key = normalizeItemKey(item.id());
            return "item/" + key + ".png";
        }
        if (texturePath.startsWith("/api/resources/textures/")) {
            return texturePath.substring("/api/resources/textures/".length());
        }
        if (texturePath.startsWith("/resources/textures/")) {
            return texturePath.substring("/resources/textures/".length());
        }
        return texturePath;
    }

    private static String normalizeItemKey(String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return "unknown";
        }
        int colon = itemId.indexOf(':');
        return colon >= 0 ? itemId.substring(colon + 1) : itemId;
    }

    private static String normalizeRelativePath(String value) {
        return (value == null ? "" : value).replace('\\', '/').replaceAll("^/+", "");
    }

    private record RenderSpec(String mode, String anchorX, String anchorY, String fit) {
    }
}
