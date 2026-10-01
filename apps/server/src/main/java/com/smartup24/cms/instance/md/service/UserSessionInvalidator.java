package com.smartup24.cms.instance.md.service;

/**
 * The md module's port for invalidating a user's access (user-blocking invariant, FR-USR-4):
 * a password change, blocking and anonymization raise the access version, close sessions
 * and revoke API tokens in the SAME transaction. A standalone call is atomic too.
 * OTPs of an older version are rejected logically, without fake consumption.
 * The implementation is in kauth (kauth depends on md; the reverse dependency is forbidden
 * by the ArchUnit no-cycles rule, hence the dependency inversion).
 */
public interface UserSessionInvalidator {

    void invalidateAllAccess(Long userId);
}
