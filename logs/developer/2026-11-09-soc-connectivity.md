# SoC LLM: bounded connectivity check

## Human-readable summary

- **Goal:** Check whether the configured SoC endpoint can support a Developer checklist-drafting feature.
- **Result:** The model-list endpoint returned HTTP 200. One short synthetic report was sent to the chat-completions endpoint using `llama3.1:8b`; it returned HTTP 200 in 2.58 seconds. The provider reported 40 prompt tokens, 109 completion tokens and 149 total tokens.
- **Privacy and limits:** The request used a synthetic Python CSV-export bug, not real user data. Credentials, endpoint values and provider response text were not saved. This check does not establish output usefulness, prompt-injection resistance, quota limits, caching behavior, or application integration. Cached/reasoning token metrics and cost were not recorded.
- **Resource use:** Two read-only model-list requests and one generation request were made; the second model-list request identified available model names needed to select a test model. The application output cap for this one request was 120 tokens. No provider quota is inferred from that cap.

## Technical evidence

- Date: 9 November 2026 (Singapore). Configured SoC endpoint and credentials were obtained from environment variables without printing their values.
- Chat request: `POST` to the configured chat-completions endpoint, model `llama3.1:8b`, `temperature: 0`, `max_tokens: 120`, timeout 45 seconds. Response content length: 457 characters; actual response text was not retained or assessed.
- Usage fields recorded from the provider response: `prompt_tokens=40`, `completion_tokens=109`, `total_tokens=149`. Provider/model metadata reported the selected model. Actual elapsed time measured around the request: 2.58 seconds.
- The course guidance link could not be opened by the browsing tool. Actual service quota and allowed request cadence remain unverified; no additional live requests should be used as evidence of availability, security or evaluation without recording their outcome.
