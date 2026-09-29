package com.smartup24.cms.instance.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartup24.cms.instance.support.TestDatabases;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Plan 10/10, item 3.7: every foreign key has an index that starts with its columns. Without one, deleting or
 * updating the referenced row scans the whole referencing table under a lock, and joins from the parent side do the
 * same.
 */
class ForeignKeyIndexTest {

    /** Foreign keys whose columns are not the leading columns of any index on the referencing table. */
    static final String FOREIGN_KEYS_WITHOUT_INDEX = """
            select c.conrelid::regclass::text || '(' || (
                       select string_agg(a.attname, ', ' order by k.n)
                       from unnest(c.conkey) with ordinality as k(attnum, n)
                       join pg_attribute a on a.attrelid = c.conrelid and a.attnum = k.attnum
                   ) || ') -> ' || c.confrelid::regclass::text as foreign_key
            from pg_constraint c
            where c.contype = 'f'
              and c.connamespace = 'public'::regnamespace
              and not exists (
                  select 1
                  from pg_index i
                  where i.indrelid = c.conrelid
                    and i.indisvalid
                    and i.indnkeyatts >= cardinality(c.conkey)
                    and (select array_agg(x order by x) from unnest((i.indkey::int2[])[0:cardinality(c.conkey) - 1]) x)
                        = (select array_agg(x order by x) from unnest(c.conkey) x))
            order by 1
            """;

    @Test
    @DisplayName("3.7: no foreign key of the OLTP schema is left without an index")
    void everyForeignKeyHasAnIndex() {
        DataSource database = TestDatabases.migratedCopy("fk_index");
        List<String> missing = JdbcClient.create(database)
                .sql(FOREIGN_KEYS_WITHOUT_INDEX)
                .query(String.class)
                .list();
        assertThat(missing).as("foreign keys without an index").isEmpty();
    }
}
