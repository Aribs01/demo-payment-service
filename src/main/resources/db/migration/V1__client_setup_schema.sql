
-- =====================================================
-- CLIENT SETUP SCHEMA
-- =====================================================

CREATE TABLE clients (
    id UUID PRIMARY KEY,
    name VARCHAR(150) NOT NULL,
    api_key_hash VARCHAR(255) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE client_accounts (
    id UUID PRIMARY KEY,
    client_id UUID NOT NULL REFERENCES clients(id),
    account_number VARCHAR(50) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_client_account
        UNIQUE (client_id, account_number)
);

CREATE INDEX idx_client_accounts_client
    ON client_accounts(client_id);


-- =====================================================
-- TRANSFERS
-- =====================================================

CREATE TABLE transfers (
    id UUID PRIMARY KEY,
    client_id UUID NOT NULL REFERENCES clients(id),

    external_reference VARCHAR(150) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,

    source_account VARCHAR(50) NOT NULL,
    destination_account VARCHAR(50) NOT NULL,
    destination_bank_code VARCHAR(20) NOT NULL,

    amount NUMERIC(19,2) NOT NULL
        CHECK (amount > 0),

    currency VARCHAR(3) NOT NULL DEFAULT 'NGN'
        CHECK (currency = 'NGN'),

    narration VARCHAR(500),

    status VARCHAR(40) NOT NULL DEFAULT 'ACCEPTED',
    reconciliation_required BOOLEAN NOT NULL DEFAULT FALSE,

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_client_external_reference
        UNIQUE (client_id, external_reference),

    CONSTRAINT fk_transfers_source_account
        FOREIGN KEY (client_id, source_account)
        REFERENCES client_accounts(client_id, account_number)
        ON UPDATE RESTRICT
        ON DELETE RESTRICT
);

CREATE INDEX idx_transfers_status
    ON transfers(status);


-- =====================================================
-- FINANCIAL OPERATIONS
-- =====================================================

CREATE TABLE financial_operations (
    id UUID PRIMARY KEY,
    transfer_id UUID NOT NULL REFERENCES transfers(id),

    operation_type VARCHAR(20) NOT NULL
        CHECK (
            operation_type IN ('DEBIT', 'PAYMENT', 'REFUND')
        ),

    operation_reference VARCHAR(150) NOT NULL UNIQUE,

    status VARCHAR(40) NOT NULL DEFAULT 'PENDING',

    external_transaction_id VARCHAR(150),

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_transfer_operation_type
        UNIQUE (transfer_id, operation_type)
);

CREATE INDEX idx_financial_operations_transfer
    ON financial_operations(transfer_id);


-- =====================================================
-- APPEND-ONLY TRANSFER AUDIT
-- =====================================================

CREATE TABLE transfer_audit (
    id UUID PRIMARY KEY,
    transfer_id UUID NOT NULL REFERENCES transfers(id),

    event_type VARCHAR(100) NOT NULL,
    previous_status VARCHAR(40),
    new_status VARCHAR(40),

    details TEXT,

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_transfer_audit_transfer
    ON transfer_audit(transfer_id, created_at);


-- =====================================================
-- TRANSACTIONAL OUTBOX
-- =====================================================

CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    aggregate_id UUID NOT NULL REFERENCES transfers(id),

    event_type VARCHAR(100) NOT NULL,
    payload JSONB NOT NULL,

    published_at TIMESTAMPTZ,
    attempts INTEGER NOT NULL DEFAULT 0,

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_outbox_pending
    ON outbox_events(created_at)
    WHERE published_at IS NULL;


-- =====================================================
-- PROVIDER CALLBACK HISTORY
-- =====================================================

CREATE TABLE provider_events (
    id UUID PRIMARY KEY,

    provider_event_id VARCHAR(150) NOT NULL UNIQUE,
    operation_reference VARCHAR(150) NOT NULL,

    reported_status VARCHAR(40) NOT NULL,
    payload JSONB NOT NULL,

    processed_at TIMESTAMPTZ,

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_provider_events_operation
    ON provider_events(operation_reference);


-- =====================================================
-- MOCK FINERACT ACCOUNTS
-- =====================================================

CREATE TABLE mock_fineract_accounts (
    id UUID PRIMARY KEY,

    account_number VARCHAR(50) NOT NULL UNIQUE,

    balance NUMERIC(19,2) NOT NULL DEFAULT 0,
    held_amount NUMERIC(19,2) NOT NULL DEFAULT 0,

    currency VARCHAR(3) NOT NULL DEFAULT 'NGN',

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_mock_account_balance
        CHECK (balance >= 0),

    CONSTRAINT chk_mock_held_amount
        CHECK (held_amount >= 0),

    CONSTRAINT chk_mock_available_balance
        CHECK (held_amount <= balance)
);


-- =====================================================
-- MOCK FINERACT OPERATIONS
-- =====================================================

CREATE TABLE mock_fineract_operations (
    id UUID PRIMARY KEY,

    operation_reference VARCHAR(150) NOT NULL UNIQUE,

    operation_type VARCHAR(20) NOT NULL
        CHECK (operation_type IN ('DEBIT', 'REFUND')),

    request_hash VARCHAR(64) NOT NULL,

    account_number VARCHAR(50) NOT NULL,
    amount NUMERIC(19,2) NOT NULL CHECK (amount > 0),

    status VARCHAR(40) NOT NULL,

    committed BOOLEAN NOT NULL DEFAULT FALSE,

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_mock_fineract_account
    ON mock_fineract_operations(account_number);


-- =====================================================
-- MOCK PAYMENT PROVIDER
-- =====================================================

CREATE TABLE mock_provider_payments (
    id UUID PRIMARY KEY,

    operation_reference VARCHAR(150) NOT NULL UNIQUE,
    request_hash VARCHAR(64) NOT NULL,

    destination_account VARCHAR(50) NOT NULL,
    destination_bank_code VARCHAR(20) NOT NULL,

    amount NUMERIC(19,2) NOT NULL CHECK (amount > 0),
    currency VARCHAR(3) NOT NULL DEFAULT 'NGN',

    status VARCHAR(40) NOT NULL,
    committed BOOLEAN NOT NULL DEFAULT FALSE,

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
