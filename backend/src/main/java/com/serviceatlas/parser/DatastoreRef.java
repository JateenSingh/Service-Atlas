package com.serviceatlas.parser;

import com.serviceatlas.parser.common.NameNormalizer;
import java.util.Locale;

/**
 * A datastore a service talks to, as named by that service's configuration.
 *
 * <p>Identity is <b>engine + database name</b>, deliberately not the host. Two services usually
 * reach the same database through different hostnames — a Kubernetes service name in one, a
 * connection-pool proxy in another, an environment variable in a third — and keying on the host
 * would draw them as separate stores and hide the coupling that matters. Where no database name is
 * available, the host is the fallback identity, which is the best that can honestly be said.
 *
 * @param engine   the engine, e.g. {@code PostgreSQL}, {@code MongoDB}, {@code Redis}
 * @param database the logical database, schema or keyspace name, when the config reveals one
 * @param host     the host as configured, kept for the inspector rather than for identity
 */
public record DatastoreRef(String engine, String database, String host) {

    public static final String POSTGRES = "PostgreSQL";
    public static final String MYSQL = "MySQL";
    public static final String MARIADB = "MariaDB";
    public static final String SQL_SERVER = "SQL Server";
    public static final String ORACLE = "Oracle";
    public static final String H2 = "H2";
    public static final String MONGODB = "MongoDB";
    public static final String REDIS = "Redis";
    public static final String CASSANDRA = "Cassandra";
    public static final String ELASTICSEARCH = "Elasticsearch";
    public static final String DYNAMODB = "DynamoDB";
    public static final String BIGTABLE = "Bigtable";
    public static final String SPANNER = "Cloud Spanner";
    public static final String BIGQUERY = "BigQuery";
    public static final String UNKNOWN_ENGINE = "Database";

    public DatastoreRef {
        engine = engine == null || engine.isBlank() ? UNKNOWN_ENGINE : engine;
        database = database == null || database.isBlank() ? null : database.strip();
        host = host == null || host.isBlank() ? null : host.strip();
    }

    public static DatastoreRef of(String engine, String database, String host) {
        return new DatastoreRef(engine, database, host);
    }

    /** Stable node key: {@code datastore:postgresql:orders}. */
    public String nodeKey() {
        return "datastore:" + NameNormalizer.canonical(engine) + ":" + NameNormalizer.canonical(identity());
    }

    /** What the node is called on the canvas. */
    public String displayName() {
        return database != null ? database : (host != null ? host : engine);
    }

    /** The part of the reference that establishes identity. */
    private String identity() {
        if (database != null) {
            return database;
        }
        // No database name: fall back to the host, reduced to its service label so that
        // "orders-db.prod.svc.cluster.local" and "orders-db" are the same store.
        return host != null ? NameNormalizer.serviceLabelOf(host) : engine;
    }

    /** True when this is a local development store rather than real infrastructure. */
    public boolean isLocalOnly() {
        if (H2.equals(engine)) {
            return true;
        }
        if (host == null) {
            return false;
        }
        String lower = host.toLowerCase(Locale.ROOT);
        return lower.equals("localhost") || lower.startsWith("127.0.0.");
    }
}
