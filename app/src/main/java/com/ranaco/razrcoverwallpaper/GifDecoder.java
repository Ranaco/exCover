package com.ranaco.razrcoverwallpaper;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ImageDecoder;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.util.Size;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * GIF decoding built on {@link ImageDecoder}. Animated drawables decode frames off the UI thread
 * and draw through the GPU, which keeps large GIFs smooth where {@code android.graphics.Movie}
 * decoded every frame in software on the calling thread.
 */
final class GifDecoder {
    private GifDecoder() {}

    static ImageDecoder.Source source(Context context, String target) {
        if (WallpaperStore.SOURCE_CUSTOM.equals(WallpaperStore.selectedSource(context, target))) {
            return ImageDecoder.createSource(WallpaperStore.gifFile(context, target));
        }
        return ImageDecoder.createSource(context.getResources(), WallpaperStore.bundledResource(
                WallpaperStore.selectedSource(context, target)));
    }

    /** Decodes an animated drawable, downscaled only when its longest side exceeds {@code maxSide}. */
    static Drawable decode(ImageDecoder.Source source, int maxSide) throws IOException {
        Drawable drawable = ImageDecoder.decodeDrawable(source, (decoder, info, ignored) -> {
            Size size = info.getSize();
            int longest = Math.max(size.getWidth(), size.getHeight());
            if (longest > maxSide) {
                float scale = maxSide / (float) longest;
                decoder.setTargetSize(Math.max(1, Math.round(size.getWidth() * scale)),
                        Math.max(1, Math.round(size.getHeight() * scale)));
            }
        });
        if (drawable instanceof AnimatedImageDrawable) {
            ((AnimatedImageDrawable) drawable).setRepeatCount(AnimatedImageDrawable.REPEAT_INFINITE);
        }
        return drawable;
    }

    /**
     * Renders only the first frame, framed like the wallpaper, into a {@code width × height} bitmap.
     * The frame is decoded at just the resolution the output needs.
     */
    static Bitmap frame(ImageDecoder.Source source, int width, int height, WallpaperStore.Crop crop) {
        try {
            Bitmap first = ImageDecoder.decodeBitmap(source, (decoder, info, ignored) -> {
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                Size size = info.getSize();
                float needed = Math.max(width / (float) size.getWidth(), height / (float) size.getHeight())
                        * Math.max(1f, crop.zoom);
                if (needed < 1f) {
                    decoder.setTargetSize(Math.max(1, Math.round(size.getWidth() * needed)),
                            Math.max(1, Math.round(size.getHeight() * needed)));
                }
            });
            Bitmap output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(output);
            CropPreviewView.Transform transform = CropPreviewView.calculateTransform(
                    width, height, first.getWidth(), first.getHeight(), crop.zoom, crop.focusX, crop.focusY);
            canvas.translate(transform.left, transform.top);
            canvas.scale(transform.scale, transform.scale);
            canvas.drawBitmap(first, 0f, 0f, null);
            first.recycle();
            return output;
        } catch (Exception error) {
            return null;
        }
    }

    /** Total loop length, read from the GIF's frame delays without decoding any pixels. */
    static int durationMs(File file) {
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
            byte[] header = new byte[13];
            if (read(input, header) < 13) {
                return 0;
            }
            int packed = header[10] & 0xFF;
            if ((packed & 0x80) != 0) {
                skip(input, 3L * (1 << ((packed & 0x07) + 1)));
            }
            int total = 0;
            while (true) {
                int block = input.read();
                if (block == -1 || block == 0x3B) {
                    return total;
                }
                if (block == 0x21) {
                    int label = input.read();
                    if (label == 0xF9) {
                        byte[] control = new byte[6];
                        if (read(input, control) < 6) {
                            return total;
                        }
                        int delay = (control[2] & 0xFF) | ((control[3] & 0xFF) << 8);
                        // Browsers treat near-zero delays as 100 ms; match what the user sees.
                        total += (delay <= 1 ? 10 : delay) * 10;
                    } else {
                        skipSubBlocks(input);
                    }
                } else if (block == 0x2C) {
                    byte[] descriptor = new byte[9];
                    if (read(input, descriptor) < 9) {
                        return total;
                    }
                    int flags = descriptor[8] & 0xFF;
                    if ((flags & 0x80) != 0) {
                        skip(input, 3L * (1 << ((flags & 0x07) + 1)));
                    }
                    input.read();
                    skipSubBlocks(input);
                } else {
                    return total;
                }
            }
        } catch (IOException error) {
            return 0;
        }
    }

    private static void skipSubBlocks(InputStream input) throws IOException {
        int length;
        while ((length = input.read()) > 0) {
            skip(input, length);
        }
    }

    private static void skip(InputStream input, long count) throws IOException {
        long remaining = count;
        while (remaining > 0) {
            long skipped = input.skip(remaining);
            if (skipped <= 0) {
                if (input.read() == -1) {
                    return;
                }
                skipped = 1;
            }
            remaining -= skipped;
        }
    }

    private static int read(InputStream input, byte[] buffer) throws IOException {
        int offset = 0;
        while (offset < buffer.length) {
            int count = input.read(buffer, offset, buffer.length - offset);
            if (count < 0) {
                break;
            }
            offset += count;
        }
        return offset;
    }
}
