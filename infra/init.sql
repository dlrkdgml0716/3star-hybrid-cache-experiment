CREATE TABLE lab_item (
    id BIGINT PRIMARY KEY,
    version BIGINT NOT NULL,
    item_value VARCHAR(255) NOT NULL
);
INSERT INTO lab_item (id, version, item_value) VALUES
    (1, 0, 'sample-1');
