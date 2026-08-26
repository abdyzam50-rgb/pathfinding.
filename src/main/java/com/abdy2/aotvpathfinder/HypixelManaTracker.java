package com.abdy2.aotvpathfinder;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class HypixelManaTracker {
    private static final Pattern MANA_PATTERN = Pattern.compile("([0-9,]+)/([0-9,]+)\\s*Mana");

    private int currentMana = -1;
    private int maxMana = -1;

    public void acceptActionBar(String text) {
        Matcher matcher = MANA_PATTERN.matcher(text);
        if (!matcher.find()) {
            return;
        }

        currentMana = parseNumber(matcher.group(1));
        maxMana = parseNumber(matcher.group(2));
    }

    public int currentMana() {
        return currentMana;
    }

    public int maxMana() {
        return maxMana;
    }

    public boolean hasAnyData() {
        return currentMana >= 0 && maxMana >= 0;
    }

    private static int parseNumber(String raw) {
        return Integer.parseInt(raw.replace(",", ""));
    }
}
