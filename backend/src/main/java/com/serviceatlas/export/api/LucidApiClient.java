package com.serviceatlas.export.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.serviceatlas.api.ApiException;
import com.serviceatlas.config.ServiceAtlasProperties;
import com.serviceatlas.export.importformat.LucidPackager;
import com.serviceatlas.persistence.LucidCredentialsEntity;
import com.serviceatlas.persistence.LucidCredentialsRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Optional Lucid REST API export (FR-6.2).
 *
 * <p>Secondary to the {@code .lucid} file download by design (ADR-003): it needs a registered Lucid
 * application and an OAuth-connected account, which most users will not have. When it is not
 * configured, every entry point here refuses with a {@code 409} carrying an explanation, and the UI
 * hides the option entirely.
 *
 * <p>It posts the <em>same</em> payload the file export produces, so the two paths cannot drift.
 */
@Component
public class LucidApiClient {

    private static final Logger log = LoggerFactory.getLogger(LucidApiClient.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final ServiceAtlasProperties properties;
    private final LucidCredentialsRepository credentials;
    private final LucidTokenCipher cipher;
    private final ObjectMapper objectMapper;
    private final HttpClient http;

    public LucidApiClient(ServiceAtlasProperties properties, LucidCredentialsRepository credentials,
                          LucidTokenCipher cipher, ObjectMapper objectMapper) {
        this.properties = properties;
        this.credentials = credentials;
        this.cipher = cipher;
        this.objectMapper = objectMapper;
        this.http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    }

    /** Whether the API path is usable at all — drives whether the UI shows it (FR-6.2). */
    public boolean isConfigured() {
        return properties.getLucid().isConfigured();
    }

    public boolean isConnected(Long workspaceId) {
        return isConfigured() && credentials.findById(workspaceId).isPresent();
    }

    /** Stores an access token for a workspace, encrypted (Q-3). */
    public void connect(Long workspaceId, String tokenJson) {
        requireConfigured();
        String encrypted = cipher.encrypt(tokenJson);
        credentials.findById(workspaceId).ifPresentOrElse(
                existing -> {
                    existing.setEncryptedTokens(encrypted);
                    credentials.save(existing);
                },
                () -> credentials.save(new LucidCredentialsEntity(workspaceId, encrypted)));
        log.info("Connected a Lucid account for workspace {}", workspaceId);
    }

    public void disconnect(Long workspaceId) {
        credentials.findById(workspaceId).ifPresent(credentials::delete);
    }

    /**
     * Creates a Lucidchart document from an already-built Standard Import payload.
     *
     * @param documentJson the exact bytes {@link LucidPackager#toDocumentJson} produced
     * @return the created document's id and edit URL
     */
    public CreatedDocument createDocument(Long workspaceId, byte[] documentJson, String title,
                                          String product) {
        requireConfigured();
        String token = accessToken(workspaceId);

        String boundary = "service-atlas-" + java.util.UUID.randomUUID();
        byte[] body = multipartBody(boundary, documentJson, title, product);

        HttpRequest request = HttpRequest.newBuilder(URI.create(properties.getLucid().getBaseUrl() + "/documents"))
                .timeout(TIMEOUT)
                .header("Authorization", "Bearer " + token)
                .header("Lucid-Api-Version", "1")
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw ApiException.conflict(
                        "Lucid rejected the document (HTTP " + response.statusCode() + "): "
                                + truncate(response.body()));
            }
            JsonNode node = objectMapper.readTree(response.body());
            return new CreatedDocument(
                    node.path("documentId").asText(null),
                    node.path("editUrl").asText(null),
                    node.path("title").asText(title));
        } catch (java.io.IOException e) {
            throw ApiException.conflict("Could not reach the Lucid API: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ApiException.conflict("The Lucid API call was interrupted");
        }
    }

    /**
     * Builds the multipart body Lucid's import endpoint expects: the Standard Import payload as a
     * file part typed with Lucid's own content type, plus the product and title fields.
     */
    private byte[] multipartBody(String boundary, byte[] documentJson, String title, String product) {
        java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        try {
            body.write(part(boundary, "product", product == null ? "lucidchart" : product));
            body.write(part(boundary, "title", title == null ? "Service Atlas" : title));

            body.write(("--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"file\"; filename=\"document.json\"\r\n"
                    + "Content-Type: " + LucidPackager.LUCID_CONTENT_TYPE + "\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            body.write(documentJson);
            body.write("\r\n".getBytes(StandardCharsets.UTF_8));
            body.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Could not assemble the Lucid request body", e);
        }
        return body.toByteArray();
    }

    private byte[] part(String boundary, String name, String value) {
        return ("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"
                + value + "\r\n").getBytes(StandardCharsets.UTF_8);
    }

    private String accessToken(Long workspaceId) {
        Optional<LucidCredentialsEntity> stored = credentials.findById(workspaceId);
        if (stored.isEmpty()) {
            throw ApiException.notConfigured(
                    "No Lucid account is connected for this workspace. Connect one, or download the "
                            + ".lucid file and import it yourself.");
        }
        String tokenJson = cipher.decrypt(stored.get().getEncryptedTokens());
        try {
            JsonNode node = objectMapper.readTree(tokenJson);
            String token = node.path("access_token").asText(null);
            if (token == null || token.isBlank()) {
                throw ApiException.notConfigured("The stored Lucid token has no access_token");
            }
            return token;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw ApiException.notConfigured("The stored Lucid token could not be read");
        }
    }

    private void requireConfigured() {
        if (!isConfigured()) {
            throw ApiException.notConfigured(
                    "Lucid API export is not configured on this server. Set LUCID_API_ENABLED, "
                            + "LUCID_CLIENT_ID and LUCID_CLIENT_SECRET, or use the .lucid file download instead.");
        }
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 400 ? value : value.substring(0, 400) + "…";
    }

    public record CreatedDocument(String documentId, String editUrl, String title) {
    }
}
