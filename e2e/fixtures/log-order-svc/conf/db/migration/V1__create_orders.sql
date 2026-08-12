CREATE TABLE orders (
    id          VARCHAR(36) PRIMARY KEY,
    customer_id VARCHAR(36) NOT NULL,
    status      VARCHAR(20) NOT NULL,
    created_at  TIMESTAMP   NOT NULL
);
