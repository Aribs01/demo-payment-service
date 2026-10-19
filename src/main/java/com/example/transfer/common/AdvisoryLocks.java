package com.example.transfer.common;

import org.springframework.jdbc.core.JdbcTemplate;

public final class AdvisoryLocks {

    private AdvisoryLocks() {
    }

    public static void lock(JdbcTemplate jdbc, String key) {
        jdbc.queryForList(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                key
        );
    }
}
