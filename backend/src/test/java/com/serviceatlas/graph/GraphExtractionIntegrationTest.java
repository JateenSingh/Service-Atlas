package com.serviceatlas.graph;

import static org.assertj.core.api.Assertions.assertThat;

import com.serviceatlas.graph.model.Confidence;
import com.serviceatlas.graph.model.DependencyGraph;
import com.serviceatlas.graph.model.EdgeType;
import com.serviceatlas.graph.model.Evidence;
import com.serviceatlas.graph.model.GraphEdge;
import com.serviceatlas.graph.model.GraphNode;
import com.serviceatlas.graph.model.NodeType;
import com.serviceatlas.graph.model.SignalSource;
import com.serviceatlas.persistence.WorkspaceEntity;
import com.serviceatlas.scan.ScanService;
import com.serviceatlas.testsupport.Fixtures;
import com.serviceatlas.workspace.WorkspaceService;
import com.serviceatlas.workspace.WorkspaceSettings;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * M2 acceptance: a full scan of the fixture set exercises every signal source in FR-3 and produces
 * the complete graph — services, sub-modules, topics, external services, and typed edges carrying
 * confidence and evidence.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:extraction-it;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GraphExtractionIntegrationTest {

    @Autowired
    private WorkspaceService workspaces;
    @Autowired
    private ScanService scans;
    @Autowired
    private GraphService graphs;

    private DependencyGraph graph;

    @BeforeAll
    void scanOnce() {
        WorkspaceEntity workspace = workspaces.create(
                "extraction-" + System.nanoTime(), Fixtures.root().toString(), WorkspaceSettings.defaults());
        scans.scanNow(workspace.getId());
        graph = graphs.forWorkspace(workspace.getId()).graph();
    }

    @Test
    @DisplayName("FR-3.2: config-declared service URLs become HIGH-confidence HTTP edges")
    void configReferencesBecomeHttpEdges() {
        assertThat(edge("logordersvc", "loginventorysvc", EdgeType.HTTP)).isPresent()
                .hasValueSatisfying(edge -> {
                    assertThat(edge.confidence()).isEqualTo(Confidence.HIGH);
                    assertThat(sourcesOf(edge)).contains(SignalSource.CONFIG_REFERENCE);
                });
        assertThat(edge("logordersvc", "logtrackingsvc", EdgeType.HTTP)).isPresent();
        assertThat(edge("logordersvc", "logshipmentsvc", EdgeType.HTTP)).isPresent();
    }

    @Test
    @DisplayName("FR-3.3: HTTP client calls in source become MEDIUM-confidence edges")
    void clientCallsBecomeMediumEdges() {
        assertThat(edge("logordersvc", "lognotificationsvc", EdgeType.HTTP)).isPresent()
                .hasValueSatisfying(edge -> {
                    assertThat(edge.confidence()).isEqualTo(Confidence.MEDIUM);
                    assertThat(sourcesOf(edge)).contains(SignalSource.HTTP_CLIENT_CALL);
                    assertThat(edge.evidence()).allSatisfy(evidence -> {
                        assertThat(evidence.file()).endsWith(".scala");
                        assertThat(evidence.line()).isPositive();
                    });
                });
        assertThat(edge("logordersvc", "logauditsvc", EdgeType.HTTP))
                .as("target assembled from a local val").isPresent();
    }

    @Test
    @DisplayName("FR-3.4: routes are catalogued and used to label matching HTTP edges")
    void routesAreCataloguedAndLabelEdges() {
        GraphNode order = node("logordersvc");
        assertThat(order.endpoints()).extracting(com.serviceatlas.graph.model.Endpoint::signature)
                .contains("GET /orders", "POST /orders", "GET /orders/:id");

        assertThat(edge("logordersvc", "logauditsvc", EdgeType.HTTP))
                .hasValueSatisfying(edge -> assertThat(edge.label()).isEqualTo("POST /audit/events"));
    }

    @Test
    @DisplayName("FR-3.5: a cross-service import corroborates the artifact edge it belongs to")
    void importsMergeIntoArtifactEdges() {
        GraphEdge quote = edge("logordersvc", "logquotesvc", EdgeType.ARTIFACT).orElseThrow();

        assertThat(sourcesOf(quote))
                .contains(SignalSource.BUILD_DEPENDENCY, SignalSource.SOURCE_IMPORT);
        assertThat(quote.confidence())
                .as("a LOW import must not weaken a HIGH build dependency")
                .isEqualTo(Confidence.HIGH);
    }

    @Test
    @DisplayName("FR-3.6: messaging flows through a topic node, producer to consumer")
    void messagingFlowsThroughTopicNodes() {
        GraphNode topic = node("topic:orderevents");
        assertThat(topic.type()).isEqualTo(NodeType.TOPIC);
        assertThat(topic.displayName()).isEqualTo("order-events");

        assertThat(edge("logordersvc", "topic:orderevents", EdgeType.MESSAGING)).isPresent();
        assertThat(edge("topic:orderevents", "loginventorysvc", EdgeType.MESSAGING)).isPresent();
        assertThat(edge("topic:orderevents", "logauditsvc", EdgeType.MESSAGING)).isPresent();
        assertThat(edge("logordersvc", "logauditsvc", EdgeType.MESSAGING))
                .as("no direct producer-to-consumer shortcut").isEmpty();
    }

    @Test
    @DisplayName("FR-3.9: a configured database becomes a DATASTORE node behind a PERSISTENCE edge")
    void datastoresBecomeTheirOwnNodes() {
        GraphNode orders = node("datastore:postgresql:orders");
        assertThat(orders.type()).isEqualTo(NodeType.DATASTORE);
        assertThat(orders.displayName()).isEqualTo("orders");
        assertThat(orders.metadata()).containsEntry("engine", "PostgreSQL")
                .containsEntry("host", "orders-db.logistics");

        assertThat(edge("logordersvc", "datastore:postgresql:orders", EdgeType.PERSISTENCE))
                .isPresent()
                .hasValueSatisfying(edge -> {
                    assertThat(edge.confidence()).isEqualTo(Confidence.HIGH);
                    assertThat(edge.label()).isEqualTo("PostgreSQL");
                    assertThat(sourcesOf(edge))
                            .contains(SignalSource.DATASTORE_CONNECTION, SignalSource.DATASTORE_SCHEMA);
                });
    }

    @Test
    @DisplayName("A cache both services configure is one node, so the coupling is visible")
    void sharedDatastoresAreDrawnOnce() {
        assertThat(graph.nodes())
                .filteredOn(node -> node.type() == NodeType.DATASTORE
                        && "Redis".equals(node.metadata().get("engine")))
                .hasSize(1);

        String cache = "datastore:redis:pricingcachelogistics";
        assertThat(edge("logordersvc", cache, EdgeType.PERSISTENCE)).isPresent();
        assertThat(edge("logquotesvc", cache, EdgeType.PERSISTENCE)).isPresent();
    }

    @Test
    @DisplayName("Non-relational stores are drawn too, each named by what it holds")
    void everyEngineIsRepresented() {
        assertThat(graph.nodes())
                .filteredOn(node -> node.type() == NodeType.DATASTORE)
                .extracting(GraphNode::displayName)
                .contains("orders", "quotes", "inventory", "audit", "pricing-cache.logistics");
    }

    @Test
    @DisplayName("An embedded or localhost database is development detail, not architecture")
    void localOnlyStoresAreNotDrawn() {
        assertThat(graph.nodes())
                .filteredOn(node -> node.type() == NodeType.DATASTORE)
                .allSatisfy(node -> assertThat(node.metadata()).doesNotContainKey("localOnly"));
    }

    @Test
    @DisplayName("FR-3.10: Pub/Sub publishers and subscribers meet at one topic node")
    void pubSubFlowsThroughATopicNode() {
        GraphNode topic = node("topic:ordereventsv2");
        assertThat(topic.type()).isEqualTo(NodeType.TOPIC);
        assertThat(topic.metadata()).containsEntry("broker", "Google Pub/Sub");

        assertThat(edge("logordersvc", "topic:ordereventsv2", EdgeType.MESSAGING)).isPresent()
                .hasValueSatisfying(edge ->
                        assertThat(sourcesOf(edge)).contains(SignalSource.PUBSUB_PUBLISHER));
        assertThat(edge("topic:ordereventsv2", "loginventorysvc", EdgeType.MESSAGING)).isPresent();
        assertThat(edge("topic:ordereventsv2", "logauditsvc", EdgeType.MESSAGING)).isPresent();
    }

    @Test
    @DisplayName("A consumer's copy of the topic name does not reverse the arrow")
    void subscribersDoNotPublish() {
        assertThat(edge("loginventorysvc", "topic:ordereventsv2", EdgeType.MESSAGING))
                .as("log-inventory-svc only subscribes").isEmpty();
        assertThat(edge("logauditsvc", "topic:ordereventsv2", EdgeType.MESSAGING))
                .as("log-audit-svc only subscribes").isEmpty();
    }

    @Test
    @DisplayName("A subscription is not a node; the topic it reads is")
    void subscriptionsAreNotTopics() {
        assertThat(keys()).noneMatch(key -> key.contains("sub") && key.startsWith("topic:"));
    }

    @Test
    @DisplayName("FR-4.1: services referenced but not cloned become EXTERNAL nodes")
    void unclonedServicesBecomeExternalNodes() {
        assertThat(graph.nodes())
                .filteredOn(node -> node.type() == NodeType.EXTERNAL)
                .extracting(GraphNode::displayName)
                .contains("log-template-svc", "log-tariff-svc", "api.stripe.com");
    }

    @Test
    @DisplayName("A third-party domain keeps its full host rather than collapsing to 'api'")
    void thirdPartyDomainsKeepTheirIdentity() {
        assertThat(edge("logordersvc", "external:apistripecom", EdgeType.HTTP)).isPresent();
    }

    @Test
    @DisplayName("Localhost and third-party libraries never become nodes")
    void noiseIsExcluded() {
        assertThat(graph.nodes()).extracting(GraphNode::displayName)
                .doesNotContain("localhost", "cats-core", "scalatest", "kafka-broker");
    }

    @Test
    @DisplayName("FR-4.2: every edge carries a type, a confidence and at least one piece of evidence")
    void everyEdgeIsAttributable() {
        assertThat(graph.edges()).isNotEmpty().allSatisfy(edge -> {
            assertThat(edge.type()).isNotNull();
            assertThat(edge.confidence()).isNotNull();
            assertThat(edge.evidence()).isNotEmpty();
            assertThat(edge.sourceKey()).isNotEqualTo(edge.targetKey());
        });
    }

    @Test
    @DisplayName("Evidence survives the round trip through persistence")
    void evidenceIsPersisted() {
        Evidence evidence = edge("logordersvc", "loginventorysvc", EdgeType.HTTP)
                .orElseThrow().evidence().get(0);

        assertThat(evidence.source()).isNotNull();
        assertThat(evidence.file()).isNotBlank();
        assertThat(evidence.snippet()).isNotBlank();
        assertThat(evidence.detail()).isNotBlank();
    }

    private GraphNode node(String key) {
        return graph.node(key).orElseThrow(() -> new AssertionError("no node " + key + " in " + keys()));
    }

    private List<String> keys() {
        return graph.nodes().stream().map(GraphNode::key).toList();
    }

    private Optional<GraphEdge> edge(String source, String target, EdgeType type) {
        return graph.edges().stream()
                .filter(edge -> edge.sourceKey().equals(source)
                        && edge.targetKey().equals(target)
                        && edge.type() == type)
                .findFirst();
    }

    private List<SignalSource> sourcesOf(GraphEdge edge) {
        return edge.evidence().stream().map(Evidence::source).toList();
    }
}
