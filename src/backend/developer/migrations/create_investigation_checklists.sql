CREATE TABLE investigation_checklists (
    report_id uuid PRIMARY KEY REFERENCES reports(id),
    draft_steps text[] NOT NULL DEFAULT '{}',
    draft_revision bigint NOT NULL DEFAULT 0 CHECK (draft_revision >= 0),
    shared_steps text[] NOT NULL DEFAULT '{}'
);
