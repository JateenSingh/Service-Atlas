package com.serviceatlas.parser.scala;

import static org.assertj.core.api.Assertions.assertThat;

import com.serviceatlas.parser.DatastoreRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Reading a datastore out of a connection string. */
class ConnectionStringsTest {

    @Test
    @DisplayName("A JDBC URL yields engine, host and database")
    void parsesJdbc() {
        DatastoreRef ref = ConnectionStrings.parse("jdbc:postgresql://orders-db.logistics:5432/orders")
                .orElseThrow();

        assertThat(ref.engine()).isEqualTo(DatastoreRef.POSTGRES);
        assertThat(ref.host()).isEqualTo("orders-db.logistics");
        assertThat(ref.database()).isEqualTo("orders");
        assertThat(ref.displayName()).isEqualTo("orders");
    }

    @Test
    @DisplayName("Query parameters do not become part of the database name")
    void ignoresQueryParameters() {
        assertThat(ConnectionStrings.parse("jdbc:mysql://db:3306/billing?useSSL=false").orElseThrow()
                .database()).isEqualTo("billing");
    }

    @Test
    @DisplayName("SQL Server names the database in a property rather than the path")
    void parsesSqlServer() {
        DatastoreRef ref = ConnectionStrings
                .parse("jdbc:sqlserver://sql.internal:1433;databaseName=warehouse;encrypt=true")
                .orElseThrow();

        assertThat(ref.engine()).isEqualTo(DatastoreRef.SQL_SERVER);
        assertThat(ref.database()).isEqualTo("warehouse");
    }

    @Test
    @DisplayName("MongoDB replica sets reduce to their first host")
    void parsesMongo() {
        DatastoreRef ref = ConnectionStrings
                .parse("mongodb://user:pw@mongo-a.internal,mongo-b.internal/inventory?replicaSet=rs0")
                .orElseThrow();

        assertThat(ref.engine()).isEqualTo(DatastoreRef.MONGODB);
        assertThat(ref.host()).isEqualTo("mongo-a.internal");
        assertThat(ref.database()).isEqualTo("inventory");
    }

    @Test
    @DisplayName("A Redis database number is not a name worth showing")
    void parsesRedis() {
        DatastoreRef ref = ConnectionStrings.parse("redis://pricing-cache.logistics:6379/0").orElseThrow();

        assertThat(ref.engine()).isEqualTo(DatastoreRef.REDIS);
        assertThat(ref.host()).isEqualTo("pricing-cache.logistics");
        assertThat(ref.database()).isNull();
        assertThat(ref.displayName()).isEqualTo("pricing-cache.logistics");
    }

    @Test
    @DisplayName("BigQuery hides its dataset among semicolon-separated properties")
    void parsesBigQuery() {
        DatastoreRef ref = ConnectionStrings
                .parse("jdbc:bigquery://https://www.googleapis.com/bigquery/v2:443;ProjectId=acme;DatasetId=audit")
                .orElseThrow();

        assertThat(ref.engine()).isEqualTo(DatastoreRef.BIGQUERY);
        assertThat(ref.database()).isEqualTo("audit");
        assertThat(ref.host()).isEqualTo("acme");
    }

    @Test
    @DisplayName("An embedded H2 file is recognised and marked local-only")
    void parsesH2() {
        DatastoreRef ref = ConnectionStrings.parse("jdbc:h2:file:/var/lib/atlas;AUTO_SERVER=TRUE")
                .orElseThrow();

        assertThat(ref.engine()).isEqualTo(DatastoreRef.H2);
        assertThat(ref.database()).isEqualTo("atlas");
        assertThat(ref.isLocalOnly()).isTrue();
    }

    @Test
    @DisplayName("A localhost database is local-only, whatever the engine")
    void detectsLocalhost() {
        assertThat(ConnectionStrings.parse("jdbc:postgresql://localhost:5432/dev").orElseThrow()
                .isLocalOnly()).isTrue();
        assertThat(ConnectionStrings.parse("jdbc:postgresql://orders-db:5432/orders").orElseThrow()
                .isLocalOnly()).isFalse();
    }

    @DisplayName("Anything that is not a connection string yields nothing, rather than a guess")
    @ParameterizedTest
    @ValueSource(strings = {
            "http://log-quote-svc:9000",
            "order-events",
            "kafka-broker:9092",
            "",
            "  ",
            "jdbc:",
            "not a url at all"
    })
    void rejectsNonConnectionStrings(String value) {
        assertThat(ConnectionStrings.parse(value)).isEmpty();
    }

    @Test
    void nullIsHandled() {
        assertThat(ConnectionStrings.parse(null)).isEmpty();
    }

    @Test
    @DisplayName("Identity is engine and database, so different hosts converge on one store")
    void identityIgnoresHost() {
        DatastoreRef viaCluster = ConnectionStrings
                .parse("jdbc:postgresql://orders-db.prod.svc.cluster.local:5432/orders").orElseThrow();
        DatastoreRef viaProxy = ConnectionStrings
                .parse("jdbc:postgresql://pgbouncer:6432/orders").orElseThrow();

        assertThat(viaCluster.nodeKey()).isEqualTo(viaProxy.nodeKey());
    }

    @Test
    @DisplayName("Without a database name, the host label establishes identity")
    void fallsBackToTheHostLabel() {
        DatastoreRef a = ConnectionStrings.parse("redis://pricing-cache.logistics:6379/0").orElseThrow();
        DatastoreRef b = ConnectionStrings.parse("redis://pricing-cache.logistics:6379/1").orElseThrow();

        // The full host is the identity here: "logistics" is not a cluster suffix, so trimming it
        // would merge unrelated caches that happen to share a first label.
        assertThat(a.nodeKey()).isEqualTo(b.nodeKey()).isEqualTo("datastore:redis:pricingcachelogistics");
    }
}
