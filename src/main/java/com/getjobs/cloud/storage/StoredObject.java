package com.getjobs.cloud.storage;

public record StoredObject(String storageKey, long contentLength, String contentType) {
}
