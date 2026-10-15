package com.team.rag.agent.context;

import org.springframework.stereotype.Component;

/**
 * Model-independent token estimator. CJK code points are counted as one token;
 * other non-whitespace characters use the common four-characters-per-token estimate.
 */
@Component
public class TokenEstimator {

    public int estimate(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int cjk = 0;
        int other = 0;
        for (int offset = 0; offset < text.length();) {
            int cp = text.codePointAt(offset);
            offset += Character.charCount(cp);
            if (Character.isWhitespace(cp)) {
                continue;
            }
            Character.UnicodeScript script = Character.UnicodeScript.of(cp);
            if (script == Character.UnicodeScript.HAN
                    || script == Character.UnicodeScript.HIRAGANA
                    || script == Character.UnicodeScript.KATAKANA
                    || script == Character.UnicodeScript.HANGUL) {
                cjk++;
            } else {
                other++;
            }
        }
        return cjk + (int) Math.ceil(other / 4.0);
    }
}
