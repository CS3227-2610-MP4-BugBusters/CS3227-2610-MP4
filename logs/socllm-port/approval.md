# MP4 role port approval

After the independent baseline review confirmed the configured-wiring Red, the
feature owner instructed: "change for specific roles in mp4 port completely for mp4".
This is approval to implement the verified integration baseline and adapt the
shared gateway to MP4's Reporter, Developer and Maintainer roles. It follows the
earlier explicit request to port and integrate the MP3 gateway. No additional
approval is required for that implementation scope.

The port keeps role features and their authorization/context/output validation in
their existing owners' services. Only the Maintainer queue-summary consumer exists
at this baseline; the shared module exposes MP4 role routes for backend consumers
without inventing new Reporter or Developer application endpoints. Live credentials,
provider quota confirmation and deployment activation remain separate checks.

## Technical references

- Date: 2026-11-10 (Asia/Singapore).
- Task: SOC-MP4-PORT; existing change `complete-maintainer-role`, tasks 4.4/5.1.
- Approved test: `src/backend/auth/src/test/java/mp4/platform/MaintainerSocLlmConfigurationTest.java`.
- Approved SHA-256: `26AD66935CCE0B18AA6423B8E1F96E18E0A076AFDC0C791C5DBD5DBACB71EC70`.
- Independent Red review: [baseline-review.md](baseline-review.md).
- Starting revision: `6bdba80519ed30996cf401f6b28dc60fb31f3e75`.
- The owner's explicit request authorizes MP3 implementation reuse for this port,
  overriding the general previous-MP reuse exclusion in `openspec/config.yaml`.
- Broad tasks remain open until their full stated verification criteria pass.
