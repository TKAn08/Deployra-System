package com.paas.deployra.storage;

import com.paas.deployra.paas.SourceUpload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalSourceStorageTest {

    private static InputStream stream(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(LocalSourceStorage storage, String key) throws IOException {
        try (InputStream in = storage.open(key)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void backendIsLocal(@TempDir Path tmp) {
        assertEquals(SourceUpload.StorageBackend.LOCAL, new LocalSourceStorage(tmp.toString()).backend());
    }

    @Test
    void putThenOpenReturnsSameBytes(@TempDir Path tmp) throws Exception {
        LocalSourceStorage storage = new LocalSourceStorage(tmp.toString());
        storage.put("uploads/42/abc-123.zip", stream("nội dung zip"),
                "nội dung zip".getBytes(StandardCharsets.UTF_8).length);
        assertEquals("nội dung zip", read(storage, "uploads/42/abc-123.zip"));
        assertTrue(Files.isRegularFile(tmp.resolve("uploads/42/abc-123.zip")));
    }

    @Test
    void putAgainReplacesTheFile(@TempDir Path tmp) throws Exception {
        LocalSourceStorage storage = new LocalSourceStorage(tmp.toString());
        storage.put("a.zip", stream("v1"), 2);
        storage.put("a.zip", stream("version two"), 11);
        assertEquals("version two", read(storage, "a.zip"));
    }

    @Test
    void unknownSizeIsAccepted(@TempDir Path tmp) throws Exception {
        LocalSourceStorage storage = new LocalSourceStorage(tmp.toString());
        storage.put("a.zip", stream("abc"), -1);
        assertEquals("abc", read(storage, "a.zip"));
    }

    @Test
    void sizeMismatchIsRejectedAndLeavesNothingBehind(@TempDir Path tmp) throws Exception {
        LocalSourceStorage storage = new LocalSourceStorage(tmp.toString());
        assertThrows(StorageException.class, () -> storage.put("uploads/1/x.zip", stream("abc"), 5));
        assertFalse(Files.exists(tmp.resolve("uploads/1/x.zip")), "không được để file dở dưới đúng key");
        try (var s = Files.list(tmp.resolve("uploads/1"))) {
            assertEquals(List.of(), s.toList(), "không được sót file tạm");
        }
    }

    @Test
    void failedOverwriteKeepsOldContent(@TempDir Path tmp) throws Exception {
        LocalSourceStorage storage = new LocalSourceStorage(tmp.toString());
        storage.put("a.zip", stream("good"), 4);
        assertThrows(StorageException.class, () -> storage.put("a.zip", stream("bad!"), 99));
        assertEquals("good", read(storage, "a.zip"));
    }

    @Test
    void deleteRemovesFileAndIsIdempotent(@TempDir Path tmp) throws Exception {
        LocalSourceStorage storage = new LocalSourceStorage(tmp.toString());
        storage.put("a.zip", stream("x"), 1);
        storage.delete("a.zip");
        assertFalse(Files.exists(tmp.resolve("a.zip")));
        storage.delete("a.zip"); // lần hai không lỗi
    }

    @Test
    void openMissingFileThrows(@TempDir Path tmp) {
        LocalSourceStorage storage = new LocalSourceStorage(tmp.toString());
        assertThrows(StorageException.class, () -> storage.open("uploads/1/none.zip"));
    }

    @Test
    void rejectsKeysThatCouldEscapeTheStorageRoot(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("store");
        LocalSourceStorage storage = new LocalSourceStorage(root.toString());
        String[] bad = { "../outside.zip", "a/../../outside.zip", "/etc/passwd", "uploads//x.zip", "uploads/",
                "", "a b.zip", "a\\b.zip", "..", ".hidden", "a/./b", "x\0y" };
        for (String key : bad) {
            assertThrows(StorageException.class, () -> storage.put(key, stream("x"), 1), "phải chặn key: " + key);
            assertThrows(StorageException.class, () -> storage.open(key), "phải chặn key: " + key);
            assertThrows(StorageException.class, () -> storage.delete(key), "phải chặn key: " + key);
        }
        assertThrows(StorageException.class, () -> storage.put(null, stream("x"), 1));
        assertFalse(Files.exists(tmp.resolve("outside.zip")));
    }
}