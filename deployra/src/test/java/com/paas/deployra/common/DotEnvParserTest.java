package com.paas.deployra.common;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DotEnvParserTest {

    private static List<String> keys(String text) {
        return List.copyOf(DotEnvParser.parse(text).keys());
    }

    @Test
    void returnsOnlyKeysInOrder() {
        DotEnvParser.Result r = DotEnvParser.parse("DB_HOST=localhost\nDB_PASS=hunter2\nPORT=8080\n");
        assertEquals(List.of("DB_HOST", "DB_PASS", "PORT"), List.copyOf(r.keys()));
        assertTrue(r.skipped().isEmpty());
        assertTrue(!r.toString().contains("hunter2"), "kết quả không được chứa value");
    }

    @Test
    void emptyOrNullInputGivesNoKeys() {
        assertTrue(DotEnvParser.parse(null).keys().isEmpty());
        assertTrue(DotEnvParser.parse("").keys().isEmpty());
    }

    @Test
    void ignoresCommentsBlankLinesAndHandlesExport() {
        assertEquals(List.of("A", "B"), keys("# comment\n\nexport A=1\n  B = 2 # trailing\n"));
    }

    @Test
    void keyWithEmptyValueIsStillAKey() {
        assertEquals(List.of("API_KEY", "TOKEN"), keys("API_KEY=\nTOKEN=\"\"\n"));
    }

    @Test
    void duplicateKeysAppearOnce() {
        assertEquals(List.of("A", "B"), keys("A=1\nB=2\nA=3\n"));
    }

    @Test
    void quotedValuesAreAccepted() {
        assertEquals(List.of("A", "B", "C"),
                keys("A=\"has # hash and = sign\"\nB='single quoted'\nC=\"esc \\\" quote\"\n"));
    }

    @Test
    void linesInsideMultilineValueAreNotMistakenForKeys() {
        String text = "PRIVATE_KEY=\"-----BEGIN KEY-----\nabc=def\nFAKE_KEY=oops\n-----END KEY-----\"\nAFTER=1\n";
        assertEquals(List.of("PRIVATE_KEY", "AFTER"), keys(text));

        String single = "CERT='line1\nINNER=x\nline3'\nNEXT=2\n";
        assertEquals(List.of("CERT", "NEXT"), keys(single));
    }

    @Test
    void invalidLinesAreSkippedWithoutLeakingContent() {
        DotEnvParser.Result r = DotEnvParser.parse(
                "GOOD=1\nnoequalsign\n1BAD=x\nBAD KEY=x\nUNCLOSED=\"secret-value\nGOOD2=2\n");
        // UNCLOSED nuốt phần còn lại của file vì nháy không đóng → không có GOOD2
        assertEquals(List.of("GOOD"), List.copyOf(r.keys()));
        assertEquals(List.of("dòng 2", "dòng 3", "dòng 4", "dòng 5"), r.skipped());
        assertTrue(r.skipped().stream().noneMatch(s -> s.contains("secret-value")));
    }

    @Test
    void trailingCharsAfterClosingQuoteSkipTheLine() {
        DotEnvParser.Result r = DotEnvParser.parse("A=\"x\" junk\nB=\"y\" # ok\n");
        assertEquals(List.of("B"), List.copyOf(r.keys()));
        assertEquals(List.of("dòng 1"), r.skipped());
    }

    @Test
    void windowsLineEndingsWork() {
        assertEquals(List.of("A", "B"), keys("A=1\r\nB=2\r\n"));
    }
}