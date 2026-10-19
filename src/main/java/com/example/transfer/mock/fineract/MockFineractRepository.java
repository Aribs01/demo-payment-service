package com.example.transfer.mock.fineract;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.example.transfer.mock.fineract.MockFineractDtos.AccountResponse;
import com.example.transfer.mock.fineract.MockFineractDtos.OperationResponse;
import com.example.transfer.mock.fineract.MockFineractDtos.StatsResponse;

@Repository
public class MockFineractRepository {

    private final JdbcTemplate jdbc;

    public MockFineractRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<OperationResponse> findOperation(String reference) {
        List<OperationResponse> results = jdbc.query("""
                SELECT operation_reference, operation_type, status, committed
                FROM mock_fineract_operations
                WHERE operation_reference = ?
                """,
                (rs, row) -> new OperationResponse(
                        rs.getString("operation_reference"),
                        rs.getString("operation_type"),
                        rs.getString("status"),
                        rs.getBoolean("committed")
                ),
                reference
        );

        return results.stream().findFirst();
    }

    public Optional<AccountResponse> findAccount(String accountNumber) {
        List<AccountResponse> results = jdbc.query("""
                SELECT account_number, balance, held_amount
                FROM mock_fineract_accounts
                WHERE account_number = ?
                """,
                (rs, row) -> {
                    BigDecimal balance = rs.getBigDecimal("balance");
                    BigDecimal held = rs.getBigDecimal("held_amount");

                    return new AccountResponse(
                            rs.getString("account_number"),
                            balance,
                            held,
                            balance.subtract(held)
                    );
                },
                accountNumber
        );

        return results.stream().findFirst();
    }

    public StatsResponse stats() {
        return new StatsResponse(
                count("DEBIT"),
                count("REFUND")
        );
    }

    private long count(String type) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM mock_fineract_operations
                WHERE operation_type = ? AND committed = TRUE
                """,
                Long.class,
                type
        );

        return count == null ? 0 : count;
    }
}
