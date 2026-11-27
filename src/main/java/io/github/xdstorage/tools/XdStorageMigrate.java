package io.github.xdstorage.tools;

import io.github.xdstorage.IXdFileStorage;
import io.github.xdstorage.XdStorageConfig;
import io.github.xdstorage.XdStorageProvider;
import io.github.xdstorage.utils.XdStorageFormatMeta;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * Migration CLI: applies the v1-to-v2 (and format) migration to a storage folder.
 *
 * <p>Usage:
 * <ul>
 *   <li>{@code java -cp xdstorage.jar io.github.xdstorage.tools.XdStorageMigrate <folder> --dry-run}</li>
 *   <li>{@code java -cp xdstorage.jar io.github.xdstorage.tools.XdStorageMigrate <folder> --script builtin:v1-to-v2}</li>
 * </ul>
 * <p>
 * The builtin {@code v1-to-v2} script:
 * <ol>
 *   <li>Opens the folder with {@code acceptMissingEofSentinel(true)} (legacy YAML reader)</li>
 *   <li>Writes a no-op record to trigger the EOF sentinel rewrite on all touched resources</li>
 *   <li>Renames all {@code *.json} files to {@code *.xml} (1.x JSON→XML recipe from EMBEDDING)</li>
 *   <li>Upgrades the format marker to v3 (layout migration to indexes/)</li>
 * </ol>
 * <p>
 * {@code --dry-run} prints the migration plan without applying changes.
 * Exit codes: 0 = OK; 1 = migration needed (dry-run) or differences found;
 * 2 = usage error; 3 = migration failed.
 *
 * @see <a href="docs/tasks/2.2.0/17-migration-schema-diff.md">task 17</a>
 */
public class XdStorageMigrate {

    private XdStorageMigrate() {
    }

    public static void main(String[] args) {
        try {
            if (args.length < 1) {
                usage();
                System.exit(2);
            }

            final Path folder = Paths.get(args[0]);
            final boolean dryRun = "--dry-run".equals(args[1]);

            if (dryRun) {
                printMigrationPlan(folder);
                System.exit(1);
            }

            final String script = "--script".equals(args[1]) ? args[2] : "builtin:v1-to-v2";
            if ("builtin:v1-to-v2".equals(script)) {
                doMigrate(folder);
            } else {
                System.err.println("Unknown script: " + script);
                System.err.println("Use --script builtin:v1-to-v2 for the builtin migration.");
                System.exit(2);
            }
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(3);
        }
    }

    private static void usage() {
        System.err.println("Usage:");
        System.err.println("  java -cp xdstorage.jar io.github.xdstorage.tools.XdStorageMigrate <folder> [--dry-run] [--script builtin:v1-to-v2]");
    }

    static void printMigrationPlan(Path folder) {
        final File folderFile = folder.toFile();
        if (!folderFile.exists() || !folderFile.isDirectory()) {
            System.err.println("Error: folder does not exist: " + folder);
            System.exit(3);
        }

        final File metaFile = new File(folderFile, ".xdstorage.meta");
        System.out.println("Migration plan for: " + folder);
        System.out.println("  format version: " + (metaFile.isFile() ? detectFormatVersion(metaFile) : "v1 (no marker)"));

        if (metaFile.isFile()) {
            try {
                final java.util.Properties meta = new java.util.Properties();
                try (java.io.FileInputStream in = new java.io.FileInputStream(metaFile)) {
                    meta.load(in);
                }
                final String raw = meta.getProperty("formatVersion");
                if (raw == null) {
                    System.out.println("  format version: v1 (marker present but no formatVersion key)");
                }
            } catch (Exception e) {
                System.out.println("  format version: unreadable marker");
            }
        }

        final File[] jsonFiles = folderFile.listFiles((dir, name) -> name.endsWith(".json"));
        if (jsonFiles != null && jsonFiles.length > 0) {
            System.out.println("  JSON->XML rename: " + jsonFiles.length + " file(s) to rename");
            for (File f : jsonFiles) {
                System.out.println("    " + f.getName());
            }
        }

        final File indexesDir = new File(folderFile, "indexes");
        if (!indexesDir.exists()) {
            System.out.println("  layout migration: indexes/ directory needs to be created");
        } else {
            System.out.println("  layout migration: indexes/ already exists (v3 layout)");
        }
    }

    private static String detectFormatVersion(File metaFile) {
        try {
            final java.util.Properties meta = new java.util.Properties();
            try (java.io.FileInputStream in = new java.io.FileInputStream(metaFile)) {
                meta.load(in);
            }
            final String raw = meta.getProperty("formatVersion");
            return raw != null ? "v" + raw : "unknown";
        } catch (Exception e) {
            return "unreadable";
        }
    }

    private static void doMigrate(Path folder) {
        final File folderFile = folder.toFile();
        if (!folderFile.exists() || !folderFile.isDirectory()) {
            System.err.println("Error: folder does not exist: " + folder);
            System.exit(3);
        }

        final XdStorageConfig config = XdStorageConfig.builder()
            .acceptMissingEofSentinel(true)
            .build();
        final IXdFileStorage storage = XdStorageProvider.newOrGetFileStorage(
            "migrate-tool", folderFile.getAbsolutePath(), config);

        try {
            storage.checkpoint();
            renameJsonToXml(folderFile);
            XdStorageFormatMeta.upgradeFormatMarkerToCurrent(folderFile, "migrate-tool", "YAML");
            System.out.println("Migration completed for: " + folder);
        } finally {
            storage.shutdown();
        }
    }

    private static void renameJsonToXml(File folder) {
        final File[] files = folder.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isFile() && f.getName().endsWith(".json")) {
                final Path target = f.toPath().resolveSibling(
                    f.getName().substring(0, f.getName().length() - 5) + ".xml");
                try {
                    Files.move(f.toPath(), target, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    System.err.println("WARN: cannot rename " + f.getName() + ": " + e.getMessage());
                }
            }
        }
    }
}
