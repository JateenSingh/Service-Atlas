package com.serviceatlas.parser;

import com.serviceatlas.parser.common.NameNormalizer;
import java.util.Locale;

/**
 * A datastore a service talks to, as named by that service's configuration.
 *
 * <p>Identity is <b>engine + database name</b>, deliberately not the host. Two services usually
 * reach the same database through different hostnames — a Kubernetes service name in one, a
 * connection-pool proxy in another, an environment variable in a third — and keying on the host
 * would draw them as separate stores and hide the coupling that matters.
 *
 * <p>When the configuration names no database at all — a URL assembled from environment variables,
 * or nothing but a driver on the classpath — identity falls back to the <b>owning service</b>, never
 * to the engine. Keying an unnamed store on its engine merges every service that happens to use
 * PostgreSQL into one node called "PostgreSQL", which is the opposite of what the shared-database
 * rule above is for: convergence has to be earned by a name two services actually share.
 *
 * @param engine   the engine, e.g. {@code PostgreSQL}, {@code MongoDB}, {@code Redis}
 * @param database the logical database, schema or keyspace name, when the config reveals one
 * @param host     the host as configured, kept for the inspector rather than for identity
 * @param owner    the service whose configuration this came from, a last-resort identity only
 */
public record DatastoreRef(String engine, String database, String host, String owner) {

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
        database = literal(database);
        host = literal(host);
        owner = owner == null || owner.isBlank() ? null : owner.strip();
    }

    public static DatastoreRef of(String engine, String database, String host) {
        return new DatastoreRef(engine, database, host, null);
    }

    public static DatastoreRef of(String engine, String database, String host, String owner) {
        return new DatastoreRef(engine, database, host, owner);
    }

    /** The same reference, attributed to the service that configured it. */
    public DatastoreRef ownedBy(String service) {
        return new DatastoreRef(engine, database, host, service);
    }

    /** The same reference with a database name it did not have. */
    public DatastoreRef named(String databaseName) {
        return database != null ? this : new DatastoreRef(engine, databaseName, host, owner);
    }

    /** Stable node key: {@code datastore:postgresql:orders}. */
    public String nodeKey() {
        return "datastore:" + NameNormalizer.canonical(engine) + ":" + NameNormalizer.canonical(identity());
    }

    /** What the node is called on the canvas. */
    public String displayName() {
        if (database != null) {
            return database;
        }
        if (host != null) {
            return host;
        }
        // Nothing named it, so say whose it is — "log-pricing-svc db", with the engine as the
        // subtitle. A node labelled with the bare engine name tells the reader nothing.
        return owner != null ? owner + " db" : engine;
    }

    /** True when the configuration never actually named this store. */
    public boolean isUnnamed() {
        return database == null && host == null;
    }

    /** The part of the reference that establishes identity. */
    private String identity() {
        if (database != null) {
            return database;
        }
        // No database name: fall back to the host, reduced to its service label so that
        // "orders-db.prod.svc.cluster.local" and "orders-db" are the same store.
        if (host != null) {
            return NameNormalizer.serviceLabelOf(host);
        }
        return owner != null ? owner : engine;
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

    /**
     * Blank, or an unresolved placeholder, is not a name. A URL like
     * {@code jdbc:postgresql://${DB_HOST}/orders} leaves the host as literal text after HOCON
     * resolution, and a node called {@code ${DB_HOST}} is worse than no node at all.
     */
    private static String literal(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String stripped = value.strip();
        return stripped.contains("${") || stripped.contains("$(") ? null : stripped;
    }
}
