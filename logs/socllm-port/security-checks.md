# Gateway dependency and secret checks

The new gateway dependency passed the repository's dependency scan. The pinned
secret scanner also found no leaks. A separate scan included the new untracked
gateway/configuration/test files, so the result is not limited to previously
tracked sources. These checks complement the independent security review; they
do not resolve its transaction and shared-policy findings or prove live provider
compatibility.

## Technical evidence

- Date: 2026-11-10, task SOC-MP4-PORT, starting HEAD
  `6bdba80519ed30996cf401f6b28dc60fb31f3e75`.
- Full repository security command: `python workflow/security/check.py`.
- Exit: 0. Gradle `writeSecurityInventory` passed in 8 seconds and exported
  90 resolved Maven runtime/test dependencies. OSV also inspected the Developer
  and Maintainer npm lockfiles (121 and 124 packages respectively). No issues found.
- Pinned Gitleaks scan inside that command: approximately 2.40 MB of tracked
  working-tree files including staged additions; no leaks found, redacted output.
- Additional parent scan used `workflow/security/tools.py`'s pinned Gitleaks binary
  against a disposable snapshot containing tracked files plus all new files under
  `src/backend/socllm`, new `mp4/socllm` tests, and new platform `*SocLlm*.java`
  configuration/test files. Approximately 2.45 MB scanned; exit 0, no leaks found.
- Scanner flags: `dir --redact=100 --no-banner --exit-code=1`.
- Dependency declaration matched initial candidate `auth/build.gradle` SHA-256:
  `32d60afa01a024e208d55d5ec4a3c3101666f55e69886333c6da9edac77e5257`.
- Findings from independent source review are separately tracked in
  [security-review.md](security-review.md); repairs do not change dependencies.
- No live key values, prompt data, upstream error bodies or application credentials
  were supplied to these checks.
- The repaired-round-1 working tree and new gateway files were rescanned with the
  same snapshot procedure: approximately 2.46 MB, exit 0, no leaks found. The final
  credential-normalization patch has no dependency declaration changes; its source
  safety is covered by the independent source recheck and targeted tests.
- The exact final credential-normalization manifest was also rescanned: all 21 file
  hashes matched, approximately 110.26 KB scanned, exit 0, no leaks found. This final
  scan complements the earlier full working-tree scan; no final dependency changed.
