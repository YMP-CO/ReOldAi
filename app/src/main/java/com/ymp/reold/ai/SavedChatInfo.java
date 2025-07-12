package com.ymp.reold.ai;

public class SavedChatInfo {
    String name;
    String filePath;
    long timestamp;

    public SavedChatInfo(String name, String filePath, long timestamp) {
        this.name = name;
        this.filePath = filePath;
        this.timestamp = timestamp;
    }

    public String getName() {
        return name;
    }

    public String getFilePath() {
        return filePath;
    }

    public long getTimestamp() {
        return timestamp;
    }
}