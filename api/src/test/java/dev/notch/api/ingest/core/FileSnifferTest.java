package dev.notch.api.ingest.core;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class FileSnifferTest {

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] zip(String name, String content) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bytes)) {
            z.putNextEntry(new ZipEntry(name));
            z.write(utf8(content));
            z.closeEntry();
        }
        return bytes.toByteArray();
    }

    @Test
    void detectsJsonEvenWithWhitespaceAndBom() {
        assertEquals(FileSniffer.Container.JSON, FileSniffer.sniff(utf8("  \n[{}]")));
        assertEquals(FileSniffer.Container.JSON, FileSniffer.sniff(utf8("\uFEFF{\"a\":1}")));
    }

    @Test
    void detectsTextAndEmpty() {  // boundary
        assertEquals(FileSniffer.Container.TEXT, FileSniffer.sniff(utf8("# Notes")));
        assertEquals(FileSniffer.Container.TEXT, FileSniffer.sniff(new byte[0]));
    }

    @Test
    void extractsConversationsJsonFromExportZip() throws Exception {
        byte[] z = zip("export/conversations.json", "[]");
        assertEquals(FileSniffer.Container.ZIP, FileSniffer.sniff(z));
        assertEquals("[]", new String(FileSniffer.extractConversationsJson(z), StandardCharsets.UTF_8));
    }

    @Test
    void zipWithoutConversationsJsonIsRejected() throws Exception {
        byte[] z = zip("chat.html", "<html>");
        assertThrows(IllegalArgumentException.class, () -> FileSniffer.extractConversationsJson(z));
    }

    @Test
    void decodeTextRejectsBinaryAndInvalidUtf8() {
        assertThrows(IllegalArgumentException.class, () -> FileSniffer.decodeText(new byte[] {(byte) 0xC3, 0x28}));
        assertThrows(IllegalArgumentException.class, () -> FileSniffer.decodeText(new byte[] {'a', 0, 'b'}));
        assertEquals("hi", FileSniffer.decodeText(utf8("\uFEFFhi")));
    }

    @Test
    void aZipOfCodeIsRecognisedAsCode() throws Exception {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bytes)) {
            for (String name : new String[] {"app/src/a.ts", "app/src/b.tsx", "app/lib/c.py"}) {
                z.putNextEntry(new java.util.zip.ZipEntry(name));
                z.write("x".getBytes());
                z.closeEntry();
            }
        }
        assertThrows(FileSniffer.CodeArchiveException.class, () -> FileSniffer.extractConversationsJson(bytes.toByteArray()));
    }
}
