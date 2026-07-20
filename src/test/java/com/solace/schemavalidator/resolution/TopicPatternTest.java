package com.solace.schemavalidator.resolution;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TopicPatternTest {

    @Test
    void matchesLiteralPattern() {
        TopicPattern pattern = TopicPattern.compile("watch/inventory/update");
        assertThat(pattern.match("watch/inventory/update")).isNotNull().isEmpty();
        assertThat(pattern.match("watch/inventory/update/extra")).isNull();
        assertThat(pattern.match("watch/inventory")).isNull();
    }

    @Test
    void matchesSingleWildcard() {
        TopicPattern pattern = TopicPattern.compile("watch/*/update");
        assertThat(pattern.match("watch/inventory/update")).isNotNull();
        assertThat(pattern.match("watch//update")).isNull();
        assertThat(pattern.match("watch/inventory/update/extra")).isNull();
    }

    @Test
    void matchesMultiWildcardAtEnd() {
        TopicPattern pattern = TopicPattern.compile("watch/>");
        assertThat(pattern.match("watch/inventory/update/1.0.0")).isNotNull();
        assertThat(pattern.match("watch/x")).isNotNull();
    }

    @Test
    void capturesNamedSegments() {
        TopicPattern pattern = TopicPattern.compile("watch/{domain}/{version}");
        var captures = pattern.match("watch/inventory/1.0.0");
        assertThat(captures).containsEntry("domain", "inventory").containsEntry("version", "1.0.0");
    }

    @Test
    void returnsNullOnSegmentCountMismatch() {
        TopicPattern pattern = TopicPattern.compile("a/b/c");
        assertThat(pattern.match("a/b")).isNull();
        assertThat(pattern.match("a/b/c/d")).isNull();
    }

    @Test
    void captureSegmentDoesNotMatchEmptyValue() {
        TopicPattern pattern = TopicPattern.compile("watch/{version}");
        // topic has segment but it's empty
        assertThat(pattern.match("watch/")).isNull();
    }

    @Test
    void throwsWhenMultiWildcardIsNotLast() {
        assertThatThrownBy(() -> TopicPattern.compile("watch/>/update"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(">");
    }
}
