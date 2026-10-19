package com.example.transfer.mock.provider;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.example.transfer.mock.provider.MockProviderDtos.PaymentResponse;
import com.example.transfer.mock.provider.MockProviderDtos.StatsResponse;

@Repository
public class MockProviderRepository {

    private final JdbcTemplate jdbc;

    public MockProviderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<PaymentResponse> find(String reference) {
        List<PaymentResponse> results = jdbc.query("""
                SELECT operation_reference, status, committed
                FROM mock_provider_payments
                WHERE operation_reference = ?
                """,
                (rs, row) -> new PaymentResponse(
                        rs.getString("operation_reference"),
                        rs.getString("status"),
                        rs.getBoolean("committed")
                ),
                reference
        );

        return results.stream().findFirst();
    }

    public StatsResponse stats() {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM mock_provider_payments
                WHERE committed = TRUE
                """,
                Long.class
        );

        return new StatsResponse(count == null ? 0 : count);
    }
}
