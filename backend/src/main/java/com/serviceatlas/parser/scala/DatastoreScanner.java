package com.serviceatlas.parser.scala;

import com.serviceatlas.graph.model.Evidence;
import com.serviceatlas.graph.model.SignalSource;
import com.serviceatlas.parser.DatastoreRef;
import com.serviceatlas.parser.DependencySignal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Finds the databases and caches a service talks to, and draws them on the diagram.
 *
 * <p>Three tiers of evidence, deliberately weighted differently:
 *
 * <ul>
 *   <li><b>Configuration</b> (HIGH) — a connection string names an engine, a host and usually a
 *       database; a {@code dbname}/{@code database}/{@code keyspace} key names the database on its
 *       own; a driver class or Slick profile names the engine. These are read together, per config
 *       block, so a block that spreads them across three keys still produces one datastore.
 *   <li><b>Schema ownership</b> (HIGH) — a service shipping Flyway migrations or Play evolutions
 *       owns that schema, even when the URL only ever arrives from the environment. Without this
 *       tier, exactly the services with the most disciplined configuration would go undrawn.
 *   <li><b>Driver dependencies</b> (LOW) — a Postgres driver on the classpath says a database is
 *       likely but names nothing. Emitted only when the first two tiers found nothing at all, so a
 *       service is never drawn with both a real database and a vague one.
 * </ul>
 *
 * <p>Every reference is attributed to the service that configured it, so the ones nothing named
 * stay one-per-service instead of collapsing into a single node called "PostgreSQL".
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

    /**
     * Fragments that identify an engine inside a driver class, a Slick profile or a config key —
     * the shapes a config file uses to say which database it means without writing a URL. Ordered,
     * so {@code mariadb} is recognised before the {@code mysql} its driver name also contains.
     */
    private static final Map<String, String> ENGINE_FRAGMENTS = new LinkedHashMap<>();

    static {
        ENGINE_FRAGMENTS.put("postgres", DatastoreRef.POSTGRES);
        ENGINE_FRAGMENTS.put("mariadb", DatastoreRef.MARIADB);
        ENGINE_FRAGMENTS.put("mysql", DatastoreRef.MYSQL);
        ENGINE_FRAGMENTS.put("sqlserver", DatastoreRef.SQL_SERVER);
        ENGINE_FRAGMENTS.put("oracle", DatastoreRef.ORACLE);
        ENGINE_FRAGMENTS.put("mongo", DatastoreRef.MONGODB);
        ENGINE_FRAGMENTS.put("cassandra", DatastoreRef.CASSANDRA);
        ENGINE_FRAGMENTS.put("elastic", DatastoreRef.ELASTICSEARCH);
        ENGINE_FRAGMENTS.put("bigquery", DatastoreRef.BIGQUERY);
        ENGINE_FRAGMENTS.put("redis", DatastoreRef.REDIS);
    }

    /** Key leaves whose value is the name of a database, schema or keyspace. */
    private static final Set<String> NAME_KEYS = Set.of(
            "dbname", "database", "databasename", "keyspace", "catalog");

    /** Key leaves whose value names a driver class or a Slick profile. */
    private static final Set<String> ENGINE_KEYS = Set.of(
            "driver", "driverclass", "driverclassname", "profile", "dialect");

    /** A plausible database name: not a path, not a class name, not a sentence. */
    private static final Pattern NAME_VALUE = Pattern.compile("^[A-Za-z][A-Za-z0-9_\\-]{1,62}$");

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

    // ---------------------------------------------------------------- configuration

    /** One config block's worth of datastore facts, gathered before any of them becomes a signal. */
    private static final class Candidate {
        private DatastoreRef ref;
        private ConfigValues.Entry evidence;

        Candidate(DatastoreRef ref, ConfigValues.Entry evidence) {
            this.ref = ref;
            this.evidence = evidence;
        }
    }

    private List<DependencySignal> fromConfiguration(ScalaScanContext context, Set<String> seen) {
        List<ConfigValues.Entry> entries = ConfigValues.read(context.files()).entries().stream()
                .filter(entry -> !entry.parseFailed())
                .toList();

        Map<String, Candidate> blocks = new LinkedHashMap<>();

        // A connection string identifies itself by its scheme, so every string value is worth
        // testing and the key name adds nothing.
        for (ConfigValues.Entry entry : entries) {
            ConnectionStrings.parse(entry.value())
                    .ifPresent(ref -> blocks.putIfAbsent(parentOf(entry.key()), new Candidate(ref, entry)));
        }

        // A driver class or Slick profile names the engine for a block that had no URL at all.
        for (ConfigValues.Entry entry : entries) {
            if (!leafIsIn(entry, ENGINE_KEYS)) {
                continue;
            }
            engineFragment(entry.value()).ifPresent(engine -> blocks.computeIfAbsent(
                    parentOf(entry.key()),
                    path -> new Candidate(DatastoreRef.of(engine, null, null), entry)));
        }

        // A name key fills in the database for the block it belongs to, or stands on its own.
        for (ConfigValues.Entry entry : entries) {
            String name = entry.value().strip();
            if (!leafIsIn(entry, NAME_KEYS) || !NAME_VALUE.matcher(name).matches()) {
                continue;
            }
            String parent = parentOf(entry.key());
            Candidate block = nearestBlock(blocks, parent);
            if (block != null) {
                if (block.ref.database() == null) {
                    block.ref = block.ref.named(name);
                    block.evidence = entry;
                }
                continue;
            }
            String engine = engineFragment(entry.key())
                    .or(() -> engineFromDrivers(context))
                    .orElse(DatastoreRef.UNKNOWN_ENGINE);
            blocks.put(parent, new Candidate(DatastoreRef.of(engine, name, null), entry));
        }

        List<DependencySignal> signals = new ArrayList<>();
        for (Candidate candidate : blocks.values()) {
            DatastoreRef datastore = candidate.ref.ownedBy(context.build().name());
            if (datastore.isLocalOnly()) {
                // An embedded H2 or a localhost dev database is not part of the architecture.
                continue;
            }
            if (!seen.add(datastore.nodeKey())) {
                continue;
            }
            ConfigValues.Entry entry = candidate.evidence;
            Evidence evidence = Evidence.of(
                    SignalSource.DATASTORE_CONNECTION,
                    entry.file(),
                    entry.line(),
                    entry.key() + " = \"" + entry.value() + "\"",
                    describe(datastore));
            signals.add(DependencySignal.persistence(
                    context.nodeKey(), datastore, SignalSource.DATASTORE_CONNECTION, evidence));
        }
        return signals;
    }

    private String describe(DatastoreRef datastore) {
        if (datastore.database() != null) {
            return "Connects to " + datastore.engine() + " database '" + datastore.database() + "'";
        }
        if (datastore.host() != null) {
            return "Connects to " + datastore.engine() + " at " + datastore.host();
        }
        return "Connects to " + datastore.engine()
                + ", but the configuration never names the database — it comes from the environment";
    }

    /**
     * The block a name key belongs to: the deepest connection block whose path is related to the
     * key's own, so {@code slick.dbs.default.db.properties.databaseName} finds the URL configured
     * at {@code slick.dbs.default.db}. Failing that, a single block in the file claims it.
     */
    private Candidate nearestBlock(Map<String, Candidate> blocks, String parent) {
        Candidate best = null;
        int bestLength = -1;
        for (Map.Entry<String, Candidate> block : blocks.entrySet()) {
            String path = block.getKey();
            boolean related = parent.equals(path)
                    || parent.startsWith(path + ".")
                    || path.startsWith(parent + ".");
            if (related && path.length() > bestLength) {
                best = block.getValue();
                bestLength = path.length();
            }
        }
        return best != null ? best : (blocks.size() == 1 ? blocks.values().iterator().next() : null);
    }

    /** Reads an engine out of a driver class, a Slick profile or a config key path. */
    private Optional<String> engineFragment(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String lower = value.toLowerCase(Locale.ROOT);
        return ENGINE_FRAGMENTS.entrySet().stream()
                .filter(fragment -> lower.contains(fragment.getKey()))
                .map(Map.Entry::getValue)
                .findFirst();
    }

    private boolean leafIsIn(ConfigValues.Entry entry, Set<String> leaves) {
        List<String> tokens = entry.keyTokens();
        if (tokens.isEmpty()) {
            return false;
        }
        // Key tokens are split on separators, so "databaseName" arrives as [database, name].
        String leaf = tokens.get(tokens.size() - 1);
        String pair = tokens.size() >= 2 ? tokens.get(tokens.size() - 2) + leaf : leaf;
        return leaves.contains(leaf) || leaves.contains(pair);
    }

    private String parentOf(String key) {
        int dot = key.lastIndexOf('.');
        return dot > 0 ? key.substring(0, dot) : "";
    }

    // ---------------------------------------------------------------- schema and drivers

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

            // Attach to the configured database when there is one; otherwise the migrations prove a
            // database exists without naming it, and it belongs to the service that ships them.
            DatastoreRef datastore = configured.orElseGet(() -> DatastoreRef.of(
                    engineFromDrivers(context).orElse(DatastoreRef.UNKNOWN_ENGINE),
                    null,
                    null,
                    context.build().name()));
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
            DatastoreRef datastore = DatastoreRef.of(engine, null, null, context.build().name());
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
