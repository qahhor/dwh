package com.smartup24.cms.instance.ms.task.api;

public record UpdateStatusRequest(String name, String color, Integer orderNo, Boolean isTerminal) {}
