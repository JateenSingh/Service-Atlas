package com.serviceatlas.parser.scala;

import static org.assertj.core.api.Assertions.assertThat;

import com.serviceatlas.graph.model.Confidence;
import com.serviceatlas.graph.model.SignalSource;
import com.serviceatlas.parser.DatastoreRef;
import com.serviceatlas.parser.DependencySignal;
import com.serviceatlas.parser.common.RepoFiles;
import com.serviceatlas.testsupport.Fixtures;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** FR-3.9 — the databases and caches a service talks to. */
class DatastoreScannerTest {

    private final DatastoreScanner scanner = new DatastoreScanner();

    private List<DependencySignal> scan(Path repo, String nodeKey) {
        RepoFiles files = new RepoFiles(repo, Fixtures.scanSettings());
        SbtBuild build = new SbtBuildParser(files).parse(repo.getFileName().toString());
        return scanner.scan(new ScalaScanContext(nodeKey, files, build, Fixtures.scanSettings()));
    }

    private Path repo(Path temp, String name, String config) throws IOException {
        return repo(temp, name, config, "name := \"" + name + "\"");
    }

    private Path repo(Path temp, String name, String config, String buildSbt) throws IOException {
        Path repo = temp.resolve(name);
        Files.createDirectories(repo.resolve("conf"));
        Files.writeString(repo.resolve("build.sbt"), buildSbt);
        Files.writeString(repo.resolve("conf/application.conf"), config);
        return repo;
    }

    @Test
    @DisplayName("A JDBC URL in config becomes a HIGH-confidence persistence signal")
    void readsConnectionStrings() {
        List<DependencySignal> signals = scan(Fixtures.repo("log-order-svc"), "logordersvc");

        assertThat(signals)
                .filteredOn(signal -> signal.source() == SignalSource.DATASTORE_CONNECTION)
                .extracting(signal -> signal.datastore().nodeKey())
                .containsExactlyInAnyOrder("datastore:postgresql:orders", "datastore:redis:pricingcachelogistics");

        DependencySignal orders = signals.stream()
                .filter(signal -> "datastore:postgresql:orders".equals(signal.datastore().nodeKey()))
                .findFirst()
                .orElseThrow();
        assertThat(orders.isPersistence()).isTrue();
        assertThat(orders.confidence()).isEqualTo(Confidence.HIGH);
        assertThat(orders.datastore().engine()).isEqualTo(DatastoreRef.POSTGRES);
        assertThat(orders.datastore().host()).isEqualTo("orders-db.logistics");
        assertThat(orders.evidence().file()).isEqualTo("conf/application.conf");
        assertThat(orders.evidence().line()).isPositive();
        assertThat(orders.evidence().snippet()).contains("jdbc:postgresql://orders-db.logistics:5432/orders");
    }

    @Test
    @DisplayName("Two services pointing at one cache converge on a single node")
    void sharedStoresShareANodeKey() {
        String orderCache = keyOf(scan(Fixtures.repo("log-order-svc"), "logordersvc"), DatastoreRef.REDIS);
        String quoteCache = keyOf(scan(Fixtures.repo("log-quote-svc"), "logquotesvc"), DatastoreRef.REDIS);

        assertThat(orderCache).isEqualTo(quoteCache);
    }

    private String keyOf(List<DependencySignal> signals, String engine) {
        return signals.stream()
                .filter(signal -> engine.equals(signal.datastore().engine()))
                .map(signal -> signal.datastore().nodeKey())
                .findFirst()
                .orElseThrow();
    }

    @Test
    @DisplayName("Migrations attach to the configured database rather than inventing a second one")
    void migrationsAttachToTheConfiguredStore() {
        List<DependencySignal> signals = scan(Fixtures.repo("log-order-svc"), "logordersvc");

        DependencySignal schema = signals.stream()
                .filter(signal -> signal.source() == SignalSource.DATASTORE_SCHEMA)
                .findFirst()
                .orElseThrow();

        assertThat(schema.datastore().nodeKey()).isEqualTo("datastore:postgresql:orders");
        assertThat(schema.evidence().detail()).contains("2 migration files");
        assertThat(signals).extracting(signal -> signal.datastore().nodeKey())
                .containsOnly("datastore:postgresql:orders", "datastore:redis:pricingcachelogistics");
    }

    @Test
    @DisplayName("Play evolutions alone are a datastore, owned by the service that ships them")
    void evolutionsWithoutAUrlAreStillADatastore() {
        List<DependencySignal> signals = scan(Fixtures.repo("log-user-svc"), "logusersvc");

        assertThat(signals).singleElement().satisfies(signal -> {
            assertThat(signal.source()).isEqualTo(SignalSource.DATASTORE_SCHEMA);
            // The driver on the classpath names the engine; nothing names the database, so the
            // service owns it and the node is labelled — and keyed — accordingly.
            assertThat(signal.datastore().engine()).isEqualTo(DatastoreRef.POSTGRES);
            assertThat(signal.datastore().database()).isNull();
            assertThat(signal.datastore().displayName()).isEqualTo("log-user-svc db");
            assertThat(signal.datastore().nodeKey()).isEqualTo("datastore:postgresql:logusersvc");
            assertThat(signal.confidence()).isEqualTo(Confidence.HIGH);
        });
    }

    @Test
    @DisplayName("A MongoDB URI is read as a datastore, not as an HTTP host")
    void readsMongoUris() {
        assertThat(scan(Fixtures.repo("log-inventory-svc"), "loginventorysvc"))
                .singleElement()
                .satisfies(signal -> {
                    assertThat(signal.datastore().engine()).isEqualTo(DatastoreRef.MONGODB);
                    assertThat(signal.datastore().database()).isEqualTo("inventory");
                });
    }

    @Test
    @DisplayName("A BigQuery JDBC URL yields its dataset")
    void readsBigQuery() {
        assertThat(scan(Fixtures.repo("log-audit-svc"), "logauditsvc"))
                .singleElement()
                .satisfies(signal -> {
                    assertThat(signal.datastore().engine()).isEqualTo(DatastoreRef.BIGQUERY);
                    assertThat(signal.datastore().database()).isEqualTo("audit");
                });
    }

    @Test
    @DisplayName("A URL assembled from environment variables still names its engine")
    void readsUrlsBuiltFromTheEnvironment(@TempDir Path temp) throws IOException {
        // HOCON resolves this to "jdbc:postgresql://:5432/" — no host, no database, but the engine
        // is beyond doubt, and that is worth a HIGH signal rather than a driver-tier guess.
        Path repo = repo(temp, "log-env-svc", """
                db.default {
                  driver = "org.postgresql.Driver"
                  url = "jdbc:postgresql://"${?DB_HOST}":5432/"${?DB_NAME}
                }
                """);

        assertThat(scan(repo, "logenvsvc")).singleElement().satisfies(signal -> {
            assertThat(signal.source()).isEqualTo(SignalSource.DATASTORE_CONNECTION);
            assertThat(signal.datastore().engine()).isEqualTo(DatastoreRef.POSTGRES);
            assertThat(signal.datastore().isUnnamed()).isTrue();
            assertThat(signal.datastore().displayName()).isEqualTo("log-env-svc db");
        });
    }

    @Test
    @DisplayName("Two services with nothing but a driver get a database each, not one shared one")
    void unnamedDatastoresDoNotMerge(@TempDir Path temp) throws IOException {
        String driver = "\nlibraryDependencies ++= Seq(\"org.postgresql\" % \"postgresql\" % \"42.7.3\")\n";
        Path first = repo(temp, "log-alpha-svc", "app.name = \"alpha\"",
                "name := \"log-alpha-svc\"" + driver);
        Path second = repo(temp, "log-beta-svc", "app.name = \"beta\"",
                "name := \"log-beta-svc\"" + driver);

        String alpha = scan(first, "logalphasvc").get(0).datastore().nodeKey();
        String beta = scan(second, "logbetasvc").get(0).datastore().nodeKey();

        assertThat(alpha).isNotEqualTo(beta);
        assertThat(alpha).isEqualTo("datastore:postgresql:logalphasvc");
    }

    @Test
    @DisplayName("A placeholder left in the URL is not a name")
    void placeholdersAreNotNames(@TempDir Path temp) throws IOException {
        Path repo = repo(temp, "log-token-svc", """
                db.default.url = "jdbc:postgresql://${DB_HOST}:5432/orders"
                """);

        assertThat(scan(repo, "logtokensvc")).singleElement().satisfies(signal -> {
            assertThat(signal.datastore().database()).isEqualTo("orders");
            assertThat(signal.datastore().host()).as("${DB_HOST} is not a hostname").isNull();
        });
    }

    @Test
    @DisplayName("A dbname key names the database its own block never did")
    void readsDatabaseNameKeys(@TempDir Path temp) throws IOException {
        Path repo = repo(temp, "log-named-svc", """
                db.default {
                  driver = "org.postgresql.Driver"
                  url = ${?DB_URL}
                  dbname = "shipments"
                }
                """);

        assertThat(scan(repo, "lognamedsvc")).singleElement().satisfies(signal -> {
            assertThat(signal.datastore().engine()).isEqualTo(DatastoreRef.POSTGRES);
            assertThat(signal.datastore().database()).isEqualTo("shipments");
        });
    }

    @Test
    @DisplayName("A name nested under the connection block belongs to it, not to a second database")
    void nestedNamesJoinTheirBlock(@TempDir Path temp) throws IOException {
        Path repo = repo(temp, "log-slick-svc", """
                slick.dbs.default.db {
                  url = "jdbc:postgresql://tracking-db:5432/"
                  properties.databaseName = "tracking"
                }
                """);

        assertThat(scan(repo, "logslicksvc")).singleElement().satisfies(signal ->
                assertThat(signal.datastore().nodeKey()).isEqualTo("datastore:postgresql:tracking"));
    }

    @Test
    @DisplayName("A Slick profile names the engine when no driver class does")
    void readsSlickProfiles(@TempDir Path temp) throws IOException {
        Path repo = repo(temp, "log-profile-svc", """
                slick.dbs.default {
                  profile = "slick.jdbc.MySQLProfile$"
                  db.dbname = "billing"
                }
                """);

        assertThat(scan(repo, "logprofilesvc")).singleElement().satisfies(signal -> {
            assertThat(signal.datastore().engine()).isEqualTo(DatastoreRef.MYSQL);
            assertThat(signal.datastore().database()).isEqualTo("billing");
        });
    }

    @Test
    @DisplayName("A database key under a Mongo block is a Mongo database")
    void engineComesFromTheKeyPathWhenNothingElseSaysIt(@TempDir Path temp) throws IOException {
        Path repo = repo(temp, "log-doc-svc", """
                mongodb.database = "notifications"
                """);

        assertThat(scan(repo, "logdocsvc")).singleElement().satisfies(signal -> {
            assertThat(signal.datastore().engine()).isEqualTo(DatastoreRef.MONGODB);
            assertThat(signal.datastore().database()).isEqualTo("notifications");
        });
    }

    @Test
    @DisplayName("A driver on the classpath is a LOW-confidence guess with no name")
    void driversAreTheLastResort(@TempDir Path temp) throws IOException {
        Path repo = repo(temp, "log-driver-svc", "app.name = \"log-driver-svc\"", """
                name := "log-driver-svc"
                libraryDependencies ++= Seq(
                  "io.lettuce" % "lettuce-core" % "6.3.2.RELEASE"
                )
                """);

        assertThat(scan(repo, "logdriversvc")).singleElement().satisfies(signal -> {
            assertThat(signal.source()).isEqualTo(SignalSource.DATASTORE_DRIVER);
            assertThat(signal.confidence()).isEqualTo(Confidence.LOW);
            assertThat(signal.datastore().engine()).isEqualTo(DatastoreRef.REDIS);
            assertThat(signal.datastore().database()).isNull();
        });
    }

    @Test
    @DisplayName("A driver is not reported when the config already named a real database")
    void driversAreSuppressedByRealEvidence(@TempDir Path temp) throws IOException {
        Path repo = repo(temp, "log-both-svc", """
                db.default.url = "jdbc:postgresql://billing-db:5432/billing"
                """, """
                name := "log-both-svc"
                libraryDependencies ++= Seq(
                  "org.postgresql" % "postgresql" % "42.7.3"
                )
                """);

        assertThat(scan(repo, "logbothsvc")).extracting(DependencySignal::source)
                .containsExactly(SignalSource.DATASTORE_CONNECTION);
    }

    @Test
    @DisplayName("Local development stores are not architecture and are left off the diagram")
    void skipsLocalOnlyStores(@TempDir Path temp) throws IOException {
        Path repo = repo(temp, "log-local-svc", """
                db.default.url = "jdbc:h2:file:./target/dev;AUTO_SERVER=TRUE"
                cache.url = "redis://localhost:6379/0"
                """);

        assertThat(scan(repo, "loglocalsvc")).isEmpty();
    }

    @Test
    @DisplayName("A repository with no persistence at all yields nothing")
    void silenceWhenThereIsNoDatastore(@TempDir Path temp) throws IOException {
        Path repo = repo(temp, "log-stateless-svc", """
                services.pricing.url = "http://log-pricing-svc:8080"
                """);

        assertThat(scan(repo, "logstatelesssvc")).isEmpty();
    }
}
