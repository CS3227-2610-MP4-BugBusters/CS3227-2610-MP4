CREATE TABLE developer_private_notes (
    report_id uuid PRIMARY KEY REFERENCES reports(id),
    text text NOT NULL DEFAULT '',
    revision bigint NOT NULL DEFAULT 0 CHECK (revision >= 0)
);
