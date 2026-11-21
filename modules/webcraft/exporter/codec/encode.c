#include <stdint.h>
#include "src/webp/encode.h"

// Packs the encoded buffer length and address into a single wasm32 result.
// The caller owns the returned buffer and releases it with free().
uint64_t encode(const uint8_t* rgba, int width, int height) {
    WebPConfig config;
    WebPPicture picture;
    WebPMemoryWriter output;
    if (!WebPConfigInit(&config) || !WebPConfigLosslessPreset(&config, 1)
            || !WebPPictureInit(&picture)) {
        return 0;
    }
    config.exact = 1;
    picture.width = width;
    picture.height = height;
    picture.use_argb = 1;
    WebPMemoryWriterInit(&output);
    picture.writer = WebPMemoryWrite;
    picture.custom_ptr = &output;

    int success = WebPPictureImportRGBA(&picture, rgba, width * 4)
            && WebPEncode(&config, &picture);
    WebPPictureFree(&picture);
    if (!success) {
        WebPMemoryWriterClear(&output);
        return 0;
    }
    return ((uint64_t) output.size << 32) | (uintptr_t) output.mem;
}
