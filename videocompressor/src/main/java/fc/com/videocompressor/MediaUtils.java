package fc.com.videocompressor;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.text.TextUtils;
import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;

public class MediaUtils {
    public static String VIDEO_MINE_TYPE = "video/";
    public static String AUDIO_MINE_TYPE = "audio/";


    public static boolean isSupportCompress(String videoPath) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        retriever.setDataSource(videoPath);
        int width = Integer.valueOf(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
        int height = Integer.valueOf(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
        int bitrate = Integer.valueOf(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE));
//        long duration = Long.valueOf(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
        retriever.release();
        return bitrate > width * height * 2;
    }

    public static void deleteFile(String filePath) {
        if (TextUtils.isEmpty(filePath)) {
            return;
        }
        File file = new File(filePath);
        if (file.exists() && file.isFile()) {
            file.delete();
        }
    }

    /**
     * 添加ADTS头，如果要与视频流合并就不用添加，单独AAC文件就需要添加，否则无法正常播放
     */
    private static void addADTStoPacket(int sampleRateType, byte[] packet, int packetLen) {
        int profile = 2; // AAC LC
        int chanCfg = 2; // CPE

        packet[0] = (byte) 0xFF;
        packet[1] = (byte) 0xF9;
        packet[2] = (byte) (((profile - 1) << 6) + (sampleRateType << 2) + (chanCfg >> 2));
        packet[3] = (byte) (((chanCfg & 3) << 6) + (packetLen >> 11));
        packet[4] = (byte) ((packetLen & 0x7FF) >> 3);
        packet[5] = (byte) (((packetLen & 7) << 5) + 0x1F);
        packet[6] = (byte) 0xFC;
    }

    private static void extractPCMAudioData2(String videoPath, String desPath) {
        MediaExtractor videoExtractor = new MediaExtractor();
        FileOutputStream fileOutputStream = null;
        BufferedOutputStream mAudioBos = null;
        MediaCodec decoder = null;
        MediaCodec encoder = null;
        try {
            fileOutputStream = new FileOutputStream(desPath);
            mAudioBos = new BufferedOutputStream(fileOutputStream, 200 * 1024);
            videoExtractor.setDataSource(videoPath);

            int audioTraceIndex = findAudioTrackIndex(videoExtractor);
            MediaFormat audioFormat = videoExtractor.getTrackFormat(audioTraceIndex);
            String mineType = audioFormat.getString(MediaFormat.KEY_MIME);

            decoder = MediaCodec.createDecoderByType(mineType);
            decoder.configure(audioFormat, null, null, 0);
            decoder.start();

            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            MediaFormat encodeMediaFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 44100, audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
            encodeMediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, audioFormat.getInteger(MediaFormat.KEY_BIT_RATE));
            encodeMediaFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, audioFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE));

            encoder.configure(encodeMediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();

            videoExtractor.selectTrack(audioTraceIndex);
            int timeout = 1000;
            boolean isDecoderDone = false;
            while (!isDecoderDone) {
                int inputIndex = decoder.dequeueInputBuffer(timeout);
                if (inputIndex >= 0) {
                    ByteBuffer byteBuffer = decoder.getInputBuffer(inputIndex);
                    int size = videoExtractor.readSampleData(byteBuffer, 0);
                    if (size < 0) {
                        decoder.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        isDecoderDone = true;
                    } else {
                        long time = videoExtractor.getSampleTime();
                        decoder.queueInputBuffer(inputIndex, 0, size, time, videoExtractor.getSampleFlags());
                        videoExtractor.advance();
                        Log.i("extractPCMAudioData", "decodeInputIndex: " + inputIndex + ", time: " + time);
                    }
                }

                MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
                while (true) {
                    int outputIndex = decoder.dequeueOutputBuffer(bufferInfo, timeout);
                    Log.i("extractPCMAudioData", "decodeOutputIndex: " + outputIndex);
                    if (outputIndex >= 0) {
                        ByteBuffer byteBuffer = decoder.getOutputBuffer(outputIndex);

                        int encodeInputIndex = encoder.dequeueInputBuffer(timeout);
                        Log.i("extractPCMAudioData", "encodeInputIndex: " + encodeInputIndex);
                        if (encodeInputIndex >= 0) {
                            ByteBuffer encodeByteBuffer = encoder.getInputBuffer(encodeInputIndex);
                            byte[] outData = new byte[bufferInfo.size];
                            byteBuffer.get(outData, bufferInfo.offset, bufferInfo.size);
                            encodeByteBuffer.clear();
                            encodeByteBuffer.limit(outData.length);
                            encodeByteBuffer.put(outData);
                            encoder.queueInputBuffer(encodeInputIndex, 0, outData.length, bufferInfo.presentationTimeUs, bufferInfo.flags);

                            MediaCodec.BufferInfo encodeBufferInfo = new MediaCodec.BufferInfo();
                            while (true) {
                                int encodeOutputIndex = encoder.dequeueOutputBuffer(encodeBufferInfo, timeout);
                                Log.i("extractPCMAudioData", "encodeOutputIndex: " + encodeOutputIndex);
                                if (encodeOutputIndex >= 0) {
                                    Log.i("extractPCMAudioData", "presentationTimeUs: " + encodeBufferInfo.presentationTimeUs + ", size: " + encodeBufferInfo.size + ", offset: " + encodeBufferInfo.offset);
                                    ByteBuffer encoderOutputByteBuffer = encoder.getOutputBuffer(encodeOutputIndex);
                                    encoderOutputByteBuffer.position(encodeBufferInfo.offset);
                                    encoderOutputByteBuffer.limit(encodeBufferInfo.offset + encodeBufferInfo.size);

                                    byte[] data = new byte[encodeBufferInfo.size + 7];
                                    addADTStoPacket(44100, data, data.length);
                                    encoderOutputByteBuffer.get(data, 7, encodeBufferInfo.size);

                                    mAudioBos.write(data, 0, data.length);
                                    mAudioBos.flush();
                                    encoder.releaseOutputBuffer(encodeOutputIndex, false);
                                } else if (encodeOutputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                                    // do no thing
                                } else {
                                    break;
                                }
                            }
                        }
                        decoder.releaseOutputBuffer(outputIndex, false);
                    } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        // do no thing
                    } else {
                        break;
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (decoder != null) {
                decoder.stop();
                decoder.release();
            }
            if (encoder != null) {
                encoder.stop();
                encoder.release();
            }
            videoExtractor.release();
//            if (mediaMuxer != null) {
//                mediaMuxer.stop();
//                mediaMuxer.release();
//            }
            if (fileOutputStream != null) {
                try {
                    fileOutputStream.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            if (mAudioBos != null) {
                try {
                    mAudioBos.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private static short[] toShortArray(byte[] src) {
        int count = src.length;
        short[] dest = new short[count];
        for (int i = 0; i < count; i++) {
            dest[i] = (short) (dest[i] & 0xff);
        }
        return dest;
    }

    private static byte[] toByteArray(short[] src) {
        int count = src.length;
        byte[] dest = new byte[count << 1];
        for (int i = 0; i < count; i++) {
            dest[i * 2 +1] = (byte) ((src[i] & 0xFF00) >> 8);
            dest[i * 2] = (byte) ((src[i] & 0x00FF));
        }
        return dest;
    }

    private static void extractPCMAudioData(String videoPath, String desPath) {
        MediaExtractor videoExtractor = new MediaExtractor();
        MediaMuxer mediaMuxer = null;
        MediaCodec decoder = null;
        MediaCodec encoder = null;
        try {
            videoExtractor.setDataSource(videoPath);
            mediaMuxer = new MediaMuxer(desPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            int audioTraceIndex = findAudioTrackIndex(videoExtractor);
            MediaFormat audioFormat = videoExtractor.getTrackFormat(audioTraceIndex);
            String mineType = audioFormat.getString(MediaFormat.KEY_MIME);
//            int bytesize = audioFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE);
            int aIndex = -1;

            decoder = MediaCodec.createDecoderByType(mineType);
            decoder.configure(audioFormat, null, null, 0);
            decoder.start();

            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            MediaFormat encodeMediaFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 44100, audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
            encodeMediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, audioFormat.getInteger(MediaFormat.KEY_BIT_RATE));
//            encodeMediaFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            encodeMediaFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, audioFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE));

            encoder.configure(encodeMediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();

            videoExtractor.selectTrack(audioTraceIndex);
            int timeout = 1000;
            boolean isDecoderDone = false;
            while (!isDecoderDone) {
                int inputIndex = decoder.dequeueInputBuffer(timeout);
                if (inputIndex >= 0) {
                    ByteBuffer byteBuffer = decoder.getInputBuffer(inputIndex);
                    int size = videoExtractor.readSampleData(byteBuffer, 0);
                    if (size < 0) {
                        decoder.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        isDecoderDone = true;
                    } else {
                        long time = videoExtractor.getSampleTime();
                        decoder.queueInputBuffer(inputIndex, 0, size, time, videoExtractor.getSampleFlags());
                        videoExtractor.advance();
                        Log.i("extractPCMAudioData", "decodeInputIndex: " + inputIndex + ", time: " + time);
                    }
                }

                MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
                while (true) {
                    int outputIndex = decoder.dequeueOutputBuffer(bufferInfo, timeout);
                    Log.i("extractPCMAudioData", "decodeOutputIndex: " + outputIndex);
                    if (outputIndex >= 0) {
                        ByteBuffer byteBuffer = decoder.getOutputBuffer(outputIndex);

                        int encodeInputIndex = encoder.dequeueInputBuffer(timeout);
                        Log.i("extractPCMAudioData", "encodeInputIndex: " + encodeInputIndex);
                        if (encodeInputIndex >= 0) {
                            ByteBuffer encodeByteBuffer = encoder.getInputBuffer(encodeInputIndex);
                            byte[] outData = new byte[bufferInfo.size - bufferInfo.offset];
                            byteBuffer.get(outData, bufferInfo.offset, bufferInfo.size);
                            encodeByteBuffer.clear();
                            encodeByteBuffer.put(outData);
                            encoder.queueInputBuffer(encodeInputIndex, 0, outData.length, bufferInfo.presentationTimeUs, bufferInfo.flags);

                            MediaCodec.BufferInfo encodeBufferInfo = new MediaCodec.BufferInfo();
                            while (true) {
                                int encodeOutputIndex = encoder.dequeueOutputBuffer(encodeBufferInfo, timeout);
                                Log.i("extractPCMAudioData", "encodeOutputIndex: " + encodeOutputIndex);
                                if (encodeOutputIndex >= 0) {
                                    Log.i("extractPCMAudioData", "presentationTimeUs: " + encodeBufferInfo.presentationTimeUs + ", size: " + encodeBufferInfo.size);
                                    ByteBuffer encoderOutputByteBuffer = encoder.getOutputBuffer(encodeOutputIndex);

                                    mediaMuxer.writeSampleData(aIndex, encoderOutputByteBuffer, encodeBufferInfo);
                                    encoder.releaseOutputBuffer(encodeOutputIndex, false);
                                } else if (encodeOutputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                                    if (aIndex == -1) {
                                        MediaFormat mediaFormat = encoder.getOutputFormat();
                                        aIndex = mediaMuxer.addTrack(mediaFormat);
                                        mediaMuxer.start();
                                    }
                                } else {
                                    break;
                                }
                            }
                        }
                        decoder.releaseOutputBuffer(outputIndex, false);
                    } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        // do no thing
                    } else {
                        break;
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (decoder != null) {
                decoder.stop();
                decoder.release();
            }
            if (encoder != null) {
                encoder.stop();
                encoder.release();
            }
            videoExtractor.release();
            if (mediaMuxer != null) {
                mediaMuxer.stop();
                mediaMuxer.release();
            }
        }
    }

    /**
     * 不同格式的视频 有严重bug，不支持
     * @param videoList
     * @param desPath
     * @return
     */
    private static boolean mergeVideoList(List<String> videoList, String desPath) {
        MediaMuxer mediaMuxer = null;
        try {
            mediaMuxer = new MediaMuxer(desPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int[] traceIndexes = new int[] {-1, -1};
            addMediaFormat(mediaMuxer, videoList, traceIndexes);
            mediaMuxer.start();
            long offsetTime = 0;
            for (String videoPath : videoList) {
                MediaExtractor videoExtractor = new MediaExtractor();
                videoExtractor.setDataSource(videoPath);
                long videoPresentationTimeUs = 0;
                int videoTraceIndex = findVideoTrackIndex(videoExtractor);
                if (videoTraceIndex != -1) {
                    videoExtractor.selectTrack(videoTraceIndex);
                    MediaFormat videoFormat = videoExtractor.getTrackFormat(videoTraceIndex);
                    int videoBufferSize = videoFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE);
                    videoPresentationTimeUs = writeSampleData(videoExtractor, mediaMuxer, videoBufferSize, traceIndexes[0], offsetTime);
                    videoExtractor.unselectTrack(videoTraceIndex);
                }

                long audioPresentationTimeUs = 0;
                int audioTraceIndex = findAudioTrackIndex(videoExtractor);
                if (audioTraceIndex != -1) {
                    videoExtractor.selectTrack(audioTraceIndex);
                    MediaFormat audioFormat = videoExtractor.getTrackFormat(audioTraceIndex);
                    int audioBufferSize = audioFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE);
                    audioPresentationTimeUs = writeSampleData(videoExtractor, mediaMuxer, audioBufferSize, traceIndexes[1], offsetTime);
                }
                videoExtractor.release();
                offsetTime += Math.max(videoPresentationTimeUs, audioPresentationTimeUs);
            }
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        } finally {
            if (mediaMuxer != null) {
                mediaMuxer.stop();
                mediaMuxer.release();
            }
        }
        return false;
    }

    private static void addMediaFormat(MediaMuxer mediaMuxer, List<String> videoList, int[] traceIndexes) {
        int videoIndex = -1;
        int audioIndex = -1;
        try {
            for (String videoPath : videoList) {
                MediaExtractor videoExtractor = new MediaExtractor();
                videoExtractor.setDataSource(videoPath);

                int videoTraceIndex = findVideoTrackIndex(videoExtractor);
                if (videoTraceIndex != -1) {
                    if (videoIndex == -1) {
                        MediaFormat videoFormat = videoExtractor.getTrackFormat(videoTraceIndex);
                        videoIndex = mediaMuxer.addTrack(videoFormat);
                    }
                }

                int audioTraceIndex = findAudioTrackIndex(videoExtractor);
                if (audioTraceIndex != -1) {
                    if (audioIndex == -1) {
                        MediaFormat audioFormat = videoExtractor.getTrackFormat(audioTraceIndex);
                        audioIndex = mediaMuxer.addTrack(audioFormat);
                    }
                }
                videoExtractor.release();
                if (videoIndex != -1 && audioIndex != -1) {
                    break;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        traceIndexes[0] = videoIndex;
        traceIndexes[1] = audioIndex;
    }

    /**
     * 合并 视频 + 音频
     * @param videoPath 视频文件
     * @param audioPath 音频文件
     * @param desPath 输出文件
     * @return true or false
     */
    public static boolean mergeVideo(String videoPath, String audioPath, String desPath) {
        MediaExtractor videoExtractor = new MediaExtractor();
        MediaExtractor audioExtractor = new MediaExtractor();
        MediaMuxer mediaMuxer = null;
        try {
            videoExtractor.setDataSource(videoPath);

            int videoTraceIndex = findVideoTrackIndex(videoExtractor);
            MediaFormat videoFormat = videoExtractor.getTrackFormat(videoTraceIndex);
            int videoBufferSize = videoFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE);

            audioExtractor.setDataSource(audioPath);
            int audioTraceIndex = findAudioTrackIndex(audioExtractor);
            MediaFormat audioFormat = audioExtractor.getTrackFormat(audioTraceIndex);
            int audioBufferSize = audioFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE);

            mediaMuxer = new MediaMuxer(desPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int videoIndex = mediaMuxer.addTrack(videoFormat);
            int audioIndex = mediaMuxer.addTrack(audioFormat);
            mediaMuxer.start();

            videoExtractor.selectTrack(videoTraceIndex);
            writeSampleData(videoExtractor, mediaMuxer, videoBufferSize, videoIndex);

            audioExtractor.selectTrack(audioTraceIndex);
            writeSampleData(audioExtractor, mediaMuxer, audioBufferSize, audioIndex);
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        } finally {
            if (mediaMuxer != null) {
                mediaMuxer.stop();
                mediaMuxer.release();
            }
            videoExtractor.release();
            audioExtractor.release();
        }

        return false;
    }

    /**
     * 分离视频
     * @param srcPath 原视频
     * @param desPath 输出视频
     * @return true or false
     */
    public static boolean splitVideo(String srcPath, String desPath) {
        MediaExtractor mediaExtractor = new MediaExtractor();
        MediaMuxer mediaMuxer = null;
        try {
            mediaExtractor.setDataSource(srcPath);
            int videoTrackIndex = findVideoTrackIndex(mediaExtractor);
            if (videoTrackIndex != -1) {
                mediaExtractor.selectTrack(videoTrackIndex);
//                mediaExtractor.seekTo(3886200, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                MediaFormat videoFormat = mediaExtractor.getTrackFormat(videoTrackIndex);
                int maxBufferSize = videoFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE);
                mediaMuxer = new MediaMuxer(desPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                int trackIndex = mediaMuxer.addTrack(videoFormat);
                mediaMuxer.start();
                writeSampleData(mediaExtractor, mediaMuxer, maxBufferSize, trackIndex);
                return true;
            } else {
                return false;
            }
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        } finally {
            if (mediaMuxer != null) {
                mediaMuxer.stop();
                mediaMuxer.release();
            }
            mediaExtractor.release();
        }
    }

    /**
     * 分离出音频
     * @param srcPath 原视频
     * @param desPath 输出音频
     * @return true or false
     */
    public static boolean splitAudio(String srcPath, String desPath) {
        MediaExtractor mediaExtractor = new MediaExtractor();
        MediaMuxer mediaMuxer = null;
        try {
            mediaExtractor.setDataSource(srcPath);
            int audioTrackIndex = findAudioTrackIndex(mediaExtractor);
            if (audioTrackIndex != -1) {
                mediaExtractor.selectTrack(audioTrackIndex);
                MediaFormat audioFormat = mediaExtractor.getTrackFormat(audioTrackIndex);
                int maxBufferSize = audioFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE);
                mediaMuxer = new MediaMuxer(desPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                int trackIndex = mediaMuxer.addTrack(audioFormat);
                mediaMuxer.start();
                writeSampleData(mediaExtractor, mediaMuxer, maxBufferSize, trackIndex);
                return true;
            } else {
                return false;
            }
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        } finally {
            if (mediaMuxer != null) {
                mediaMuxer.stop();
                mediaMuxer.release();
            }
            mediaExtractor.release();
        }
    }

    private static long writeSampleData(MediaExtractor mediaExtractor, MediaMuxer mediaMuxer, int byteSize, int trackIndex) {
        return writeSampleData(mediaExtractor, mediaMuxer, byteSize, trackIndex, 0);
    }

    private static long writeSampleData(MediaExtractor mediaExtractor, MediaMuxer mediaMuxer, int byteSize, int trackIndex, long offsetTimeUs) {
        ByteBuffer byteBuffer = ByteBuffer.allocateDirect(byteSize);
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        long presentationTimeUs = 0;
        while (true) {
            int size  = mediaExtractor.readSampleData(byteBuffer, 0);
            if (size < 0) {
                break;
            }
            bufferInfo.size = size;
            bufferInfo.presentationTimeUs = offsetTimeUs + mediaExtractor.getSampleTime();
            bufferInfo.offset = 0;
            bufferInfo.flags = mediaExtractor.getSampleFlags();
//            Log.i("writeSampleData", "flags: " + bufferInfo.flags + ", presentationTimeUs: " + bufferInfo.presentationTimeUs);
            mediaMuxer.writeSampleData(trackIndex, byteBuffer, bufferInfo);
            mediaExtractor.advance();
            presentationTimeUs = bufferInfo.presentationTimeUs;
        }
        return presentationTimeUs - offsetTimeUs;
    }

    static int findVideoTrackIndex(MediaExtractor mediaExtractor) {
        for (int i = 0; i < mediaExtractor.getTrackCount(); i++) {
            MediaFormat mediaFormat = mediaExtractor.getTrackFormat(i);
            if (mediaFormat.getString(MediaFormat.KEY_MIME).startsWith(VIDEO_MINE_TYPE)) {
                return i;
            }
        }
        return -1;
    }

    static int findAudioTrackIndex(MediaExtractor mediaExtractor) {
        for (int i = 0; i < mediaExtractor.getTrackCount(); i++) {
            MediaFormat mediaFormat = mediaExtractor.getTrackFormat(i);
            if (mediaFormat.getString(MediaFormat.KEY_MIME).startsWith(AUDIO_MINE_TYPE)) {
                return i;
            }
        }
        return -1;
    }
}
