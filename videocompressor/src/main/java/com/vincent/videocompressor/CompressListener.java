package com.vincent.videocompressor;

public interface CompressListener {
    void onStart();
    void onSuccess();
    void onFail();
    void onProgress(float percent);
}
