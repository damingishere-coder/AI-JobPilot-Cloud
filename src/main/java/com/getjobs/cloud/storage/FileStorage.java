package com.getjobs.cloud.storage;

import java.io.IOException;
import java.io.InputStream;

public interface FileStorage {
    StoredObject store(InputStream input, long contentLength, String contentType) throws IOException;

    InputStream read(String storageKey) throws IOException;

    boolean exists(String storageKey) throws IOException;

    void delete(String storageKey) throws IOException;

    void checkHealth() throws IOException;
}
