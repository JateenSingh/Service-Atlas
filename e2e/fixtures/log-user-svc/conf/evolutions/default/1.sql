# Users schema

# --- !Ups
CREATE TABLE users (
    id    VARCHAR(36) PRIMARY KEY,
    email VARCHAR(255) NOT NULL UNIQUE
);

# --- !Downs
DROP TABLE users;
