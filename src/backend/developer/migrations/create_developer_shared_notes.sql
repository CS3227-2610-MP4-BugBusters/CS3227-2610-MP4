CREATE TABLE developer_shared_note_drafts (
    report_id uuid PRIMARY KEY REFERENCES reports(id),
    text text NOT NULL DEFAULT '',
    draft_revision bigint NOT NULL DEFAULT 0 CHECK (draft_revision >= 0),
    published_note_id uuid REFERENCES report_shared_notes(id)
);
