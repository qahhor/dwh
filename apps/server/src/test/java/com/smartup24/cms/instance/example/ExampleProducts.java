package com.smartup24.cms.instance.example;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Products of the reference list written straight into their table, for the tests of the documents that name them. */
final class ExampleProducts {

    private ExampleProducts() {}

    /** A new live product with a code of its own; returns its id. */
    static long insert(JdbcClient jdbc, long author) {
        String code = "p-" + UUID.randomUUID().toString().substring(0, 12);
        return jdbc.sql("insert into ex_products (code, name, unit, created_by, modified_by)"
                        + " values (:code, :name, 'pcs', :author, :author) returning id")
                .param("code", code)
                .param("name", "Product " + code)
                .param("author", author)
                .query(Long.class)
                .single();
    }
}
