# S-01 — Disable unsafe SSO until verified OIDC exists

**Priority:** P0 · **Effort:** S to disable / L to implement · **Owner:** TBD

## Problem and evidence

`OAuth2AuthService.java:71-118` trusts client-supplied identity and creates a local session without exchanging/verifying the authorization code. Route is public in `SecurityConfig.java:29-38`; V017 enables seeded providers.

## Minimal release change

- Disable provider seeds and public SSO callback behind a default-off server feature flag.
- Do not render SSO buttons when server capability is off.
- Return a non-authenticating response; never fall back to email derived from code.

Full implementation is out of this release unless it includes authorization-code+PKCE, exact redirect URI, state, nonce, issuer/audience/signature/expiry verification, verified email policy, account-linking rules and provider-secret management.

## Verification

- Anonymous request with arbitrary provider/code/email cannot create user/session.
- Providers endpoint returns none when disabled; UI has no SSO action.
- Negative integration test is required in CI.
