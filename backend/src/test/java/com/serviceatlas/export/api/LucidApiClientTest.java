package com.serviceatlas.export.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.serviceatlas.api.ApiException;
import com.serviceatlas.config.ServiceAtlasProperties;
import com.serviceatlas.persistence.LucidCredentialsEntity;
import com.serviceatlas.persistence.LucidCredentialsRepository;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * FR-6.2 — the optional Lucid REST API export.
 *
 * <p>Driven against a real (tiny) HTTP server rather than a mocked client, so the assertions are
 * about what Lucid would actually receive: a bearer token, a multipart body, and the Standard
 * Import payload typed with Lucid's own content type.
 */
class LucidApiClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ServiceAtlasProperties properties;
    private StubCredentialsRepository credentials;
    private LucidTokenCipher cipher;
    private HttpServer server;
    private final AtomicReference<RecordedRequest> received = new AtomicReference<>();

    @BeforeEach
    void setUp() throws Exception {
        properties = new ServiceAtlasProperties();
        properties.getLucid().setTokenEncryptionKey("test-key");
        credentials = new StubCredentialsRepository();
        cipher = new LucidTokenCipher(properties);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private LucidApiClient client() {
        return new LucidApiClient(properties, credentials, cipher, objectMapper);
    }

    private void startLucidStub(int status, String body) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/documents", exchange -> respond(exchange, status, body));
        server.start();
        properties.getLucid().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private void respond(HttpExchange exchange, int status, String body) throws java.io.IOException {
        byte[] requestBody = exchange.getRequestBody().readAllBytes();
        received.set(new RecordedRequest(
                exchange.getRequestHeaders().getFirst("Authorization"),
                exchange.getRequestHeaders().getFirst("Content-Type"),
                new String(requestBody, StandardCharsets.UTF_8)));

        byte[] response = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private void configureAndConnect() {
        properties.getLucid().setEnabled(true);
        properties.getLucid().setClientId("client");
        properties.getLucid().setClientSecret("secret");
        client().connect(1L, "{\"access_token\":\"token-abc\"}");
    }

    @Test
    @DisplayName("FR-6.2: the API path is unavailable until credentials are configured")
    void unconfiguredByDefault() {
        LucidApiClient client = client();

        assertThat(client.isConfigured()).isFalse();
        assertThat(client.isConnected(1L)).isFalse();
        assertThatThrownBy(() -> client.createDocument(1L, "{}".getBytes(), "t", "lucidchart"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("not configured");
    }

    @Test
    @DisplayName("Configured but not connected is refused with an actionable message")
    void configuredButNotConnected() {
        properties.getLucid().setEnabled(true);
        properties.getLucid().setClientId("client");
        properties.getLucid().setClientSecret("secret");

        assertThatThrownBy(() -> client().createDocument(1L, "{}".getBytes(), "t", "lucidchart"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("No Lucid account is connected")
                .hasMessageContaining(".lucid file");
    }

    @Test
    @DisplayName("Q-3: stored tokens are encrypted, never plaintext")
    void tokensAreStoredEncrypted() {
        configureAndConnect();

        String stored = credentials.findById(1L).orElseThrow().getEncryptedTokens();
        assertThat(stored).doesNotContain("token-abc");
        assertThat(cipher.decrypt(stored)).contains("token-abc");
        assertThat(client().isConnected(1L)).isTrue();
    }

    @Test
    @DisplayName("The document is posted as multipart with the Standard Import content type")
    void postsStandardImportPayload() throws Exception {
        startLucidStub(200, """
                {"documentId":"doc-1","editUrl":"https://lucid.app/documents/doc-1/edit","title":"Logistics"}
                """);
        configureAndConnect();

        LucidApiClient.CreatedDocument created = client().createDocument(
                1L, "{\"version\":1,\"pages\":[]}".getBytes(StandardCharsets.UTF_8), "Logistics", "lucidchart");

        assertThat(created.documentId()).isEqualTo("doc-1");
        assertThat(created.editUrl()).isEqualTo("https://lucid.app/documents/doc-1/edit");

        RecordedRequest request = received.get();
        assertThat(request.authorization()).isEqualTo("Bearer token-abc");
        assertThat(request.contentType()).startsWith("multipart/form-data; boundary=");
        assertThat(request.body())
                .contains("name=\"product\"")
                .contains("lucidchart")
                .contains("name=\"title\"")
                .contains("Logistics")
                .contains("filename=\"document.json\"")
                .contains("x-application/vnd.lucid.standardImport")
                .contains("\"version\":1");
    }

    @Test
    @DisplayName("A rejection from Lucid is surfaced with its own explanation")
    void surfacesLucidErrors() throws Exception {
        startLucidStub(422, "{\"error\":\"shape 3 has no boundingBox\"}");
        configureAndConnect();

        assertThatThrownBy(() -> client().createDocument(1L, "{}".getBytes(), "t", "lucidchart"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("422")
                .hasMessageContaining("boundingBox");
    }

    @Test
    @DisplayName("An unreachable Lucid API fails with a message, not a stack trace")
    void handlesUnreachableApi() {
        properties.getLucid().setEnabled(true);
        properties.getLucid().setClientId("client");
        properties.getLucid().setClientSecret("secret");
        properties.getLucid().setBaseUrl("http://127.0.0.1:1");
        client().connect(1L, "{\"access_token\":\"token\"}");

        assertThatThrownBy(() -> client().createDocument(1L, "{}".getBytes(), "t", "lucidchart"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Could not reach the Lucid API");
    }

    @Test
    @DisplayName("Disconnecting removes the stored credentials")
    void disconnects() {
        configureAndConnect();
        client().disconnect(1L);

        assertThat(credentials.findById(1L)).isEmpty();
        assertThat(client().isConnected(1L)).isFalse();
    }

    @Test
    @DisplayName("A stored token without an access_token is reported rather than sent")
    void rejectsMalformedStoredToken() {
        properties.getLucid().setEnabled(true);
        properties.getLucid().setClientId("client");
        properties.getLucid().setClientSecret("secret");
        client().connect(1L, "{\"refresh_token\":\"only\"}");

        assertThatThrownBy(() -> client().createDocument(1L, "{}".getBytes(), "t", "lucidchart"))
                .hasMessageContaining("no access_token");
    }

    private record RecordedRequest(String authorization, String contentType, String body) {
    }

    /** In-memory stand-in; the real repository is Spring Data and needs no test of its own. */
    private static final class StubCredentialsRepository
            extends com.serviceatlas.testsupport.UnsupportedJpaRepository<LucidCredentialsEntity, Long>
            implements LucidCredentialsRepository {

        private final Map<Long, LucidCredentialsEntity> stored = new HashMap<>();

        @Override
        public Optional<LucidCredentialsEntity> findById(Long id) {
            return Optional.ofNullable(stored.get(id));
        }

        @Override
        public <S extends LucidCredentialsEntity> S save(S entity) {
            stored.put(entity.getWorkspaceId(), entity);
            return entity;
        }

        @Override
        public void delete(LucidCredentialsEntity entity) {
            stored.remove(entity.getWorkspaceId());
        }
    }
}
