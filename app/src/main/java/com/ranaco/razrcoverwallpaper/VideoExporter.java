package com.ranaco.razrcoverwallpaper;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Movie;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;

/**
 * Renders the editor's GIF, framed for the cover screen, into a looping MP4 in Photos. Motorola's
 * cover lock screen designs accept videos from their photo button on phones where they don't list
 * live wallpapers (e.g. the Razr 50 on Android 16), and Motorola plays the video itself.
 */
final class VideoExporter {
    private static final String MIME = MediaFormat.MIMETYPE_VIDEO_AVC;
    private static final int FPS = 30;
    /** Very short loops are repeated so the wallpaper doesn't visibly restart every second. */
    private static final int MIN_LENGTH_MS = 4_000;
    private static final int MAX_LENGTH_MS = 15_000;
    private static final long TIMEOUT_US = 10_000;

    interface Progress {
        void onProgress(float fraction);
    }

    private VideoExporter() {}

    /** @return the Photos URI of the saved video. */
    static Uri exportDraftForCover(Context context, Progress progress) throws IOException {
        byte[] gif = readDraft(context);
        Movie movie = Movie.decodeByteArray(gif, 0, gif.length);
        if (movie == null || movie.width() <= 0 || movie.height() <= 0) {
            throw new IOException("Android couldn't decode this GIF");
        }
        WallpaperStore.Crop crop = WallpaperStore.crop(context, WallpaperStore.TARGET_DRAFT, false);
        int[] size = pickSize();
        File temp = new File(context.getCacheDir(), "cover-export.mp4");
        try {
            encode(movie, crop, size[0], size[1], temp, progress);
            return saveToPhotos(context, temp, WallpaperStore.selectionName(context, WallpaperStore.TARGET_DRAFT));
        } finally {
            temp.delete();
        }
    }

    private static byte[] readDraft(Context context) throws IOException {
        InputStream input = WallpaperStore.SOURCE_CUSTOM.equals(
                WallpaperStore.selectedSource(context, WallpaperStore.TARGET_DRAFT))
                ? new FileInputStream(WallpaperStore.gifFile(context, WallpaperStore.TARGET_DRAFT))
                : context.getResources().openRawResource(WallpaperStore.bundledResource(
                        WallpaperStore.selectedSource(context, WallpaperStore.TARGET_DRAFT)));
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    /** The cover's own 1080 × 1272 when the encoder takes it, else the same shape at 720 × 848. */
    private static int[] pickSize() {
        int[][] candidates = {{1080, 1272}, {720, 848}};
        for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (!info.isEncoder()) {
                continue;
            }
            for (String type : info.getSupportedTypes()) {
                if (!type.equalsIgnoreCase(MIME)) {
                    continue;
                }
                MediaCodecInfo.VideoCapabilities video = info.getCapabilitiesForType(type).getVideoCapabilities();
                for (int[] candidate : candidates) {
                    if (video != null && video.isSizeSupported(candidate[0], candidate[1])) {
                        return candidate;
                    }
                }
            }
        }
        return candidates[1];
    }

    private static void encode(Movie movie, WallpaperStore.Crop crop, int width, int height,
                               File output, Progress progress) throws IOException {
        int loopMs = movie.duration() > 0 ? movie.duration() : MIN_LENGTH_MS;
        int loops = Math.max(1, (int) Math.ceil(MIN_LENGTH_MS / (double) loopMs));
        int totalMs = Math.min(MAX_LENGTH_MS, loopMs * loops);
        int frames = Math.max(1, totalMs * FPS / 1000);

        MediaFormat format = MediaFormat.createVideoFormat(MIME, width, height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
        format.setInteger(MediaFormat.KEY_BIT_RATE, width * height * 6);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

        MediaCodec codec = MediaCodec.createEncoderByType(MIME);
        MediaMuxer muxer = new MediaMuxer(output.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        Bitmap frame = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(frame);
        int[] argb = new int[width * height];
        CropPreviewView.Transform transform = CropPreviewView.calculateTransform(
                width, height, movie.width(), movie.height(), crop.zoom, crop.focusX, crop.focusY);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int track = -1;
        boolean muxing = false;
        int queued = 0;
        boolean inputDone = false;
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();
            while (true) {
                if (!inputDone) {
                    int index = codec.dequeueInputBuffer(TIMEOUT_US);
                    if (index >= 0) {
                        long timeUs = queued * 1_000_000L / FPS;
                        if (queued >= frames) {
                            codec.queueInputBuffer(index, 0, 0, timeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            movie.setTime((int) ((queued * 1000L / FPS) % loopMs));
                            canvas.drawColor(Color.BLACK);
                            canvas.save();
                            canvas.translate(transform.left, transform.top);
                            canvas.scale(transform.scale, transform.scale);
                            movie.draw(canvas, 0f, 0f);
                            canvas.restore();
                            frame.getPixels(argb, 0, width, 0, 0, width, height);
                            Image image = codec.getInputImage(index);
                            writeYuv(argb, width, height, image);
                            codec.queueInputBuffer(index, 0, width * height * 3 / 2, timeUs, 0);
                            queued++;
                            if (progress != null) {
                                progress.onProgress(queued / (float) frames);
                            }
                        }
                    }
                }
                int out = codec.dequeueOutputBuffer(info, TIMEOUT_US);
                if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(codec.getOutputFormat());
                    muxer.start();
                    muxing = true;
                } else if (out >= 0) {
                    ByteBuffer data = codec.getOutputBuffer(out);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        info.size = 0;
                    }
                    if (info.size > 0 && muxing && data != null) {
                        data.position(info.offset);
                        data.limit(info.offset + info.size);
                        muxer.writeSampleData(track, data, info);
                    }
                    codec.releaseOutputBuffer(out, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break;
                    }
                }
            }
        } finally {
            frame.recycle();
            codec.stop();
            codec.release();
            if (muxing) {
                muxer.stop();
            }
            muxer.release();
        }
    }

    /** ARGB to YUV 4:2:0 (BT.601, limited range) through the codec's own plane layout. */
    private static void writeYuv(int[] argb, int width, int height, Image image) {
        Image.Plane[] planes = image.getPlanes();
        ByteBuffer yBuffer = planes[0].getBuffer();
        ByteBuffer uBuffer = planes[1].getBuffer();
        ByteBuffer vBuffer = planes[2].getBuffer();
        int yRow = planes[0].getRowStride();
        int yPixel = planes[0].getPixelStride();
        int uRow = planes[1].getRowStride();
        int uPixel = planes[1].getPixelStride();
        int vRow = planes[2].getRowStride();
        int vPixel = planes[2].getPixelStride();
        for (int y = 0; y < height; y++) {
            int rowStart = y * width;
            int yBase = y * yRow;
            for (int x = 0; x < width; x++) {
                int c = argb[rowStart + x];
                int r = (c >> 16) & 0xFF;
                int g = (c >> 8) & 0xFF;
                int b = c & 0xFF;
                yBuffer.put(yBase + x * yPixel, (byte) (((66 * r + 129 * g + 25 * b + 128) >> 8) + 16));
            }
        }
        for (int y = 0; y < height / 2; y++) {
            for (int x = 0; x < width / 2; x++) {
                int c = argb[(y * 2) * width + x * 2];
                int r = (c >> 16) & 0xFF;
                int g = (c >> 8) & 0xFF;
                int b = c & 0xFF;
                uBuffer.put(y * uRow + x * uPixel, (byte) (((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128));
                vBuffer.put(y * vRow + x * vPixel, (byte) (((112 * r - 94 * g - 18 * b + 128) >> 8) + 128));
            }
        }
    }

    private static Uri saveToPhotos(Context context, File video, String name) throws IOException {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw new IOException("Saving videos needs Android 10 or newer");
        }
        String fileName = name.replaceAll("[^A-Za-z0-9 _-]", "").trim();
        if (fileName.isEmpty()) {
            fileName = "exCover";
        }
        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME, fileName + " (cover lock).mp4");
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/exCover");
        values.put(MediaStore.Video.Media.IS_PENDING, 1);
        ContentResolver resolver = context.getContentResolver();
        Uri item = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
        if (item == null) {
            throw new IOException("Photos couldn't create the video");
        }
        try (InputStream input = new FileInputStream(video);
             OutputStream output = resolver.openOutputStream(item)) {
            if (output == null) {
                throw new IOException("Photos couldn't open the video");
            }
            input.transferTo(output);
        } catch (IOException error) {
            resolver.delete(item, null, null);
            throw error;
        }
        values.clear();
        values.put(MediaStore.Video.Media.IS_PENDING, 0);
        resolver.update(item, values, null, null);
        return item;
    }
}
