package com.smartup24.cms.instance.support.entity;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * What an entity's fixture may use to prepare the rows its records refer to (ADR-0032, 11.1).
 *
 * @param jdbc      the application's database, for rows of other entities the fixture needs
 * @param anyUserId an existing user, for a reference to a user
 * @param tag       a token unique to this run of the kit
 */
public record FixtureContext(JdbcClient jdbc, long anyUserId, String tag) {}
