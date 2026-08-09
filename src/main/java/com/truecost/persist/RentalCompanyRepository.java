package com.truecost.persist;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class RentalCompanyRepository {

    private final JdbcClient jdbcClient;

    public RentalCompanyRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public int count() {
        return jdbcClient.sql("select count(*) from rental_company").query(Integer.class).single();
    }

    public Optional<Long> findIdByCode(String code) {
        return jdbcClient.sql("select id from rental_company where code = :code")
                .param("code", code)
                .query(Long.class)
                .optional();
    }

    /**
     * Inserts the company if its code is not already present. toll_programs.csv repeats the
     * company code and name on every program row, so the loader calls this once per row and
     * relies on the unique constraint on code to make repeated calls for the same company safe.
     */
    public long upsert(String code, String name) {
        return findIdByCode(code).orElseGet(() -> jdbcClient.sql("""
                        insert into rental_company (code, name) values (:code, :name)
                        returning id
                        """)
                .param("code", code)
                .param("name", name)
                .query(Long.class)
                .single());
    }
}
