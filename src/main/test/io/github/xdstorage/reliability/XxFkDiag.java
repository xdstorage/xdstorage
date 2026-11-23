package io.github.xdstorage.reliability;

import io.github.xdstorage.*;
import io.github.xdstorage.entities.foreignkeys.*;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import io.github.xdstorage.serialization.FilesFormat;
import java.nio.file.*;
import java.util.*;

public class XxFkDiag {
    public static void main(String[] a) throws Exception {
        String root = "./target/fkdiag-" + System.nanoTime();
        IXdFileStorage s = XdStorageProvider.newOrGetFileStorage("fkd-" + System.nanoTime(), root, 128, FilesFormat
                .YAML);
        Parent parent = new Parent();
        parent.setId("p0");
        Child c1 = new Child();
        c1.setId("p0-c0");
        c1.setParent(parent);
        parent.addChild(c1);
        IXdStorageTransaction tx = s.beginTransaction(60000L);
        s.save(parent, tx);
        s.commitTransaction(tx);
        System.out.println("[DIAG] save committed");

        // has() + load() in a fresh tx
        IXdStorageTransaction t2 = s.beginTransaction(60000L);
        System.out.println("[DIAG] has=" + s.has(Parent.class, "p0", t2));
        Parent loaded = s.load(Parent.class, "p0", t2);
        System.out.println("[DIAG] load=" + (loaded == null ? "NULL" : "ok children=" + loaded.getChildren().size()));
        s.commitTransaction(t2);

        // the file truth
        Files.walk(Paths.get(root)).filter(p -> p.toString().endsWith(".yaml")).forEach(p -> {
            try {
                String c = new String(Files.readAllBytes(p), "UTF-8");
                boolean interesting = c.contains("'p0'") || p.toString().contains("Parent");
                if (interesting) {
                    System.out.println("[FILE] " + p);
                }
            } catch (Exception ignored) {}
        });
        // the index content
        Path idx = Paths.get(root, "indexes", "Parent", "index.yaml");
        if (Files.exists(idx)) {
            System.out.println("[ID-INDEX]");
            for (String line : Files.readAllLines(idx)) System.out.println("  " + line.replace("\r", ""));
        } else {
            System.out.println("[ID-INDEX] MISSING: " + idx);
        }
        // the parent fragment
        Path frag = Paths.get(root, "data", "Parent", "Parent-p0.yaml");
        System.out.println("[FRAGMENT exists=" + Files.exists(frag) + "]");
        if (Files.exists(frag)) {
            for (String line : Files.readAllLines(frag)) System.out.println("  " + line.replace("\r", ""));
        }
        s.shutdown();
    }
}
