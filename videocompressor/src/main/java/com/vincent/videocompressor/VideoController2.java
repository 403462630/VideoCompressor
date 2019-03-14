package com.vincent.videocompressor;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.os.AsyncTask;

import java.io.IOException;
import java.nio.ByteBuffer;

public class VideoController2 {
    final int TIMEOUT_USEC = 2500;
    public static final String MIME_TYPE = "video/avc";
    public static final int COMPRESS_QUALITY_HIGH = 1;
    public static final int COMPRESS_QUALITY_MEDIUM = 2;
    public static final int COMPRESS_QUALITY_LOW = 3;

    private MediaCodec decoder;
    private MediaCodec encoder;

    private MediaExtractor mediaExtractor;
    private MediaMuxer mediaMuxer;

    private MediaFormat videoFormat;
    private MediaFormat audioFormat;

    private MediaFormat originVideoFormat;
    private MediaFormat originAudioFormat;

    private int originVideoTraceIndex = -1;
    private int originAudioTraceIndex = -1;
    private int originWidth;
    private int originHeight;
    private int originBitrate;

    private int width;
    private int height;
    private int bitrate;
    private long duration;

    private int videoTraceIndex = -1;
    private int audioTraceIndex = -1;

    private CompressListener compressListener;
    private VideoCompressTask compressTask;

    private boolean isCompressing;

    private VideoController2() {}

    private void init(String videoPath, String outPath) throws IOException {
        mediaExtractor = new MediaExtractor();
        mediaExtractor.setDataSource(videoPath);

        originVideoTraceIndex = MediaMuxerUtils.findVideoTrackIndex(mediaExtractor);
        originAudioTraceIndex = MediaMuxerUtils.findAudioTrackIndex(mediaExtractor);

        originVideoFormat = mediaExtractor.getTrackFormat(originVideoTraceIndex);
        originAudioFormat = mediaExtractor.getTrackFormat(originAudioTraceIndex);

        mediaMuxer = new MediaMuxer(outPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
    }

    private void initDecoder() throws IOException {
        if (decoder == null) {
            decoder = MediaCodec.createDecoderByType(MIME_TYPE);
            decoder.configure(originVideoFormat, null, null, 0);
        }
    }

    private void initEncoder() throws IOException {
        if (encoder == null) {
            encoder = MediaCodec.createEncoderByType(MIME_TYPE);
            MediaFormat mediaFormat = MediaFormat.createVideoFormat(MIME_TYPE, width, height);
            mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
            mediaFormat.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
            mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, selectColorFormat(encoder.getCodecInfo(), MIME_TYPE));
//                mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            mediaFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 5);
            encoder.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        }
    }

    public void syncCompress() {
        if (!isCompressing) {
            compressTask = new VideoCompressTask();
            compressTask.execute();
            isCompressing = true;
        }
    }

    private boolean compress() {
        try {
            initDecoder();
            decoder.start();
            initEncoder();
            encoder.start();

            if (originVideoFormat != null) {
                audioTraceIndex = mediaMuxer.addTrack(originAudioFormat);
                audioFormat = originAudioFormat;
            }

            mediaExtractor.selectTrack(originVideoTraceIndex);
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            boolean isDecoderDone = false;
            while (!isDecoderDone) {
                int decoderInputBufferIndex = decoder.dequeueInputBuffer(TIMEOUT_USEC);
                if (decoderInputBufferIndex >= 0) {
                    ByteBuffer byteBuffer = decoder.getInputBuffer(decoderInputBufferIndex);
                    int size  = mediaExtractor.readSampleData(byteBuffer, 0);
                    if (size < 0) {
                        decoder.queueInputBuffer(decoderInputBufferIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        isDecoderDone = true;
                    } else {
                        decoder.queueInputBuffer(decoderInputBufferIndex, 0, size, mediaExtractor.getSampleTime(), mediaExtractor.getSampleFlags());
                        mediaExtractor.advance();
                    }
//                    Log.i("compress", "size: " + size);
                }

                while (true) {
                    int decoderOutputBufferIndex = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_USEC);
//                    Log.i("compress", "decoderOutputBufferIndex: " + decoderOutputBufferIndex);
                    if (decoderOutputBufferIndex >= 0) {
                        ByteBuffer byteBuffer = decoder.getOutputBuffer(decoderOutputBufferIndex);
                        encodeVideoData(bufferInfo, byteBuffer);
                        decoder.releaseOutputBuffer(decoderOutputBufferIndex, false);
                        compressTask.onProgress(bufferInfo.presentationTimeUs * 1.0f / duration * 100);
                    } else if (decoderOutputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        // do no thing
                    } else {
                        break;
                    }
                }
            }

            mediaExtractor.unselectTrack(originVideoTraceIndex);
            if (audioTraceIndex != -1 && originAudioFormat != null) {
                mediaExtractor.selectTrack(originAudioTraceIndex);
                encodeAudioData();
            }
            compressTask.onProgress(100);
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        } finally {
            if (decoder != null) {
                decoder.stop();
                decoder.release();
                decoder = null;
            }
            if (encoder != null) {
                encoder.stop();
                encoder.release();
                encoder = null;
            }
            if (mediaExtractor != null) {
                mediaExtractor.release();
            }
            if (mediaMuxer != null) {
                try {
                    mediaMuxer.stop();
                    mediaMuxer.release();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private void encodeVideoData(MediaCodec.BufferInfo bufferInfo, ByteBuffer byteBuffer) {
        int encoderInputBufferIndex = encoder.dequeueInputBuffer(TIMEOUT_USEC);
//        Log.i("compress", "encodeVideoData, encoderInputBufferIndex: " + encoderInputBufferIndex);
        if (encoderInputBufferIndex >= 0) {
            ByteBuffer encodeByteBuffer = encoder.getInputBuffer(encoderInputBufferIndex);
            byte[] outData = new byte[bufferInfo.size - bufferInfo.offset];
            byteBuffer.get(outData, bufferInfo.offset, bufferInfo.size);
            encodeByteBuffer.clear();
            encodeByteBuffer.put(outData);
            encoder.queueInputBuffer(encoderInputBufferIndex, 0, outData.length, bufferInfo.presentationTimeUs, bufferInfo.flags);
            MediaCodec.BufferInfo encodeOutBufferInfo = new MediaCodec.BufferInfo();
            while (true) {
                int encoderOutputBufferIndex = encoder.dequeueOutputBuffer(encodeOutBufferInfo, TIMEOUT_USEC);
                if (encoderOutputBufferIndex >= 0) {
//                    Log.i("compress", "encodeVideoData, presentationTimeUs: " + encodeOutBufferInfo.presentationTimeUs + ", offset: " + encodeOutBufferInfo.offset + ", size: " + encodeOutBufferInfo.size);
                    ByteBuffer encoderOutBuffer = encoder.getOutputBuffer(encoderOutputBufferIndex);
                    mediaMuxer.writeSampleData(videoTraceIndex, encoderOutBuffer, encodeOutBufferInfo);
                    encoder.releaseOutputBuffer(encoderOutputBufferIndex, false);
                } else if (encoderOutputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (videoTraceIndex == -1) {
                        videoFormat = encoder.getOutputFormat();
                        videoTraceIndex = mediaMuxer.addTrack(videoFormat);
                        mediaMuxer.start();
                    }
                } else {
                    break;
                }
            }
        }
    }

    private void encodeAudioData() {
        int byteSize = audioFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE);
        ByteBuffer byteBuffer = ByteBuffer.allocateDirect(byteSize);
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        while (true) {
            int size  = mediaExtractor.readSampleData(byteBuffer, 0);
            if (size < 0) {
                break;
            }
            bufferInfo.size = size;
            bufferInfo.presentationTimeUs = mediaExtractor.getSampleTime();
            bufferInfo.offset = 0;
            bufferInfo.flags = mediaExtractor.getSampleFlags();
//            Log.i("writeSampleData", "flags: " + bufferInfo.flags + ", presentationTimeUs: " + bufferInfo.presentationTimeUs);
            mediaMuxer.writeSampleData(audioTraceIndex, byteBuffer, bufferInfo);
            mediaExtractor.advance();
        }
    }

    private static boolean isRecognizedFormat(int colorFormat) {
        switch (colorFormat) {
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar:
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedPlanar:
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar:
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar:
            case MediaCodecInfo.CodecCapabilities.COLOR_TI_FormatYUV420PackedSemiPlanar:
                return true;
            default:
                return false;
        }
    }

    public static int selectColorFormat(MediaCodecInfo codecInfo, String mimeType) {
        MediaCodecInfo.CodecCapabilities capabilities = codecInfo.getCapabilitiesForType(mimeType);
        int lastColorFormat = 0;
        for (int i = 0; i < capabilities.colorFormats.length; i++) {
            int colorFormat = capabilities.colorFormats[i];
            if (isRecognizedFormat(colorFormat)) {
                lastColorFormat = colorFormat;
                if (!(codecInfo.getName().equals("OMX.SEC.AVC.Encoder") && colorFormat == 19)) {
                    return colorFormat;
                }
            }
        }
        return lastColorFormat;
    }

    private void prepareVideoSize(String videoPath, int quality, int outWidth, int outHeight, int outBitrate) {
        this.width = outWidth;
        this.height = outHeight;
        this.bitrate = outBitrate;

        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        retriever.setDataSource(videoPath);
        this.originWidth = Integer.valueOf(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
        this.originHeight = Integer.valueOf(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
        this.originBitrate = Integer.valueOf(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE));
        this.duration = Long.valueOf(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)) * 1000;
        retriever.release();

        switch (quality) {
            case COMPRESS_QUALITY_HIGH:
                this.width = originWidth; // * 2 / 3;
                this.height = originHeight; // * 2 / 3;
                this.bitrate = originBitrate * 2 / 3 ;
                if (this.bitrate > this.width * this.height * 20) {
                    this.bitrate = this.width * this.height * 20;
                }
                break;
            case COMPRESS_QUALITY_MEDIUM:
                this.width = originWidth; // / 2;
                this.height = originHeight; // / 2;
                this.bitrate = originBitrate / 2;
                if (this.bitrate > this.width * this.height * 10) {
                    this.bitrate = this.width * this.height * 10;
                }
                break;
            case COMPRESS_QUALITY_LOW:
                this.width = originWidth; // / 2;
                this.height = originHeight; // / 2;
                this.bitrate = originBitrate / 3;
                if (this.bitrate > this.width * this.height * 4) {
                    this.bitrate = this.width * this.height * 4;
                }
                break;
        }
        if (this.width <= 0) {
            this.width = originWidth;
        }
        if (this.height <= 0) {
            this.height = originHeight;
        }
        if (this.bitrate <= 0) {
            this.bitrate = originBitrate;
        }

        this.width = this.width + (this.width % 4 == 0 ? 0 : (4 - this.width % 4));
        this.height = this.height + (this.height % 4 == 0 ? 0 : (4 - this.height % 4));
    }

    public static class Builder {
        private String videoPath;
        private String outPath;
        private int width;
        private int height;
        private int quality;
        private int bitrate;
        private CompressListener listener;

        public Builder setVideoSource(String videoPath) {
            this.videoPath = videoPath;
            return this;
        }

        public Builder setVideoOutPath(String outPath) {
            this.outPath = outPath;
            return this;
        }

        public Builder setVideoSize(int width, int height) {
            this.width = width;
            this.height = height;
            return this;
        }

        public Builder setBitrate(int bitrate) {
            this.bitrate = bitrate;
            return this;
        }

        public Builder setQuality(int quality) {
            this.quality = quality;
            return this;
        }

        public Builder setCompressListener(CompressListener listener) {
            this.listener = listener;
            return this;
        }

        public VideoController2 build() {
            VideoController2 controller = new VideoController2();
            try {
                controller.init(videoPath, outPath);
                controller.prepareVideoSize(videoPath, quality, width, height, bitrate);
                controller.compressListener = listener;
            } catch (Exception e) {
                e.printStackTrace();
            }
            return controller;
        }
    }

    public interface CompressListener {
        void onStart();
        void onSuccess();
        void onFail();
        void onProgress(float percent);
    }

    private class VideoCompressTask extends AsyncTask<String, Float, Boolean> {


        public VideoCompressTask() {
        }

        public void onProgress(float percent) {
            publishProgress(percent);
        }

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            if (compressListener != null) {
                compressListener.onStart();
            }
        }

        @Override
        protected Boolean doInBackground(String... paths) {
            return compress();
        }

        @Override
        protected void onProgressUpdate(Float... percent) {
            super.onProgressUpdate(percent);
            if (compressListener != null) {
                compressListener.onProgress(percent[0]);
            }
        }

        @Override
        protected void onPostExecute(Boolean result) {
            super.onPostExecute(result);
            if (compressListener != null) {
                if (result) {
                    compressListener.onSuccess();
                } else {
                    compressListener.onFail();
                }
            }
            isCompressing = false;
        }
    }
}
