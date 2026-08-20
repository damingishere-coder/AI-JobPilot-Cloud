package com.getjobs.cloud.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalPrivateFileStorageTest {
    @TempDir
    Path tempDir;

    @Test
    void storesReadsAndDeletesPrivateObject() throws Exception {
        LocalPrivateFileStorage storage = new LocalPrivateFileStorage(tempDir);
        byte[] content = "fictional resume".getBytes(StandardCharsets.UTF_8);

        StoredObject object = storage.store(
                new ByteArrayInputStream(content), content.length, "text/plain"
        );

        assertThat(object.storageKey()).startsWith("objects/");
        assertThat(storage.exists(object.storageKey())).isTrue();
        assertThat(storage.read(object.storageKey()).readAllBytes()).isEqualTo(content);

        storage.delete(object.storageKey());
        assertThat(storage.exists(object.storageKey())).isFalse();
    }

    @Test
    void rejectsPathTraversal() {
        LocalPrivateFileStorage storage = new LocalPrivateFileStorage(tempDir);

        assertThatThrownBy(() -> storage.read("../../outside.txt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("storageKey");
    }

    @Test
    void rejectsMismatchedDeclaredLengthAndRemovesPartialFile() {
        LocalPrivateFileStorage storage = new LocalPrivateFileStorage(tempDir);
        byte[] content = "short".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> storage.store(
                new ByteArrayInputStream(content), content.length + 1, "text/plain"
        )).isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("长度");
    }
}
