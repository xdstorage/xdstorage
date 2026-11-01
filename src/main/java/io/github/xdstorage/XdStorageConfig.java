package io.github.xdstorage;

import io.github.xdstorage.resource.XdStorageIsolationLevel;

/**
 * Immutable open-time configuration for a storage instance (SRE-OPTIMIZATION-PLAN phases S/O).
 * Every default reproduces the pre-config engine behavior exactly, so passing
 * {@link #defaults()} (or using the old provider overloads) changes nothing.
 *
 * <p>Knobs:</p>
 * <ul>
 *   <li>{@code durableWrites} — S-2: fsync the resource file before the atomic rename and
 *       fsync the parent directory after it. Default OFF: commits are process-crash-safe only.
 *       With it ON, commits are power-loss-safe at the cost of one fsync per written file.</li>
 *   <li>{@code acceptMissingEofSentinel} — S-1: when false (default), a resource file without
 *       the EOF sentinel fails loudly at open (truncation detection). Set true only to migrate
 *       a pre-sentinel (legacy) storage: the first commit rewrites every touched file with the
 *       sentinel.</li>
 *   <li>{@code daemonWorkerThreads} — S-4: worker pool threads are daemon, so a forgotten
 *       {@code shutdown()} cannot hang JVM exit. Default OFF (pre-config behavior).</li>
 *   <li>{@code registerShutdownHook} — S-4: best-effort {@code shutdown()} on JVM exit.
 *       Cannot replace {@code daemonWorkerThreads}: hooks run only after the last non-daemon
 *       thread exits, so a non-daemon idle pool blocks JVM exit before hooks ever run.</li>
 *   <li>{@code workerPoolSize} — internal worker pool size (default 15, pre-config value).</li>
 *   <li>{@code defaultTransactionTimeoutMillis} — timeout used by the no-arg
 *       {@code beginTransaction()} (default 3000, pre-config value).</li>
 *   <li>{@code isolationLevel} — MVCC isolation applied to every resource cache of the
 *       storage at open (default SNAPSHOT, pre-config value; previously unreachable via the
 *       public API).</li>
 * </ul>
 */
public final class XdStorageConfig {

    private final boolean durableWrites;
    private final boolean acceptMissingEofSentinel;
    private final boolean daemonWorkerThreads;
    private final boolean registerShutdownHook;
    private final int workerPoolSize;
    private final long defaultTransactionTimeoutMillis;
    private final XdStorageIsolationLevel isolationLevel;
    private final boolean journalWrites;
    private final boolean indexJournalWrites;
    private final long journalCheckpointBytes;
    private final long txMaterializedObjectsCap;
    private final boolean enableForeignKeys;

    private XdStorageConfig(final Builder builder) {
        this.durableWrites = builder.durableWrites;
        this.acceptMissingEofSentinel = builder.acceptMissingEofSentinel;
        this.daemonWorkerThreads = builder.daemonWorkerThreads;
        this.registerShutdownHook = builder.registerShutdownHook;
        this.workerPoolSize = builder.workerPoolSize;
        this.defaultTransactionTimeoutMillis = builder.defaultTransactionTimeoutMillis;
        this.isolationLevel = builder.isolationLevel;
        this.journalWrites = builder.journalWrites;
        this.indexJournalWrites = builder.indexJournalWrites;
        this.journalCheckpointBytes = builder.journalCheckpointBytes;
        this.txMaterializedObjectsCap = builder.txMaterializedObjectsCap;
        this.enableForeignKeys = builder.enableForeignKeys;
    }

    /** The all-defaults configuration.
     *
     * @return the pre-config engine behavior: every knob at its documented default (see the class javadoc) */
    public static XdStorageConfig defaults() {
        return new Builder().build();
    }

    /** Starts a custom configuration.
     *
     * @return a fresh builder with every knob at its default (identical to {@link #defaults()}) */
    public static Builder builder() {
        return new Builder();
    }

    /** Answers whether commits fsync.
     *
     * @return whether commits fsync (power-loss safe, measured ~8x write cost). Default {@code false} */
    public boolean isDurableWrites() {
        return durableWrites;
    }

    /** Answers whether sentinel-less files open.
     *
     * @return whether a resource file without the EOF sentinel opens instead of failing loudly. Default {@code false} */
    public boolean isAcceptMissingEofSentinel() {
        return acceptMissingEofSentinel;
    }

    /** Answers whether workers are daemon threads.
     *
     * @return whether the worker pool uses daemon threads (a forgotten {@code shutdown()} cannot block JVM exit). Default {@code false} */
    public boolean isDaemonWorkerThreads() {
        return daemonWorkerThreads;
    }

    /** Answers whether a JVM-exit hook is registered.
     *
     * @return whether a best-effort {@code shutdown()} runs on JVM exit. Default {@code false} */
    public boolean isRegisterShutdownHook() {
        return registerShutdownHook;
    }

    /** The internal worker pool size.
     *
     * @return internal worker pool size. Default 15 */
    public int getWorkerPoolSize() {
        return workerPoolSize;
    }

    /** The default transaction timeout.
     *
     * @return timeout (ms) used by the no-arg {@code beginTransaction()}. Default 3000 */
    public long getDefaultTransactionTimeoutMillis() {
        return defaultTransactionTimeoutMillis;
    }

    /** The configured MVCC isolation.
     *
     * @return MVCC isolation applied to every resource cache of the storage at open. Default SNAPSHOT */
    public XdStorageIsolationLevel getIsolationLevel() {
        return isolationLevel;
    }

    /** W-1: append-only commit journal for data/reference resources (YAML format only; default ON).
     *
     * @return whether data-class commits append to a journal instead of rewriting whole files
     */
    public boolean isJournalWrites() {
        return journalWrites;
    }

    /** W-4a (docs/tasks/2.1.0/32, W4-DESIGN.md): append-only commit journal for the HASH
     * id-index resource — the O(N)-per-commit full-file rewrite becomes O(delta). YAML
     * format only; default ON (W-4a flip done at 2.1.0).
     *
     * @return whether the hash id-index commits append to a journal
     */
    public boolean isIndexJournalWrites() {
        return indexJournalWrites;
    }

    /** Journal size above which a checkpoint (full base rewrite + journal reset) runs after a commit.
     *
     * @return the checkpoint threshold in bytes (default 16 MB)
     */
    public long getJournalCheckpointBytes() {
        return journalCheckpointBytes;
    }

    /**
     * W-2: per-transaction materialization budget (default 0 = unlimited). When a single
     * transaction materializes more distinct objects (by-id reads, scans, watch), the read
     * fails LOUDLY with an actionable message instead of letting the heap grow unbounded
     * (the OOM death the SRE plan flagged). Write-out for a commit is deliberately exempt.
     *
     * @return the per-transaction cap (0 = unlimited, the default)
     */
    public long getTxMaterializedObjectsCap() {
        return txMaterializedObjectsCap;
    }

    public boolean isEnableForeignKeys() {
        return enableForeignKeys;
    }

    /** The builder of {@link XdStorageConfig}: fluent setters, every knob at its documented
     * default, and loud validation of illegal values at set-time. */
    public static final class Builder {
        /** A builder with every knob at its documented default. */
        public Builder() {
        }

        private boolean durableWrites = false;
        private boolean acceptMissingEofSentinel = false;
        private boolean daemonWorkerThreads = false;
        private boolean registerShutdownHook = false;
        private int workerPoolSize = 15;
        private long defaultTransactionTimeoutMillis = 3 * 1000L;
        private XdStorageIsolationLevel isolationLevel = XdStorageIsolationLevel.SNAPSHOT;
        private boolean journalWrites = true;   // W-3: journal is the default write path
        private boolean indexJournalWrites = true;   // W-4a: index journaling is the default write path (task 32; green soak 808/0, the W-3 flip protocol)
        private long journalCheckpointBytes = 16L * 1024 * 1024;
        private long txMaterializedObjectsCap = 0;
        private boolean enableForeignKeys = true;

        /** Fsync the resource file before the atomic rename and the parent directory after it (power-loss safe; measured ~8x write cost).
         *
         * @param value {@code true} to enable
         * @return this builder, for call chaining
         */
        public Builder durableWrites(final boolean value) {
            this.durableWrites = value;
            return this;
        }

        /** Open even when resource files lack the EOF sentinel (legacy/pre-sentinel folders); the first commit rewrites every touched file.
         *
         * @param value {@code true} to enable
         * @return this builder, for call chaining
         */
        public Builder acceptMissingEofSentinel(final boolean value) {
            this.acceptMissingEofSentinel = value;
            return this;
        }

        /** Worker pool threads become daemon, so a forgotten {@code shutdown()} cannot block JVM exit.
         *
         * @param value {@code true} to enable
         * @return this builder, for call chaining
         */
        public Builder daemonWorkerThreads(final boolean value) {
            this.daemonWorkerThreads = value;
            return this;
        }

        /** Register a best-effort {@code shutdown()} on JVM exit (does NOT substitute for daemonWorkerThreads).
         *
         * @param value {@code true} to enable
         * @return this builder, for call chaining
         */
        public Builder registerShutdownHook(final boolean value) {
            this.registerShutdownHook = value;
            return this;
        }

        /** Internal worker pool size (default 15); must be positive.
         *
         * @param value pool size
         * @return this builder, for call chaining
         * @throws IllegalArgumentException when {@code value <= 0}
         */
        public Builder workerPoolSize(final int value) {
            if (value <= 0)
                throw new IllegalArgumentException("workerPoolSize must be positive");
            this.workerPoolSize = value;
            return this;
        }

        /** Timeout (ms) for the no-arg {@code beginTransaction()} (default 3000); must be positive.
         *
         * @param value timeout in milliseconds
         * @return this builder, for call chaining
         * @throws IllegalArgumentException when {@code value <= 0}
         */
        public Builder defaultTransactionTimeoutMillis(final long value) {
            if (value <= 0)
                throw new IllegalArgumentException("defaultTransactionTimeoutMillis must be positive");
            this.defaultTransactionTimeoutMillis = value;
            return this;
        }

        /** MVCC isolation for the storage's resource caches (default SNAPSHOT); must be non-null.
         *
         * @param value isolation level
         * @return this builder, for call chaining
         * @throws IllegalArgumentException when {@code value} is {@code null}
         */
        public Builder isolationLevel(final XdStorageIsolationLevel value) {
            if (value == null)
                throw new IllegalArgumentException("isolationLevel cannot be null");
            this.isolationLevel = value;
            return this;
        }

        /** Append-only commit journal for data/reference resources (YAML format only; default ON — W-3: the journal is the default write path).
         *
         * @param value {@code true} to enable
         * @return this builder, for call chaining
         */
        public Builder journalWrites(final boolean value) {
            this.journalWrites = value;
            return this;
        }

        /** W-4a: journal the hash id-index resource (docs/tasks/2.1.0/32).
         *
         * @param value {@code true} to enable (default)
         * @return this builder, for call chaining
         */
        public Builder indexJournalWrites(final boolean value) {
            this.indexJournalWrites = value;
            return this;
        }

        /** Journal size (bytes) above which a checkpoint (full base rewrite + journal reset) runs after a commit (default 16 MB); must be positive.
         *
         * @param value threshold in bytes
         * @return this builder, for call chaining
         * @throws IllegalArgumentException when {@code value <= 0}
         */
        public Builder journalCheckpointBytes(final long value) {
            if (value <= 0)
                throw new IllegalArgumentException("journalCheckpointBytes must be positive");
            this.journalCheckpointBytes = value;
            return this;
        }

        /** Per-transaction materialization budget (default 0 = unlimited); over the cap a read fails loudly instead of growing the heap. Must be >= 0.
         *
         * @param value the cap (0 = unlimited)
         * @return this builder, for call chaining
         * @throws IllegalArgumentException when {@code value < 0}
         */
        public Builder txMaterializedObjectsCap(final long value) {
            if (value < 0)
                throw new IllegalArgumentException("txMaterializedObjectsCap must be >= 0");
            this.txMaterializedObjectsCap = value;
            return this;
        }

        public Builder setEnableForeignKeys(boolean enableForeignKeys) {
            this.enableForeignKeys = enableForeignKeys;
            return this;
        }

        /** Builds the immutable configuration.
         *
         * @return the built configuration
         */
        public XdStorageConfig build() {
            return new XdStorageConfig(this);
        }
    }
}
