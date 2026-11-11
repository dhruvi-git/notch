package dev.notch.explain;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Reads a zipped codebase into memory for mapping, without writing anything to disk.
 *
 * <p>Guards against zip bombs by counting the bytes actually decompressed, not the sizes the
 * archive claims. A single folder wrapping everything (as in GitHub's "Download ZIP") is
 * removed from the paths.
 */
public final class ZipSource {

    public static final int MAX_ENTRIES = 20_000;
    public static final long MAX_TOTAL_BYTES = 100L * 1024 * 1024;

    public record Result(List<SourceFile> files, int skipped) {
    }

    public static final class TooLargeException extends IOException {
        public TooLargeException(String message) {
            super(message);
        }
    }

    private ZipSource() {
    }

    public static Result read(byte[] zip) throws IOException {
        List<SourceFile> files = new ArrayList<>();
        int skipped = 0;
        int entries = 0;
        long total = 0;
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                if (++entries > MAX_ENTRIES) throw new TooLargeException("The zip has more than " + MAX_ENTRIES + " entries.");
                if (e.isDirectory()) continue;
                String path = e.getName().replace('\\', '/');
                if (path.startsWith("/") || path.contains("../") || path.startsWith("__MACOSX/")) {
                    skipped++;
                    continue;
                }
                byte[] data = readCapped(in, CodeMapBuilder.MAX_FILE_BYTES + 1);
                total += data.length;
                if (total > MAX_TOTAL_BYTES) throw new TooLargeException("The zip unpacks to more than 100 MB.");
                if (data.length > CodeMapBuilder.MAX_FILE_BYTES) {
                    skipped++;
                    drain(in);
                    continue;
                }
                String text = decode(data);
                if (text == null) {
                    skipped++;
                    continue;
                }
                files.add(new SourceFile(path, text));
            }
        } catch (IllegalArgumentException malformed) {
            throw new IOException("That doesn't look like a valid zip file.", malformed);
        }
        return new Result(stripWrapper(files), skipped);
    }

    /** "repo-main/src/x.ts" → "src/x.ts" when every path shares one top folder. */
    static List<SourceFile> stripWrapper(List<SourceFile> files) {
        if (files.isEmpty()) return files;
        String first = files.get(0).path();
        int slash = first.indexOf('/');
        if (slash < 0) return files;
        String top = first.substring(0, slash + 1);
        for (SourceFile f : files) if (!f.path().startsWith(top)) return files;
        List<SourceFile> out = new ArrayList<>(files.size());
        for (SourceFile f : files) out.add(new SourceFile(f.path().substring(top.length()), f.content()));
        return out;
    }

    private static byte[] readCapped(InputStream in, int cap) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while (out.size() < cap && (n = in.read(buf, 0, Math.min(buf.length, cap - out.size()))) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private static void drain(InputStream in) throws IOException {
        byte[] buf = new byte[8192];
        while (in.read(buf) > 0) {
            // discard the rest of an oversized entry
        }
    }

    /** UTF-8 text, or null for binary data. */
    private static String decode(byte[] data) {
        for (byte b : data) if (b == 0) return null;
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE)
                    .decode(java.nio.ByteBuffer.wrap(data)).toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            return null;
        }
    }
}
