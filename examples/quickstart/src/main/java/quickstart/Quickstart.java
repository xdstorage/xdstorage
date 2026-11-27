package quickstart;

import io.github.xdstorage.IXdFileStorage;
import io.github.xdstorage.XdStorageProvider;
import io.github.xdstorage.search.query.IXdStorageCriterionOperator;
import io.github.xdstorage.search.query.XdStoragePrimaryCriterion;
import io.github.xdstorage.search.query.XdStorageRange;
import io.github.xdstorage.search.query.XdStorageSearchQuery;
import io.github.xdstorage.transaction.IXdStorageTransaction;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The lifecycle of {@link QuickstartTest} as a plain {@code main} — for running the demo
 * where there is neither a JDK nor Maven: on a JRE, in a container (README § Run it on a
 * JRE). The engine needs none of them when the wrapper classes were generated while this
 * application was built.
 *
 * <p>Prints one line, {@value #OK}, when the whole story held; any failure ends the process
 * with a non-zero status. The storage is always shut down — the engine's worker threads are
 * not daemons by design.</p>
 */
public final class Quickstart {

    /** The line a successful run prints. */
    public static final String OK = "QUICKSTART-OK";

    /** {@code BETWEEN 300..700} on the balance index — asked before AND after the reopen. */
    private static final XdStorageSearchQuery MID_BALANCES = new XdStorageSearchQuery(
            new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.BETWEEN, new XdStorageRange(300L, 700L)),
            null);

    private Quickstart() {
    }

    /**
     * Runs the lifecycle once.
     *
     * @param args {@code [storage folder]} — a temporary directory by default
     * @throws Exception any failure of the lifecycle — left uncaught on purpose
     */
    public static void main(final String[] args) throws Exception {
        final String storageName = "quickstart-" + System.nanoTime();
        final String folder = args.length > 0 ? args[0] : "xdstorage-quickstart-db";

        // --- 1. open: a storage is one folder, registered by name, locked while open ------
        IXdFileStorage storage = XdStorageProvider.newOrGetFileStorage(storageName, folder, 64);
        try {
            // --- 2. save two classes in one transaction ----------------------------------
            final IXdStorageTransaction tx = storage.beginTransaction(60_000L);
            try {
                storage.save(Arrays.asList(
                        new Customer("c-1", "Alice", "DE"),
                        new Customer("c-2", "Bruno", "FR"),
                        new Customer("c-3", "Clara", "DE"),
                        new Customer("c-4", "Dana", "US"),
                        new Customer("c-5", "Emil", "DE")), tx);
                for (int i = 1; i <= 10; i++) {
                    storage.save(new Account("a-" + i, "owner-" + i, (long) i * 100), tx);
                }
                storage.commitTransaction(tx);
            } catch (final RuntimeException e) {
                storage.rollbackTransaction(tx);
                throw e;
            }

            // --- 3. query on the secondary index ------------------------------------------
            check("EQUALS hits the by_country index", queryCountry(storage, "DE"),
                    Arrays.asList("c-1", "c-3", "c-5"));

            // --- 4. a RANGE on the ordered index (inclusive bounds) ----------------------
            // Contract note: WITHOUT shaping, the result set is unordered — sort when the
            // walk order matters.
            check("BETWEEN is inclusive on both bounds", sorted(accountIds(storage, MID_BALANCES)),
                    Arrays.asList("a-3", "a-4", "a-5", "a-6", "a-7"));

            // --- 5. shape it: ORDER BY DESC + LIMIT (the walk stops at the page end) -----
            final XdStorageSearchQuery topPage = new XdStorageSearchQuery(
                    new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.GTE, 300L), null)
                    .orderByPrimary(true)
                    .limit(3);
            check("DESC + LIMIT returns the top page in key order", accountIds(storage, topPage),
                    Arrays.asList("a-10", "a-9", "a-8"));

            // --- 6. count without materializing the result -------------------------------
            final IXdStorageTransaction countTx = storage.beginTransaction(60_000L);
            try {
                check("count(q) equals load(q).size() on the same snapshot",
                        storage.count(Account.class, "by_balance", MID_BALANCES, countTx), 5L);
                storage.commitTransaction(countTx);
            } catch (final RuntimeException e) {
                storage.rollbackTransaction(countTx);
                throw e;
            }
        } finally {
            // --- 7a. shutdown ----------------------------------------------------------
            storage.shutdown();
        }

        // --- 7b. reopen the same folder from disk ----------------------------------------

        storage = XdStorageProvider.newOrGetFileStorage(storageName, folder, 64);
        try {
            final IXdStorageTransaction reopenTx = storage.beginTransaction(60_000L);
            try {
                check("the object survived the close/open cycle",
                        storage.load(Customer.class, "c-3", reopenTx).getName(), "Clara");
                check("the index answered from persisted state", queryCountry(storage, "DE"),
                        Arrays.asList("c-1", "c-3", "c-5"));
                check("BETWEEN after the reopen: keys restored to the indexed field's type",
                        sorted(accountIds(storage, MID_BALANCES)), Arrays.asList("a-3", "a-4", "a-5", "a-6", "a-7"));
                storage.commitTransaction(reopenTx);
            } catch (final RuntimeException e) {
                storage.rollbackTransaction(reopenTx);
                throw e;
            }
        } finally {
            // --- 8. shutdown ----------------------------------------------------------
            storage.shutdown();
        }
        System.out.println(OK);
    }

    private static void check(final String what, final Object actual, final Object expected) {
        if (!expected.equals(actual)) {
            throw new IllegalStateException(what + " — expected " + expected + " but got " + actual);
        }
    }

    private static List<String> sorted(final List<String> ids) {
        return ids.stream().sorted().collect(Collectors.toList());
    }

    private static List<String> queryCountry(final IXdFileStorage st, final String country) throws Exception {
        final IXdStorageTransaction tx = st.beginTransaction(60_000L);
        try {
            final XdStorageSearchQuery query = new XdStorageSearchQuery(
                    new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.EQUALS, country), null);
            final List<String> ids = st.load(Customer.class, "by_country", query, tx)
                    .stream().map(Customer::getId).sorted().collect(Collectors.toList());
            st.commitTransaction(tx);
            return ids;
        } catch (final RuntimeException e) {
            st.rollbackTransaction(tx);
            throw e;
        }
    }

    private static List<String> accountIds(final IXdFileStorage st, final XdStorageSearchQuery query)
            throws Exception {
        final IXdStorageTransaction tx = st.beginTransaction(60_000L);
        try {
            final List<String> ids = st.load(Account.class, "by_balance", query, tx)
                    .stream().map(Account::getId).collect(Collectors.toList());
            st.commitTransaction(tx);
            return ids;
        } catch (final RuntimeException e) {
            st.rollbackTransaction(tx);
            throw e;
        }
    }
}
