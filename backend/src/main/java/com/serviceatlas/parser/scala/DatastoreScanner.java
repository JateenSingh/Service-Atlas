package com.serviceatlas.parser.scala;

import com.serviceatlas.graph.model.Evidence;
import com.serviceatlas.graph.model.SignalSource;
import com.serviceatlas.parser.DatastoreRef;
import com.serviceatlas.parser.DependencySignal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Finds the databases and caches a service talks to, and draws them on the diagram.
 *
 * <p>Three tiers of evidence, deliberately weighted differently:
 *
 * <ul>
 *   <li><b>Connection strings</b> in configuration (HIGH) — a JDBC URL names an engine, a host and
 *       usually a database. This is the strongest thing static analysis can find.
 *   <li><b>Schema ownership</b> (HIGH) — a service shipping Flyway migrations or Play evolutions
 *       owns that schema, even when the URL only ever arrives from the environment. Without this
 *       tier, exactly the services with the most disciplined configuration would go undrawn.
 *   <li><b>Driver dependencies</b> (LOW) — a Postgres driver on the classpath says a database is
 *       likely but names nothing. Emitted only when the first two tiers found nothing at all, so a
 *       service is never drawn with both a real database and a vague one.
 * </ul>
 */
@Component
public final class DatastoreScanner implements ScalaSignalScanner {

    /** Driver and client coordinates, mapped to the engine they imply. */
    private static final Map<String, String> DRIVER_COORDINATES = Map.ofEntries(
            Map.entry("org.postgresql:postgresql", DatastoreRef.POSTGRES),
            Map.entry("com.mysql:mysql-connector-j", DatastoreRef.MYSQL),
            Map.entry("mysql:mysql-connector-java", DatastoreRef.MYSQL),
            Map.entry("org.mariadb.jdbc:mariadb-java-client", DatastoreRef.MARIADB),
            Map.entry("com.microsoft.sqlserver:mssql-jdbc", DatastoreRef.SQL_SERVER),
            Map.entry("com.oracle.database.jdbc:ojdbc11", DatastoreRef.ORACLE),
            Map.entry("org.mongodb.scala:mongo-scala-driver", DatastoreRef.MONGODB),
            Map.entry("org.mongodb:mongodb-driver-sync", DatastoreRef.MONGODB),
            Map.entry("io.lettuce:lettuce-core", DatastoreRef.REDIS),
            Map.entry("redis.clients:jedis", DatastoreRef.REDIS),
            Map.entry("com.datastax.oss:java-driver-core", DatastoreRef.CASSANDRA),
            Map.entry("co.elastic.clients:elasticsearch-java", DatastoreRef.ELASTICSEARCH),
            Map.entry("software.amazon.awssdk:dynamodb", DatastoreRef.DYNAMODB),
            Map.entry("com.google.cloud:google-cloud-bigtable", DatastoreRef.BIGTABLE),
            Map.entry("com.google.cloud:google-cloud-spanner", DatastoreRef.SPANNER),
            Map.entry("com.google.cloud:google-cloud-bigquery", DatastoreRef.BIGQUERY));

    /** Engines whose schema a migration folder could describe. */
    private static final Set<String> RELATIONAL_ENGINES = Set.of(
            DatastoreRef.POSTGRES, DatastoreRef.MYSQL, DatastoreRef.MARIADB,
            DatastoreRef.SQL_SERVER, DatastoreRef.ORACLE, DatastoreRef.H2);

    /** Directories that mean "this service owns a schema". */
    private static final List<String> MIGRATION_DIRECTORIES = List.of(
            "conf/db/migration", "src/main/resources/db/migration", "conf/evolutions", "db/migration");

    @Override
    public String id() {
        return "datastores";
    }

    @Override
    public List<DependencySignal> scan(ScalaScanContext context) {
        List<DependencySignal> signals = new ArrayList<>();
        Set<String> seenNodes = new LinkedHashSet<>();

        List<DependencySignal> configured = fromConfiguration(context, seenNodes);
        signals.addAll(configured);
        signals.addAll(fromMigrations(context, seenNodes, relationalStore(configured)));

        if (signals.isEmpty()) {
            // Nothing concrete was found, so a driver on the classpath is the only thing we know.
            signals.addAll(fromDrivers(context));
        }
        return signals;
    }

    /**
     * The relational database this service configured, if exactly one. Migrations then belong to
     * <em>that</em> store rather than to a new one — a service with both a JDBC URL and a migration
     * folder has one database, not two.
     */
    private Optional<DatastoreRef> relationalStore(List<DependencySignal> configured) {
        List<DatastoreRef> relational = configured.stream()
                .map(DependencySignal::datastore)
                .filter(ref -> RELATIONAL_ENGINES.contains(ref.engine()))
                .toList();
        return relational.size() == 1 ? Optional.of(relational.get(0)) : Optional.empty();
    }

    /** Connection strings in HOCON configuration — the strongest evidence available. */
    private List<DependencySignal> fromConfiguration(ScalaScanContext context, Set<String> seen) {
        List<DependencySignal> signals = new ArrayList<>();

        for (ConfigValues.Entry entry : ConfigValues.read(context.files()).entries()) {
            if (entry.parseFailed()) {
                continue;
            }
            // A connection string identifies itself by its scheme, so every string value is worth
            // testing and the key name adds nothing.
            Optional<DatastoreRef> parsed = ConnectionStrings.parse(entry.value());
            if (parsed.isEmpty()) {
                continue;
            }
            DatastoreRef datastore = parsed.get();
            if (datastore.isLocalOnly()) {
                // An embedded H2 or a localhost dev database is not part of the architecture.
                continue;
            }
            if (!seen.add(datastore.nodeKey())) {
                continue;
            }
            Evidence evidence = Evidence.of(
                    SignalSource.DATASTORE_CONNECTION,
                    entry.file(),
                    entry.line(),
                    entry.key() + " = \"" + entry.value() + "\"",
                    "Connects to " + datastore.engine()
                            + (datastore.database() == null ? "" : " database '" + datastore.database() + "'"));
            signals.add(DependencySignal.persistence(
                    context.nodeKey(), datastore, SignalSource.DATASTORE_CONNECTION, evidence));
        }
        return signals;
    }

    /**
     * Migration and evolution directories: a service that ships schema for a database uses that
     * database, whatever its configuration says at runtime.
     */
    private List<DependencySignal> fromMigrations(ScalaScanContext context, Set<String> seen,
                                                  Optional<DatastoreRef> configured) {
        List<DependencySignal> signals = new ArrayList<>();

        for (String directory : MIGRATION_DIRECTORIES) {
            List<Path> migrations = context.files().find(directory, path -> {
                String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                return name.endsWith(".sql");
            });
            if (migrations.isEmpty()) {
                continue;
            }

            // Attach to the configured database when there is one; otherwise the migration is all
            // we have, and the service name is the honest label for the schema it owns.
            DatastoreRef datastore = configured.orElseGet(() -> DatastoreRef.of(
                    engineFromDrivers(context).orElse(DatastoreRef.UNKNOWN_ENGINE),
                    context.build().name(),
                    null));
            if (!seen.add("schema:" + datastore.nodeKey())) {
                continue;
            }
            String example = context.files().relativize(migrations.get(0));
            Evidence evidence = Evidence.of(
                    SignalSource.DATASTORE_SCHEMA,
                    example,
                    0,
                    example,
                    "Owns a schema: " + migrations.size() + " migration file"
                            + (migrations.size() == 1 ? "" : "s") + " under " + directory);
            signals.add(DependencySignal.persistence(
                    context.nodeKey(), datastore, SignalSource.DATASTORE_SCHEMA, evidence));
        }
        return signals;
    }

    /** A driver on the classpath, when nothing else revealed a datastore. */
    private List<DependencySignal> fromDrivers(ScalaScanContext context) {
        List<DependencySignal> signals = new ArrayList<>();
        Set<String> seenEngines = new LinkedHashSet<>();

        for (ArtifactCoordinate dependency : context.build().dependencies()) {
            String engine = DRIVER_COORDINATES.get(dependency.group() + ":"
                    + dependency.artifactWithoutScalaSuffix());
            if (engine == null || !seenEngines.add(engine)) {
                continue;
            }
            DatastoreRef datastore = DatastoreRef.of(engine, null, null);
            Evidence evidence = Evidence.of(
                    SignalSource.DATASTORE_DRIVER,
                    dependency.file(),
                    dependency.line(),
                    dependency.sourceLine(),
                    "Depends on the " + engine + " driver, but no connection details were found");
            signals.add(DependencySignal.persistence(
                    context.nodeKey(), datastore, SignalSource.DATASTORE_DRIVER, evidence));
        }
        return signals;
    }

    private Optional<String> engineFromDrivers(ScalaScanContext context) {
        return context.build().dependencies().stream()
                .map(dependency -> DRIVER_COORDINATES.get(
                        dependency.group() + ":" + dependency.artifactWithoutScalaSuffix()))
                .filter(java.util.Objects::nonNull)
                .findFirst();
    }
}
