-- Read-only state snapshot for private_notes_api_test.py. Pass report_id.
SELECT jsonb_build_object(
    'notes', COALESCE(
        (SELECT jsonb_build_object('text', notes.body, 'revision', notes.notes_revision)
         FROM developer_private_notes notes WHERE notes.report_id = report.id),
        jsonb_build_object('text', '', 'revision', 0)
    ),
    'report', jsonb_build_object(
        'id', report.id,
        'reporterId', report.reporter_id,
        'assigneeId', report.assignee_id,
        'status', report.status,
        'version', report.version,
        'currentProposalId', report.current_proposal_id
    ),
    'proposal', jsonb_build_object(
        'id', proposal.id,
        'reportId', proposal.report_id,
        'status', proposal.status,
        'version', proposal.version,
        'contents', proposal.contents
    )
)
FROM reports report
JOIN resolution_proposals proposal
  ON proposal.id = report.current_proposal_id AND proposal.report_id = report.id
WHERE report.id = :'report_id'::uuid;
