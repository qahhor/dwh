package com.smartup24.cms.instance.md.api;

/**
 * What another module needs to act as a user (sign-in filter, background work as a person, ADR-0018): who the user
 * is, whether the account is active and which authentication version its credentials must carry. No password hash,
 * no profile fields (plan 10/10, item 1.3).
 */
public record MdUserIdentity(
        Long id, String login, String email, String state, boolean forcePasswordChange, long authenticationVersion) {}
