package com.smartup24.cms.instance.common.web;

/**
 * A record that carries the revision a change of it must name (plan 10/10, item 3.6): its answers get an
 * {@code ETag} with the revision, and {@code PATCH}/{@code PUT} of it require {@code If-Match}.
 */
public interface Revisioned {

    long revision();
}
