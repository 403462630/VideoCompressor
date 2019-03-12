package com.vincent.videocompressor;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;

public class MediaMuxerHelper {
    public static String VIDEO_MINE_TYPE = "video/";
    public static String AUDIO_MINE_TYPE = "audio/";

    /**
     * 有严重bug，不支持
     * @param videoList
     * @param desPath
     * @return
     */
    public boolean mergeVideoList(List<String> videoList, String desPath) {
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

    private void addMediaFormat(MediaMuxer mediaMuxer, List<String> videoList, int[] traceIndexes) {
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
    public boolean mergeVideo(String videoPath, String audioPath, String desPath) {
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
    public boolean splitVideo(String srcPath, String desPath) {
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
    public boolean splitAudio(String srcPath, String desPath) {
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

    private long writeSampleData(MediaExtractor mediaExtractor, MediaMuxer mediaMuxer, int byteSize, int trackIndex) {
        return writeSampleData(mediaExtractor, mediaMuxer, byteSize, trackIndex, 0);
    }

    private long writeSampleData(MediaExtractor mediaExtractor, MediaMuxer mediaMuxer, int byteSize, int trackIndex, long offsetTimeUs) {
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

    private int findVideoTrackIndex(MediaExtractor mediaExtractor) {
        for (int i = 0; i < mediaExtractor.getTrackCount(); i++) {
            MediaFormat mediaFormat = mediaExtractor.getTrackFormat(i);
            if (mediaFormat.getString(MediaFormat.KEY_MIME).startsWith(VIDEO_MINE_TYPE)) {
                return i;
            }
        }
        return -1;
    }

    private int findAudioTrackIndex(MediaExtractor mediaExtractor) {
        for (int i = 0; i < mediaExtractor.getTrackCount(); i++) {
            MediaFormat mediaFormat = mediaExtractor.getTrackFormat(i);
            if (mediaFormat.getString(MediaFormat.KEY_MIME).startsWith(AUDIO_MINE_TYPE)) {
                return i;
            }
        }
        return -1;
    }

    public boolean test(String srcPath, String desPath) {
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(desPath, false);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }
        MediaExtractor mediaExtractor = new MediaExtractor();
        MediaMuxer mediaMuxer = null;
        try {
            mediaExtractor.setDataSource(srcPath);
            int videoTrackIndex = findVideoTrackIndex(mediaExtractor);
            if (videoTrackIndex != -1) {
                mediaExtractor.selectTrack(videoTrackIndex);
//                mediaExtractor.seekTo(3886200, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                MediaFormat videoFormat = mediaExtractor.getTrackFormat(videoTrackIndex);
                MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                retriever.setDataSource(srcPath);
                int width = Integer.valueOf(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
                int height = Integer.valueOf(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
                int bitrate = Integer.valueOf(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE));
                retriever.release();
//                int maxBufferSize = videoFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE);
                mediaMuxer = new MediaMuxer(desPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

                MediaCodec videoDecoder = MediaCodec.createDecoderByType("video/avc");
                videoDecoder.configure(videoFormat, null, null, 0);
                videoDecoder.start();

                MediaCodec videoEncoder = MediaCodec.createEncoderByType("video/avc");
                MediaFormat mediaFormat = MediaFormat.createVideoFormat("video/avc", width, height);
                mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
                mediaFormat.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
                mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, VideoController.selectColorFormat(videoEncoder.getCodecInfo(), "video/avc"));
//                mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
                mediaFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 5);
                videoEncoder.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                videoEncoder.start();

                int trackIndex = -1;

                while (true) {
                    int inputBufferIndex = videoDecoder.dequeueInputBuffer(100);
                    if (inputBufferIndex >= 0) {
                        ByteBuffer inputBuffer = videoDecoder.getInputBuffer(inputBufferIndex);
                        int size  = mediaExtractor.readSampleData(inputBuffer, 0);
                        if (size < 0) {
                            break;
                        }
                        videoDecoder.queueInputBuffer(inputBufferIndex, 0, size, mediaExtractor.getSampleTime(), mediaExtractor.getSampleFlags());
                        mediaExtractor.advance();
                    }

                    while (true) {
                        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
                        int outBufferIndex = videoDecoder.dequeueOutputBuffer(bufferInfo, 100);
                        if (outBufferIndex >= 0) {
                            ByteBuffer outBuffer = videoDecoder.getOutputBuffer(outBufferIndex);
                            byte[] outData = new byte[bufferInfo.size - bufferInfo.offset];
                            outBuffer.get(outData, bufferInfo.offset, bufferInfo.size);


                            int encodeInputBufferIndex = videoEncoder.dequeueInputBuffer(-1);
                            if (encodeInputBufferIndex >= 0) {
                                ByteBuffer encodeInputBuffer = videoEncoder.getInputBuffer(encodeInputBufferIndex);
                                encodeInputBuffer.clear();
                                encodeInputBuffer.put(outData);
                                videoEncoder.queueInputBuffer(encodeInputBufferIndex, 0, outData.length, bufferInfo.presentationTimeUs, bufferInfo.flags);
                            }
                            while (true) {
                                MediaCodec.BufferInfo encodeOutBufferInfo = new MediaCodec.BufferInfo();
                                int encodeOutBufferIndex = videoEncoder.dequeueOutputBuffer(encodeOutBufferInfo, 100);
                                if (encodeOutBufferIndex >= 0) {
                                    ByteBuffer encodeOutBuffer = videoEncoder.getOutputBuffer(encodeOutBufferIndex);
//                                    byte[] encodeOutData = new byte[bufferInfo.size];
//                                    encodeOutBuffer.get(encodeOutData);
                                    mediaMuxer.writeSampleData(trackIndex, encodeOutBuffer, encodeOutBufferInfo);
                                    videoEncoder.releaseOutputBuffer(encodeOutBufferIndex, false);
                                } else if (encodeOutBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                                    if (trackIndex == -1) {
                                        trackIndex = mediaMuxer.addTrack(videoEncoder.getOutputFormat());
                                        mediaMuxer.start();
                                    }
                                    break;
                                } else {
                                    break;
                                }
                            }


//                            mediaMuxer.writeSampleData(trackIndex, outBuffer, bufferInfo);
                            videoDecoder.releaseOutputBuffer(outBufferIndex, false);
                        } else if (outBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            if (trackIndex == -1) {
//                                trackIndex = mediaMuxer.addTrack(videoEncoder.getOutputFormat());
//                                mediaMuxer.start();
                            }
                            break;
                        } else {
                            break;
                        }
                    }
                }
                videoEncoder.release();
                videoDecoder.release();
//                writeSampleData(mediaExtractor, mediaMuxer, maxBufferSize, trackIndex);
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

//    private void encodeData(MediaMuxer mediaMuxer, int trackIndex, MediaCodec videoEncoder, byte[] data) throws IOException {
//
//    }
}
