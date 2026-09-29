package com.smartup24.cms.instance.ms.task.api;

import java.util.Map;

public record UpdateProjectRequest(String name, String description, String state, Map<String, Object> attributes) {}
