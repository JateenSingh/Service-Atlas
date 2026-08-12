package com.serviceatlas.parser.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class NameNormalizerTest {

    @DisplayName("FR-3.2: kebab, camel, snake and screaming-snake spellings converge")
    @ParameterizedTest
    @ValueSource(strings = {"log-quote-svc", "logQuoteSvc", "LOG_QUOTE_SVC", "log_quote_svc", "LogQuoteSvc"})
    void allSpellingsShareOneCanonicalForm(String spelling) {
        assertThat(NameNormalizer.canonical(spelling)).isEqualTo("logquotesvc");
    }

    @Test
    void stemStripsTrailingNoiseSoClientArtifactsMatchTheirService() {
        assertThat(NameNormalizer.canonicalStem("log-quote-svc-client"))
                .isEqualTo(NameNormalizer.canonicalStem("log-quote-svc"))
                .isEqualTo("logquote");
    }

    @Test
    void distinctServicesDoNotShareACanonicalForm() {
        assertThat(NameNormalizer.canonical("log-quote-svc"))
                .isNotEqualTo(NameNormalizer.canonical("log-user-svc"));
    }

    @DisplayName("Short or generic names are not matchable, to avoid false positives")
    @ParameterizedTest
    @CsvSource({
            "api,false",
            "svc,false",
            "service,false",
            "app,false",
            "log,false",
            "logquotesvc,true",
            "billing,true"
    })
    void matchability(String canonical, boolean expected) {
        assertThat(NameNormalizer.isMatchable(canonical)).isEqualTo(expected);
    }

    @DisplayName("Hosts are extracted from URL-shaped references")
    @ParameterizedTest
    @CsvSource({
            "http://log-quote-svc:9000/quotes,log-quote-svc",
            "https://user:pass@log-user-svc/users,log-user-svc",
            "log-quote-svc,log-quote-svc",
            "http://log-quote-svc.logistics.svc.cluster.local:9000/x,log-quote-svc.logistics.svc.cluster.local"
    })
    void hostExtraction(String input, String expected) {
        assertThat(NameNormalizer.hostOf(input)).isEqualTo(expected);
    }

    @Test
    void serviceLabelStripsTheClusterDomain() {
        assertThat(NameNormalizer.serviceLabelOf("http://log-shipment-svc.logistics.svc.cluster.local:9000/x"))
                .isEqualTo("log-shipment-svc");
    }

    @Test
    @DisplayName("A public domain keeps its full host: api.stripe.com must not collapse to 'api'")
    void serviceLabelKeepsPublicDomains() {
        assertThat(NameNormalizer.serviceLabelOf("https://api.stripe.com/v1/charges"))
                .isEqualTo("api.stripe.com");
        assertThat(NameNormalizer.firstLabelOf("https://api.stripe.com/v1/charges")).isEqualTo("api");
    }

    @Test
    void blankInputIsHandled() {
        assertThat(NameNormalizer.canonical(null)).isEmpty();
        assertThat(NameNormalizer.canonical("  ")).isEmpty();
        assertThat(NameNormalizer.hostOf(null)).isEmpty();
        assertThat(NameNormalizer.tokens("")).isEmpty();
    }
}
