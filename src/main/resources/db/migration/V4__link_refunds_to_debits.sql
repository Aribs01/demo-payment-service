ALTER TABLE mock_fineract_operations
ADD COLUMN original_debit_reference VARCHAR(150);

CREATE UNIQUE INDEX uq_mock_refund_original_debit
ON mock_fineract_operations(original_debit_reference)
WHERE operation_type = 'REFUND';

ALTER TABLE mock_fineract_operations
ADD CONSTRAINT chk_refund_original_reference
CHECK (
    (operation_type = 'DEBIT'
        AND original_debit_reference IS NULL)
    OR
    (operation_type = 'REFUND'
        AND original_debit_reference IS NOT NULL)
);
