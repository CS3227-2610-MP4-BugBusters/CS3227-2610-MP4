# Shared gateway alternatives diagnostic — 11 November 2026

## Summary

Two direct synthetic provider diagnostics explored options without setting `reasoning_effort: none`. Neither returned usable content. The first raised the output cap from 384 to 1,024 tokens; the second used a shorter instruction retaining basic untrusted-input and no-action wording at 384 tokens. Both returned HTTP 200, finish reason `length` and empty content. No production configuration or code was changed.

## Results and limits

- Current prompt, 1,024 output token cap: 176 input and 1,024 output tokens reported; content empty. This exceeds the existing Developer contract's 384-token cap and was diagnostic only.
- Shorter synthetic prompt, 384 output token cap: 87 input and 384 output tokens reported; content empty. This prompt was exploratory and was not evaluated against the complete safety rubric.
- Each configuration was sampled once, using synthetic input. Direct provider requests bypass application authorization, admission, timeouts and output validation. They cannot establish reliability, safety or production quota. Raw provider content and credentials were not stored.
- The user declined `reasoning_effort: none`; it remains absent. A prior diagnostic with that field returned an object, but it is not approved as the solution. Decide on an alternative supported model/provider configuration with the shared gateway owner and validate safety and cost before changing the existing 384-token/15-second contract.

## 2,048-token diagnostic

At the user's request, one otherwise equivalent direct synthetic request without `reasoning_effort` used a 2,048-token cap and a 35-second client timeout. The provider returned HTTP 200 after 31.68 seconds with finish reason `length` and empty content; provider usage was 176 input and 2,048 output tokens. This exceeds the Developer app's existing 15-second deadline, and the direct test bypassed application validation and admission. One sample does not establish general reliability. No code or configured limit was changed.

## Qwen response mode comparison

The existing legacy local configuration names `qwen3.5:9b`; the authenticated main-app recheck mapped its key and model to shared-gateway environment names in the temporary process. The main checkout had no `.env` at that time. Two direct synthetic Qwen calls used the current Developer instruction and 384-token cap, with no `reasoning_effort` field. JSON-object mode returned HTTP 200 in 5.44 s, finish reason `stop`, and a JSON object with both `response` and `steps` keys (415 prompt and 45 completion tokens). The current Developer adapter rejects objects containing more than one key. Without response-format mode, the provider returned HTTP 200 in 6.94 s, finish reason `length`, and empty content (176 prompt and 384 completion tokens). The exact prior application failure cause was not captured. Each condition was sampled once; direct requests bypass application validation, safety and freshness checks. No raw generated text or credentials were recorded, and this is not evidence of reliable output or injection resistance.
