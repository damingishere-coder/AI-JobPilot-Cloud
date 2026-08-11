package com.getjobs.cloud.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.UUID;

public class LocalPrivateFileStorage implements FileStorage {
    private final Path root;

    public LocalPrivateFileStorage(Path root) {
        this.root = Objects.requireNonNull(root, "local storage root").toAbsolutePath().normalize();
    }

    @Override
    public StoredObject store(InputStream input, long contentLength, String contentType) throws IOException {
        Objects.requireNonNull(input, "input");
        if (contentLength < 0) {
            throw new IllegalArgumentException("contentLength 不能为负数");
        }
        String id = UUID.randomUUID().toString();
        String storageKey = "objects/" + id.substring(0, 2) + "/" + id;
        Path target = resolve(storageKey);
        Files.createDirectories(target.getParent());
        long written = Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
        if (written != contentLength) {
            Files.deleteIfExists(target);
            throw new IOException("写入长度与声明长度不一致");
        }
        return new StoredObject(storageKey, written, contentType);
    }

    @Override
    public InputStream read(String storageKey) throws IOException {
        return Files.newInputStream(resolve(storageKey));
    }

    @Override
    public boolean exists(String storageKey) {
        return Files.isRegularFile(resolve(storageKey));
    }

    @Override
    public void delete(String storageKey) throws IOException {
        Files.deleteIfExists(resolve(storageKey));
    }

    @Override
    public void checkHealth() throws IOException {
        Files.createDirectories(root);
        if (!Files.isDirectory(root) || !Files.isWritable(root)) {
            throw new IOException("私有文件目录不可写");
        }
    }

    private Path resolve(String storageKey) {
        if (storageKey == null || storageKey.isBlank()) {
            throw new IllegalArgumentException("storageKey 不能为空");
        }
        Path resolved = root.resolve(storageKey).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("非法 storageKey");
        }
        return resolved;
    }
}
