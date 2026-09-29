package com.smartup24.cms.instance.ms.task.api;

import com.smartup24.cms.instance.common.web.Revisioned;

/** A task status of the dictionary; its revision is what a change of it names (plan item 3.6). */
public record TaskStatusView(
        Long id, String pcode, String name, String color, int orderNo, boolean isTerminal, long revision)
        implements Revisioned {}
