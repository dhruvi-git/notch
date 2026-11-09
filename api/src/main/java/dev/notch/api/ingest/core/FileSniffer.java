package dev.notch.api.ingest.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Works out what kind of file was uploaded, and safely unpacks export archives. */
public final class FileSniffer {

    /** Uncompressed size cap when reading from a zip, against decompression bombs. */
    static final long MAX_UNZIPPED_BYTES = 64L * 1024 * 1024;

    public enum Container { ZIP, JSON, TEXT }

    private FileSniffer() {
    }

    public static Container sniff(byte[] content) {
        if (content.length >= 4 && content[0] == 'P' && content[1] == 'K'
                && content[2] == 3 && content[3] == 4) {
            return Container.ZIP;
        }
        int i = 0;
        if (content.length >= 3 && (content[0] & 0xff) == 0xEF && (content[1] & 0xff) == 0xBB
                && (content[2] & 0xff) == 0xBF) {
            i = 3;  // UTF-8 byte order mark
        }
        while (i < content.length && Character.isWhitespace(content[i])) i++;
        if (i < content.length && (content[i] == '[' || content[i] == '{')) return Container.JSON;
        return Container.TEXT;
    }

    /** Reads {@code conversations.json} from a ChatGPT or Claude export archive. */
    public static byte[] extractConversationsJson(byte[] zip) {
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                String name = entry.getName();
                if (!entry.isDirectory() && (name.equals("conversations.json")
                        || name.endsWith("/conversations.json"))) {
                    return readLimited(in);
                }
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("the zip file could not be read", e);
        }
        throw new IllegalArgumentException("the zip file has no conversations.json");
    }

    private static byte[] readLimited(ZipInputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        long total = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > MAX_UNZIPPED_BYTES) {
                throw new IllegalArgumentException("conversations.json is larger than 64 MB unzipped");
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    /** Decodes strict UTF-8 text, rejecting binary files. */
    public static String decodeText(byte[] content) {
        try {
            String s = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content)).toString();
            if (s.indexOf('\0') >= 0) throw new IllegalArgumentException("the file looks binary");
            return s.startsWith("\uFEFF") ? s.substring(1) : s;
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("the file is not UTF-8 text", e);
        }
    }
}
