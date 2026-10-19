INSERT INTO clients (
    id,
    name,
    api_key_hash
) VALUES
(
    '11111111-1111-1111-1111-111111111111',
    'Mock Client 1',
    'key-hash-1'
),
(
    '22222222-2222-2222-2222-222222222222',
    'Mock Client 2',
    'key-hash-2'
);

INSERT INTO client_accounts (
    id,
    client_id,
    account_number
) VALUES
(
    'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1',
    '11111111-1111-1111-1111-111111111111',
    '1001610327'
),
(
    'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2',
    '22222222-2222-2222-2222-222222222222',
    '0123456789'
);

INSERT INTO mock_fineract_accounts (
    id,
    account_number,
    balance,
    held_amount
) VALUES
(
    'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb1',
    '1001610327',
    200000.00,
    60000.00
),
(
    'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb2',
    '0123456789',
    100000.00,
    0.00
);
