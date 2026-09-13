package com.abdy2.aotvpathfinder.path;

/** How a single step of a route is performed. */
public enum HopType {
    /** Aspect of the Void right-click: teleports along the look vector into open space. */
    NORMAL,
    /** Etherwarp (sneak + right-click): aimed at a solid block, lands you on top of it. */
    SHIFT,
    /** Ordinary walking, no ability used. */
    WALK
}
