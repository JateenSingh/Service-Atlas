package com.serviceatlas.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Application-level tuning knobs. Everything here has a sensible default so that Service Atlas
 * runs with no configuration at all (§1.2 goal 1).
 */
@ConfigurationProperties(prefix = "service-atlas")
public class ServiceAtlasProperties {

    /** Directory holding the embedded H2 database and any generated artifacts. */
    private String dataDir = System.getProperty("user.home") + "/.service-atlas";

    private final Scan scan = new Scan();
    private final Lucid lucid = new Lucid();

    public String getDataDir() {
        return dataDir;
    }

    public void setDataDir(String dataDir) {
        this.dataDir = dataDir;
    }

    public Scan getScan() {
        return scan;
    }

    public Lucid getLucid() {
        return lucid;
    }

    public static class Scan {

        /** Default recursion depth when discovering repositories (FR-2.1). */
        private int maxDepth = 3;

        /** Directory names never descended into (FR-2.3). */
        private List<String> ignoredDirectories =
                List.of(".git", "target", "node_modules", ".idea", ".bloop", ".metals", "build", "out", ".gradle");

        /** Hard cap on files read per repository, so a pathological repo cannot stall a scan. */
        private int maxFilesPerRepo = 5000;

        /** Files larger than this (bytes) are skipped by the source scanners. */
        private long maxFileSizeBytes = 2L * 1024 * 1024;

        /** Number of repositories parsed concurrently. */
        private int parallelism = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);

        public int getMaxDepth() {
            return maxDepth;
        }

        public void setMaxDepth(int maxDepth) {
            this.maxDepth = maxDepth;
        }

        public List<String> getIgnoredDirectories() {
            return ignoredDirectories;
        }

        public void setIgnoredDirectories(List<String> ignoredDirectories) {
            this.ignoredDirectories = ignoredDirectories;
        }

        public int getMaxFilesPerRepo() {
            return maxFilesPerRepo;
        }

        public void setMaxFilesPerRepo(int maxFilesPerRepo) {
            this.maxFilesPerRepo = maxFilesPerRepo;
        }

        public long getMaxFileSizeBytes() {
            return maxFileSizeBytes;
        }

        public void setMaxFileSizeBytes(long maxFileSizeBytes) {
            this.maxFileSizeBytes = maxFileSizeBytes;
        }

        public int getParallelism() {
            return parallelism;
        }

        public void setParallelism(int parallelism) {
            this.parallelism = parallelism;
        }
    }

    /** Lucid REST API export (FR-6.2). Disabled unless credentials are supplied (Q-3). */
    public static class Lucid {

        private boolean enabled = false;
        private String clientId;
        private String clientSecret;
        private String baseUrl = "https://api.lucid.co";
        private String redirectUri = "http://localhost:8080/api/v1/lucid/oauth/callback";
        /** Key used to encrypt stored OAuth tokens; supplied via environment in real use. */
        private String tokenEncryptionKey;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getClientId() {
            return clientId;
        }

        public void setClientId(String clientId) {
            this.clientId = clientId;
        }

        public String getClientSecret() {
            return clientSecret;
        }

        public void setClientSecret(String clientSecret) {
            this.clientSecret = clientSecret;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getRedirectUri() {
            return redirectUri;
        }

        public void setRedirectUri(String redirectUri) {
            this.redirectUri = redirectUri;
        }

        public String getTokenEncryptionKey() {
            return tokenEncryptionKey;
        }

        public void setTokenEncryptionKey(String tokenEncryptionKey) {
            this.tokenEncryptionKey = tokenEncryptionKey;
        }

        /** True when the API path is usable end to end (FR-6.2: otherwise the UI hides it). */
        public boolean isConfigured() {
            return enabled
                    && clientId != null && !clientId.isBlank()
                    && clientSecret != null && !clientSecret.isBlank();
        }
    }
}
