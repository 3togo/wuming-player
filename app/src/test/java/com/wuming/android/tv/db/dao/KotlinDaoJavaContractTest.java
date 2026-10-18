package com.wuming.android.tv.db.dao;

import static org.junit.Assert.*;

import com.wuming.android.tv.db.SearchHistory;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/** Java overrides must remain valid when the DAO base and default methods are Kotlin. */
public class KotlinDaoJavaContractTest {
    @Test public void batchUpsertUpdatesOnlyConflictsThroughOriginalJavaOverrides() {
        StringDao dao = new StringDao();
        dao.insertOrUpdate(List.of("existing", "new", "another-existing"));
        assertEquals(List.of("existing", "another-existing"), dao.updated);
        dao.insertOrUpdate("existing");
        assertEquals(List.of("existing", "another-existing", "existing"), dao.updated);
    }

    @Test public void boxedInsertReturnAndInvariantListSignaturesRemainAvailable() throws Exception {
        assertEquals(Long.class, BaseDao.class.getMethod("insert", Object.class).getReturnType());
        assertEquals("java.util.List<T>", BaseDao.class.getMethod("update", List.class)
                .getGenericParameterTypes()[0].getTypeName());
    }

    @Test public void searchHistoryDefaultImplementationCanBeCalledByGeneratedJava() {
        SearchDao dao = new SearchDao();
        dao.insertOrUpdate("query");
        assertEquals("query", dao.saved.getKeyword());
        assertEquals(1, dao.saved.getFrequency());
        SearchHistory original = dao.saved;
        dao.insertOrUpdate("query");
        assertSame(original, dao.saved);
        assertEquals(2, dao.saved.getFrequency());
    }

    private static class StringDao extends BaseDao<String> {
        final List<String> updated = new ArrayList<>();
        @Override public Long insert(String value) { return -1L; }
        @Override public List<Long> insert(List<String> values) { return List.of(-1L, 2L, -1L); }
        @Override public void update(String value) { updated.add(value); }
        @Override public void update(List<String> values) { updated.addAll(values); }
    }

    private static class SearchDao implements SearchHistoryDao {
        SearchHistory saved;
        @Override public long insert(SearchHistory history) { saved = history; return 1L; }
        @Override public void update(SearchHistory history) { saved = history; }
        @Override public void delete(SearchHistory history) { saved = null; }
        @Override public void deleteAll() { saved = null; }
        @Override public List<SearchHistory> getRecent(int limit) { return List.of(); }
        @Override public List<SearchHistory> getFrequent(int limit) { return List.of(); }
        @Override public SearchHistory findByKeyword(String keyword) { return saved; }
    }
}
