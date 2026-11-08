CREATE TABLE developer_private_notes (
    report_id uuid PRIMARY KEY REFERENCES reports(id),
    body text NOT NULL DEFAULT '',
    notes_revision bigint NOT NULL DEFAULT 0 CHECK (notes_revision >= 0)
);
