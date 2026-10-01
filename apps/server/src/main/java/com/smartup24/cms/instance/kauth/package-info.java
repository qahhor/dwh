/**
 * Authentication. The {@code kauth} prefix is the Biruni convention for authentication: sign-in with a password and a
 * one-time code, sessions, API tokens, password change and reset, sign-in through OAuth2/SSO providers and the channels
 * on which a user receives codes. It owns the {@code kauth_*} tables and {@code md_sso_providers}; its endpoints use
 * forms that the master data module publishes to it (ADR-0028). Module map: {@code docs/architecture/module-map.md}.
 */
package com.smartup24.cms.instance.kauth;
