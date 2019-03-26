# VideoCompressor
A High-performance video compressor for Android using Hardware decoding and encoding API(MediaCodec).

## Demo
![Demo](/pic/Demo.gif)

## Usage
### Call compressVideoLow, compressVideoMedium and compressVideoHigh that indicates 3 quality of compressing.
        new VideoCompress.Builder()
            .setVideoSource(tv_input.getText().toString())
            .setVideoOutPath(destPath)
            .setQuality(VideoCompress.COMPRESS_QUALITY_HIGH)
            .setCompressListener(new CompressListener() {
                @Override
                public void onStart() {
                    
                }

                @Override
                public void onSuccess() {
                   
                }

                @Override
                public void onFail() {
                    
                }

                @Override
                public void onProgress(float percent) {
                    
                }
            })
            .build()
            .compress();
            
        // SVideoCompress 比 VideoCompress 兼容性更好
        new SVideoCompress.Builder()
            .setVideoSource(tv_input2.getText().toString())
            .setVideoOutPath(destPath)
            .setQuality(SVideoCompress.COMPRESS_QUALITY_HIGH)
            .setCompressListener(new CompressListener() {
                @Override
                public void onStart() {
                    
                }

                @Override
                public void onSuccess() {
                    
                }

                @Override
                public void onFail() {
                    
                }

                @Override
                public void onProgress(float percent) {
                    
                }
            })
            .build()
            .compress();    