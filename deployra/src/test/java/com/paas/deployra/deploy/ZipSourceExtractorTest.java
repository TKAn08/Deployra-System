package com.paas.deployra.deploy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZipSourceExtractorTest {

    private static final long MB = 1024 * 1024;

    private ZipSourceExtractor extractor() {
        return new ZipSourceExtractor(1000, 5 * MB, 20 * MB);
    }

    private static byte[] zip(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue());
                zos.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static Map<String, byte[]> files(String... nameAndContent) {
        Map<String, byte[]> m = new LinkedHashMap<>();
        for (int i = 0; i < nameAndContent.length; i += 2) {
            m.put(nameAndContent[i], nameAndContent[i + 1].getBytes(StandardCharsets.UTF_8));
        }
        return m;
    }

    private ZipSourceExtractor.Result extract(ZipSourceExtractor x, byte[] zip, Path dest) {
        return x.extract(new ByteArrayInputStream(zip), dest);
    }

    private void assertEmptyDir(Path dir) throws IOException {
        try (var s = Files.list(dir)) {
            assertFalse(s.findAny().isPresent(), "thư mục đích phải được dọn sạch khi lỗi");
        }
    }

    // ------------------------------------------------------------------ giải nén
    // bình thường

    @Test
    void extractsFilesAndNestedFolders(@TempDir Path tmp) throws Exception {
        Path dest = tmp.resolve("src");
        byte[] z = zip(files("Dockerfile", "FROM scratch\n", "app/main.py", "print(1)\n", "app/util/helper.py", "x\n"));
        ZipSourceExtractor.Result r = extract(extractor(), z, dest);

        assertEquals(3, r.fileCount());
        assertEquals(dest, r.directory());
        assertEquals("FROM scratch\n", Files.readString(dest.resolve("Dockerfile")));
        assertEquals("print(1)\n", Files.readString(dest.resolve("app/main.py")));
        assertEquals("x\n", Files.readString(dest.resolve("app/util/helper.py")));
        assertEquals("FROM scratch\n".length() + "print(1)\n".length() + "x\n".length(), r.totalBytes());
    }

    @Test
    void stripsSingleTopLevelFolderLikeGithubZips(@TempDir Path tmp) throws Exception {
        Path dest = tmp.resolve("src");
        byte[] z = zip(files("repo-main/", "", "repo-main/Dockerfile", "FROM scratch\n", "repo-main/src/a.txt", "a\n"));
        extract(extractor(), z, dest);
        assertTrue(Files.exists(dest.resolve("Dockerfile")));
        assertTrue(Files.exists(dest.resolve("src/a.txt")));
        assertFalse(Files.exists(dest.resolve("repo-main")));
    }

    @Test
    void stripsRootEvenWhenChildHasSameName(@TempDir Path tmp) throws Exception {
        Path dest = tmp.resolve("src");
        byte[] z = zip(files("app/app/main.py", "inner\n", "app/Dockerfile", "FROM scratch\n"));
        extract(extractor(), z, dest);
        assertEquals("inner\n", Files.readString(dest.resolve("app/main.py")));
        assertTrue(Files.exists(dest.resolve("Dockerfile")));
    }

    @Test
    void keepsLayoutWhenThereAreSeveralTopLevelEntries(@TempDir Path tmp) throws Exception {
        Path dest = tmp.resolve("src");
        extract(extractor(), zip(files("backend/Dockerfile", "a\n", "frontend/Dockerfile", "b\n")), dest);
        assertTrue(Files.exists(dest.resolve("backend/Dockerfile")));
        assertTrue(Files.exists(dest.resolve("frontend/Dockerfile")));
    }

    @Test
    void keepsSingleFileAtRoot(@TempDir Path tmp) throws Exception {
        Path dest = tmp.resolve("src");
        extract(extractor(), zip(files("Dockerfile", "FROM scratch\n")), dest);
        assertTrue(Files.isRegularFile(dest.resolve("Dockerfile")));
    }

    @Test
    void ignoresMacAndWindowsJunk(@TempDir Path tmp) throws Exception {
        Path dest = tmp.resolve("src");
        byte[] z = zip(files("proj/Dockerfile", "FROM scratch\n", "__MACOSX/proj/._Dockerfile", "junk",
                "proj/.DS_Store", "junk", "proj/sub/Thumbs.db", "junk"));
        ZipSourceExtractor.Result r = extract(extractor(), z, dest);
        assertEquals(1, r.fileCount());
        assertTrue(Files.exists(dest.resolve("Dockerfile")));
        assertFalse(Files.exists(dest.resolve("__MACOSX")));
        assertFalse(Files.exists(dest.resolve(".DS_Store")));
    }

    @Test
    void acceptsBackslashSeparatorsFromWindowsTools(@TempDir Path tmp) throws Exception {
        Path dest = tmp.resolve("src");
        byte[] z = zip(files("src\\", "", "src\\main.py", "x\n", "Dockerfile", "FROM scratch\n"));
        extract(extractor(), z, dest);
        assertEquals("x\n", Files.readString(dest.resolve("src/main.py")));
    }

    // ------------------------------------------------------------------ tấn công
    // đường dẫn

    @Test
    void rejectsZipSlipAndAbsolutePaths(@TempDir Path tmp) throws Exception {
        String[] evil = { "../evil.txt", "a/../../evil.txt", "a/b/../../../evil.txt", "..\\evil.txt",
                "/tmp/evil-abs.txt", "C:\\evil.txt", "C:/evil.txt", "ok/../../evil.txt" };
        for (String name : evil) {
            Path dest = tmp.resolve("dest-" + Math.abs(name.hashCode()));
            byte[] z = zip(files("Dockerfile", "FROM scratch\n", name, "pwned\n"));
            assertThrows(SourceFetchException.class, () -> extract(extractor(), z, dest), "phải chặn: " + name);
            assertEmptyDir(dest);
        }
        assertFalse(Files.exists(tmp.resolve("evil.txt")), "không được ghi ra ngoài thư mục đích");
        assertFalse(Files.exists(Path.of("/tmp/evil-abs.txt")), "không được ghi vào đường dẫn tuyệt đối");
    }

    @Test
    void rejectsNulInName(@TempDir Path tmp) throws Exception {
        Path dest = tmp.resolve("src");
        byte[] z = zip(files("Dockerfile", "x", "bad\0name.txt", "x"));
        assertThrows(SourceFetchException.class, () -> extract(extractor(), z, dest));
        assertEmptyDir(dest);
    }

    @Test
    void rejectsNamesThatDifferOnlyByCase(@TempDir Path tmp) throws Exception {
        // trên ổ đĩa không phân biệt hoa thường (Windows, macOS) hai file này đè lên
        // nhau nên phải chặn
        byte[] z = zip(files("Dockerfile", "1", "DOCKERFILE", "2"));
        Path dest = tmp.resolve("src");
        assertThrows(SourceFetchException.class, () -> extract(extractor(), z, dest));
        assertEmptyDir(dest);
    }

    @Test
    void rejectsFileAndFolderWithSameName(@TempDir Path tmp) throws Exception {
        Path dest = tmp.resolve("src");
        byte[] z = zip(files("a", "i am a file", "a/b.txt", "x"));
        assertThrows(SourceFetchException.class, () -> extract(extractor(), z, dest));
        assertEmptyDir(dest);
    }

    @Test
    void rejectsTooDeepNesting(@TempDir Path tmp) throws Exception {
        Path dest = tmp.resolve("src");
        byte[] z = zip(files("Dockerfile", "x", "d/".repeat(60) + "f.txt", "x"));
        assertThrows(SourceFetchException.class, () -> extract(extractor(), z, dest));
    }

    // ------------------------------------------------------------------ giới hạn
    // (zip bomb)

    @Test
    void rejectsSingleFileThatInflatesTooLarge(@TempDir Path tmp) throws Exception {
        Path dest = tmp.resolve("src");
        byte[] bomb = zip(Map.of("big.bin", new byte[(int) (8 * MB)])); // nén xuống vài KB
        assertTrue(bomb.length < 100_000, "zip bomb phải nhỏ để bài test có ý nghĩa, thực tế " + bomb.length);
        SourceFetchException e = assertThrows(SourceFetchException.class, () -> extract(extractor(), bomb, dest));
        assertTrue(e.getMessage().contains("quá lớn"), e.getMessage());
        assertEmptyDir(dest);
    }

    @Test
    void rejectsWhenTotalUnpackedSizeExceedsLimit(@TempDir Path tmp) throws Exception {
        Path dest = tmp.resolve("src");
        Map<String, byte[]> m = new LinkedHashMap<>();
        for (int i = 0; i < 5; i++) {
            m.put("f" + i + ".bin", new byte[(int) (4 * MB)]); // mỗi file hợp lệ, tổng 20MB+ thì vượt
        }
        ZipSourceExtractor x = new ZipSourceExtractor(1000, 5 * MB, 10 * MB);
        SourceFetchException e = assertThrows(SourceFetchException.class, () -> extract(x, zip(m), dest));
        assertTrue(e.getMessage().contains("quá lớn"), e.getMessage());
        assertEmptyDir(dest);
    }

    @Test
    void rejectsTooManyEntries(@TempDir Path tmp) throws Exception {
        Path dest = tmp.resolve("src");
        Map<String, byte[]> m = new LinkedHashMap<>();
        for (int i = 0; i < 20; i++) {
            m.put("f" + i + ".txt", new byte[] { 1 });
        }
        ZipSourceExtractor x = new ZipSourceExtractor(10, 5 * MB, 20 * MB);
        SourceFetchException e = assertThrows(SourceFetchException.class, () -> extract(x, zip(m), dest));
        assertTrue(e.getMessage().contains("quá nhiều"), e.getMessage());
        assertEmptyDir(dest);
    }

    // ------------------------------------------------------------------ dữ liệu
    // hỏng

    @Test
    void rejectsEmptyZip(@TempDir Path tmp) throws Exception {
        Path dest = tmp.resolve("src");
        assertThrows(SourceFetchException.class, () -> extract(extractor(), zip(Map.of()), dest));
    }

    @Test
    void rejectsZipWithOnlyFolders(@TempDir Path tmp) throws Exception {
        Path dest = tmp.resolve("src");
        assertThrows(SourceFetchException.class, () -> extract(extractor(), zip(files("a/", "", "a/b/", "")), dest));
    }

    @Test
    void rejectsNonZipData(@TempDir Path tmp) {
        Path dest = tmp.resolve("src");
        byte[] garbage = "đây không phải file zip, chỉ là chữ".getBytes(StandardCharsets.UTF_8);
        assertThrows(SourceFetchException.class, () -> extract(extractor(), garbage, dest));
    }

    @Test
    void rejectsTruncatedZip(@TempDir Path tmp) throws Exception {
        Path dest = tmp.resolve("src");
        byte[] random = new byte[200_000]; // dữ liệu ngẫu nhiên không nén được, nên nửa zip chắc chắn cắt giữa dữ liệu
        new java.util.Random(1).nextBytes(random);
        byte[] full = zip(Map.of("data.bin", random));
        byte[] cut = java.util.Arrays.copyOf(full, full.length / 2);
        assertThrows(SourceFetchException.class, () -> extract(extractor(), cut, dest));
        assertEmptyDir(dest);
    }

    @Test
    void refusesNonEmptyDestinationAndKeepsExistingFiles(@TempDir Path tmp) throws Exception {
        Path dest = tmp.resolve("src");
        Files.createDirectories(dest);
        Files.writeString(dest.resolve("old.txt"), "keep me");
        assertThrows(IllegalArgumentException.class,
                () -> extract(extractor(), zip(files("Dockerfile", "x")), dest));
        assertEquals("keep me", Files.readString(dest.resolve("old.txt")));
    }
}