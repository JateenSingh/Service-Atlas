package com.serviceatlas.export.importformat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.stereotype.Component;

/**
 * Packs a {@link LucidDocument} into a {@code .lucid} file (FR-6.1).
 *
 * <p>A {@code .lucid} file is a ZIP whose payload is {@code document.json} at the archive root.
 * Lucid documents a 2 MB limit on that file and 50 MB on the archive; both are checked here so an
 * over-large export fails with an explanation rather than being rejected at import time.
 */
@Component
public class LucidPackager {

    /** Lucid's documented limit for {@code document.json}. */
    static final int MAX_DOCUMENT_BYTES = 2 * 1024 * 1024;

    /** Lucid's documented limit for the archive. */
    static final int MAX_ARCHIVE_BYTES = 50 * 1024 * 1024;

    public static final String DOCUMENT_ENTRY = "document.json";

    /** The content type Lucid's import endpoint expects for this format. */
    public static final String LUCID_CONTENT_TYPE = "x-application/vnd.lucid.standardImport";

    private final ObjectMapper objectMapper;

    public LucidPackager(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public byte[] toDocumentJson(LucidDocument document) {
        try {
            byte[] json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(document);
            if (json.length > MAX_DOCUMENT_BYTES) {
                throw new IllegalStateException(
                        "This diagram is too large for Lucid Standard Import (document.json is "
                                + (json.length / 1024) + " KB, the limit is 2 MB). Filter the view down and export again.");
            }
            return json;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise the Lucid document", e);
        }
    }

    public byte[] pack(LucidDocument document) {
        byte[] json = toDocumentJson(document);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer, StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry(DOCUMENT_ENTRY));
            zip.write(json);
            zip.closeEntry();
        } catch (IOException e) {
            throw new IllegalStateException("Could not write the .lucid archive", e);
        }
        byte[] archive = buffer.toByteArray();
        if (archive.length > MAX_ARCHIVE_BYTES) {
            throw new IllegalStateException("The .lucid archive exceeds Lucid's 50 MB limit");
        }
        return archive;
    }
}
