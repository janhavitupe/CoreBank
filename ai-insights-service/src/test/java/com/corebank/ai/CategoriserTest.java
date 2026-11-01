package com.corebank.ai;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CategoriserTest {

    private final GeminiAgent gemini = mock(GeminiAgent.class);
    private final Categoriser categoriser = new Categoriser(gemini);

    private void geminiAnswers(String answer) {
        when(gemini.isConfigured()).thenReturn(true);
        when(gemini.run(anyString(), anyString(), any(List.class), anyString(), anyString())).thenReturn(answer);
    }

    @Test
    void returnsTheCategoryGeminiChose() {
        geminiAnswers("Food.\n");
        assertThat(categoriser.categorise("Grocery shopping", "2500")).isEqualTo("food");
    }

    @Test
    void anUnexpectedAnswerBecomesOther() {
        geminiAnswers("I think this is groceries");
        assertThat(categoriser.categorise("Grocery shopping", "2500")).isEqualTo("other");
    }

    @Test
    void aGeminiFailureBecomesOther() {
        when(gemini.isConfigured()).thenReturn(true);
        when(gemini.run(anyString(), anyString(), any(List.class), anyString(), anyString()))
                .thenThrow(new RuntimeException("503"));
        assertThat(categoriser.categorise("Grocery shopping", "2500")).isEqualTo("other");
    }

    @Test
    void noApiKeyBecomesOther() {
        when(gemini.isConfigured()).thenReturn(false);
        assertThat(categoriser.categorise("Grocery shopping", "2500")).isEqualTo("other");
    }

    @Test
    void accountNumbersAreMaskedBeforeGeminiSeesThem() {
        geminiAnswers("transfer");
        categoriser.categorise("Paid CB1234567890", "10");
        verify(gemini).run(anyString(), anyString(), any(List.class), eq("system"), contains("****7890"));
        assertThat(Masking.mask("Paid CB1234567890")).doesNotContain("123456");
    }
}
