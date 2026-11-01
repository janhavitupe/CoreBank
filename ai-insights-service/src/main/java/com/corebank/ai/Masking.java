package com.corebank.ai;

/** Hides all but the last four digits of account numbers before any text is sent to Gemini. */
final class Masking {

    private Masking() {
    }

    static String mask(String text) {
        return text == null ? null : text.replaceAll("\\bCB\\d{6}(\\d{4})\\b", "****$1");
    }
}
