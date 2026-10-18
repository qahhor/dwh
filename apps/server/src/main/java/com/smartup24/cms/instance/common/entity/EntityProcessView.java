package com.smartup24.cms.instance.common.entity;

import java.util.Map;

/**
 * What a live form tells the screens about the process of its documents beyond the platform's declaration (ADR-0043):
 * the color of each state's badge and, of each transition, the dictionary key of its name and whether it takes a
 * comment ({@code none}, {@code optional} or {@code required}). The server enforces the comment; the screen asks for it.
 *
 * @param colors      the color of each state, by its code
 * @param transitions each transition's name and comment, by its code
 */
public record EntityProcessView(Map<String, String> colors, Map<String, Transition> transitions) {

    /** The name of a transition and whether it takes a comment. */
    public record Transition(String labelKey, String comment) {}

    public EntityProcessView {
        colors = Map.copyOf(colors);
        transitions = Map.copyOf(transitions);
    }
}
