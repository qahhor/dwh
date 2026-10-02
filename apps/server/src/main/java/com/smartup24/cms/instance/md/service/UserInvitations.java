package com.smartup24.cms.instance.md.service;

/**
 * The md module's port for inviting a new user (ADR-0032, 8 and 19, question 10): an account is created without a
 * password, and the invitation lets its owner set one. The implementation is in kauth, which keeps the one-time links
 * and delivers them (kauth depends on md; the reverse dependency is forbidden, hence the inversion, as for
 * {@link UserSessionInvalidator}).
 */
public interface UserInvitations {

    /**
     * Issues the invitation of the user in the current transaction; it is delivered to the user's e-mail after the
     * commit. A user who has a password or is not active gets none.
     */
    void invite(long userId);
}
