package com.smartup24.cms.instance.kauth.api;

/** A new API token and its secret, returned once at creation and never again. */
public record CreatedApiToken(ApiTokenView record, String rawSecretToken) {}
