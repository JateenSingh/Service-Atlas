package com.serviceatlas.parser.scala;

import com.serviceatlas.parser.DatastoreRef;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a datastore out of a connection string.
 *
 * <p>Deliberately format-specific rather than generic: a JDBC URL, a MongoDB URI and a Redis URL
 * each put the database name somewhere different, and guessing with one regex produces confident
 * nonsense. Anything not recognised returns empty, which is honest — an unparsed string becomes no
 * node rather than a node called something arbitrary.
 */
public final class ConnectionStrings {

    /**
     * {@code jdbc:postgresql://host:5432/orders?ssl=true}, and the semicolon-separated property
     * tails that SQL Server and H2 use: {@code jdbc:sqlserver://host;databaseName=orders}.
     */
    private static final Pattern JDBC = Pattern.compile(
            "^jdbc:([a-z0-9]+):(?://(?<host>[^/:;,?]+)(?::\\d+)?)?"
                    + "(?:[^;?]*?/(?<db>[A-Za-z0-9_.\\-]+))?(?:[;?].*)?$",
            Pattern.CASE_INSENSITIVE);

    /** SQL Server puts the database in a property: {@code jdbc:sqlserver://host;databaseName=orders} */
    private static final Pattern SQLSERVER_DB =
            Pattern.compile("databaseName=([A-Za-z0-9_.\\-]+)", Pattern.CASE_INSENSITIVE);

    /** {@code mongodb://user:pw@host1,host2/orders?replicaSet=rs0} and {@code mongodb+srv://…} */
    private static final Pattern MONGO = Pattern.compile(
            "^mongodb(?:\\+srv)?://(?:[^@/]*@)?(?<host>[^/?]+)(?:/(?<db>[A-Za-z0-9_.\\-]+))?(?:\\?.*)?$",
            Pattern.CASE_INSENSITIVE);

    /** {@code redis://host:6379/0} and {@code rediss://…} */
    private static final Pattern REDIS = Pattern.compile(
            "^rediss?://(?:[^@/]*@)?(?<host>[^/:?]+)(?::\\d+)?(?:/(?<db>\\d+))?.*$",
            Pattern.CASE_INSENSITIVE);

    private ConnectionStrings() {
    }

    /** Parses any supported connection string, or empty when it is not one. */
    public static Optional<DatastoreRef> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String trimmed = value.strip();
        String lower = trimmed.toLowerCase(Locale.ROOT);

        if (lower.startsWith("jdbc:")) {
            return parseJdbc(trimmed);
        }
        if (lower.startsWith("mongodb://") || lower.startsWith("mongodb+srv://")) {
            return parseWith(MONGO, trimmed, DatastoreRef.MONGODB);
        }
        if (lower.startsWith("redis://") || lower.startsWith("rediss://")) {
            return parseWith(REDIS, trimmed, DatastoreRef.REDIS)
                    // Redis database numbers ("/0") are not names worth showing.
                    .map(ref -> DatastoreRef.of(ref.engine(), null, ref.host()));
        }
        return Optional.empty();
    }

    /**
     * BigQuery's JDBC URL embeds an HTTPS endpoint and semicolon-separated properties, which the
     * general JDBC pattern cannot read. The dataset is the meaningful "database" here.
     */
    private static final Pattern BIGQUERY_DATASET =
            Pattern.compile("DatasetId=([A-Za-z0-9_.\\-]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern BIGQUERY_PROJECT =
            Pattern.compile("ProjectId=([A-Za-z0-9_.\\-]+)", Pattern.CASE_INSENSITIVE);

    private static Optional<DatastoreRef> parseJdbc(String url) {
        if (url.toLowerCase(Locale.ROOT).startsWith("jdbc:bigquery")) {
            Matcher dataset = BIGQUERY_DATASET.matcher(url);
            Matcher project = BIGQUERY_PROJECT.matcher(url);
            return Optional.of(DatastoreRef.of(
                    DatastoreRef.BIGQUERY,
                    dataset.find() ? dataset.group(1) : null,
                    project.find() ? project.group(1) : null));
        }

        Matcher matcher = JDBC.matcher(url);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        String subprotocol = matcher.group(1).toLowerCase(Locale.ROOT);
        String engine = engineForJdbc(subprotocol);
        String host = matcher.group("host");
        String database = matcher.group("db");

        if (DatastoreRef.SQL_SERVER.equals(engine)) {
            Matcher named = SQLSERVER_DB.matcher(url);
            database = named.find() ? named.group(1) : null;
        }
        if (DatastoreRef.H2.equals(engine)) {
            // "jdbc:h2:file:/var/data/atlas" — the last path segment is the database.
            int slash = url.lastIndexOf('/');
            String tail = slash >= 0 ? url.substring(slash + 1) : url;
            database = tail.split("[;?]")[0];
            host = null;
        }
        return Optional.of(DatastoreRef.of(engine, database, host));
    }

    private static Optional<DatastoreRef> parseWith(Pattern pattern, String value, String engine) {
        Matcher matcher = pattern.matcher(value);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        String host = matcher.group("host");
        // A comma-separated replica set: the first host is representative.
        if (host != null && host.contains(",")) {
            host = host.substring(0, host.indexOf(','));
        }
        return Optional.of(DatastoreRef.of(engine, matcher.group("db"), host));
    }

    /** Maps a JDBC subprotocol to a human engine name. */
    public static String engineForJdbc(String subprotocol) {
        return switch (subprotocol) {
            case "postgresql", "postgres" -> DatastoreRef.POSTGRES;
            case "mysql" -> DatastoreRef.MYSQL;
            case "mariadb" -> DatastoreRef.MARIADB;
            case "sqlserver", "jtds" -> DatastoreRef.SQL_SERVER;
            case "oracle" -> DatastoreRef.ORACLE;
            case "h2" -> DatastoreRef.H2;
            case "cassandra" -> DatastoreRef.CASSANDRA;
            case "bigquery" -> DatastoreRef.BIGQUERY;
            default -> DatastoreRef.UNKNOWN_ENGINE;
        };
    }
}
