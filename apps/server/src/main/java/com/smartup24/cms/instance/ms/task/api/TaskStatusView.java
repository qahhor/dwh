package com.smartup24.cms.instance.ms.task.api;

/** A task status of the dictionary. */
public record TaskStatusView(Long id, String pcode, String name, String color, int orderNo, boolean isTerminal) {}
