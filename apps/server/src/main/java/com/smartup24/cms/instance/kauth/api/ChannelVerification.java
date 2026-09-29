package com.smartup24.cms.instance.kauth.api;

/** The token to come back with, together with the code sent to the new address, to confirm a binding. */
public record ChannelVerification(String verifyToken) {}
