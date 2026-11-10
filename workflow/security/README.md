# Release security checks

Added explicit secret and dependency checks for team-plan section 8. Run them through
the repository verification entry point, or use `python workflow/security/check.py`
for a focused security check. A missing tool, network failure, checksum mismatch or
scanner finding fails verification rather than being reported as a pass.

Use pinned Gitleaks 8.30.1 for redacted detection over the tracked working tree,
including staged additions. Copy only tracked paths into a disposable snapshot;
private untracked notes and build outputs are not exported. This checks the current
candidate, not every historical commit. No scanner proves that all secrets are absent.

Use pinned OSV-Scanner 2.7.1 for both committed npm lockfiles and a generated inventory
of resolved runtime/test Maven dependencies. Export the actual selected versions
using `dependencies.gradle`, without changing dependency resolution. Keep generated
inventory under the ignored backend build directory. Any reported advisory blocks
this check and needs review; no blanket vulnerability suppression is configured.

Download fixed Linux/Windows x64 releases into the user's external tool cache and
verify the upstream SHA-256 before extraction/execution. Use pinned actionlint 1.7.12
for Actions syntax checks. Disabled optional shellcheck/pyflakes integrations because
those binaries are not part of this runner; actionlint still validates the workflows.

## Acknowledgements and limits

Used the official [Gitleaks CLI](https://github.com/gitleaks/gitleaks),
[OSV source-scanning documentation](https://google.github.io/osv-scanner/usage/scan-source),
[OSV custom inventory format](https://google.github.io/osv-scanner/supported-languages-and-lockfiles/#custom-lockfiles)
and [actionlint](https://github.com/rhysd/actionlint). Used their published release
checksums for the pinned downloads. These are external development tools, not code
carried over from previous MPs. Scans do not replace authorization, prompt-security,
provider-quality or deployed environment checks.
