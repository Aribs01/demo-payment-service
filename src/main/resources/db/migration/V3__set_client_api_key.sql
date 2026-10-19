-- Local development API keys. api_key_hash is lowercase hex SHA-256 of the raw key.
-- Mock Client 1 raw key: mock-secret-key-1
-- Mock Client 2 raw key: mock-secret-key-2

UPDATE clients AS c
SET api_key_hash = encode(
    sha256(convert_to(keys.raw_key, 'UTF8')),
    'hex'
)
FROM (
    VALUES
        ('11111111-1111-1111-1111-111111111111'::uuid, 'mock-secret-key-1'),
        ('22222222-2222-2222-2222-222222222222'::uuid, 'mock-secret-key-2')
) AS keys(id, raw_key)
WHERE c.id = keys.id;
