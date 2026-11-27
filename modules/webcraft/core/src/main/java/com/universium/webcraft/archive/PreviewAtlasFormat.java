package com.universium.webcraft.archive;

/** Layout and limits shared by the preview atlas writer and reader. */
public final class PreviewAtlasFormat {
    public static final String INDEX = "previews/atlas-index.json";
    public static final int VERSION = 1;
    public static final int TILE_SIZE = 64;
    public static final int GRID_SIZE = 10;
    public static final int TILE_COUNT = GRID_SIZE * GRID_SIZE;
    public static final int IMAGE_SIZE = TILE_SIZE * GRID_SIZE;
    public static final int MAX_INDEX_BYTES = 32 * 1024 * 1024;
    public static final int MAX_IMAGE_BYTES = 4 * 1024 * 1024;

    private PreviewAtlasFormat() {}

    /** Returns the archive path for the SHA-256 of a sheet's WebP bytes. */
    public static String imagePath(String hash) {
        return "previews/atlases/" + hash + ".webp";
    }
}
