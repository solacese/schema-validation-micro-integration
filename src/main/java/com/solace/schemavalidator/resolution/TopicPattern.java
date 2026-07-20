package com.solace.schemavalidator.resolution;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TopicPattern {

    private final String pattern;
    private final List<SegmentMatcher> matchers;

    private TopicPattern(String pattern, List<SegmentMatcher> matchers) {
        this.pattern = pattern;
        this.matchers = matchers;
    }

    public static TopicPattern compile(String pattern) {
        String[] segments = pattern.split("/", -1);
        List<SegmentMatcher> matchers = new ArrayList<>(segments.length);
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            if (">".equals(segment)) {
                if (i != segments.length - 1) {
                    throw new IllegalArgumentException("Segment '>' must be last in pattern: " + pattern);
                }
                matchers.add(new MultiWildcardMatcher());
                continue;
            }
            if ("*".equals(segment)) {
                matchers.add(new SingleWildcardMatcher());
                continue;
            }
            if (segment.contains("{")) {
                matchers.add(new RegexSegmentMatcher(segment));
                continue;
            }
            matchers.add(new LiteralSegmentMatcher(segment));
        }
        return new TopicPattern(pattern, matchers);
    }

    public Map<String, String> match(String topic) {
        String[] segments = topic.split("/", -1);
        Map<String, String> captures = new LinkedHashMap<>();
        int topicIndex = 0;
        for (int i = 0; i < matchers.size(); i++) {
            SegmentMatcher matcher = matchers.get(i);
            if (matcher instanceof MultiWildcardMatcher) {
                return captures;
            }
            if (topicIndex >= segments.length) {
                return null;
            }
            if (!matcher.matches(segments[topicIndex], captures)) {
                return null;
            }
            topicIndex++;
        }
        return topicIndex == segments.length ? captures : null;
    }

    public String getPattern() {
        return pattern;
    }

    private interface SegmentMatcher {
        boolean matches(String value, Map<String, String> captures);
    }

    private static final class LiteralSegmentMatcher implements SegmentMatcher {
        private final String literal;

        private LiteralSegmentMatcher(String literal) {
            this.literal = literal;
        }

        @Override
        public boolean matches(String value, Map<String, String> captures) {
            return literal.equals(value);
        }
    }

    private static final class SingleWildcardMatcher implements SegmentMatcher {
        @Override
        public boolean matches(String value, Map<String, String> captures) {
            return !value.isEmpty();
        }
    }

    private static final class MultiWildcardMatcher implements SegmentMatcher {
        @Override
        public boolean matches(String value, Map<String, String> captures) {
            return true;
        }
    }

    private static final class RegexSegmentMatcher implements SegmentMatcher {
        private static final Pattern TOKEN_PATTERN = Pattern.compile("\\{([a-zA-Z][a-zA-Z0-9_]*)}");

        private final Pattern pattern;
        private final List<String> groupNames;

        private RegexSegmentMatcher(String source) {
            StringBuilder regex = new StringBuilder("^");
            Matcher matcher = TOKEN_PATTERN.matcher(source);
            int cursor = 0;
            List<String> names = new ArrayList<>();
            while (matcher.find()) {
                regex.append(Pattern.quote(source.substring(cursor, matcher.start())));
                String captureName = matcher.group(1);
                regex.append("(?<").append(captureName).append(">[^/]+)");
                names.add(captureName);
                cursor = matcher.end();
            }
            regex.append(Pattern.quote(source.substring(cursor)));
            regex.append('$');
            this.pattern = Pattern.compile(regex.toString());
            this.groupNames = names;
        }

        @Override
        public boolean matches(String value, Map<String, String> captures) {
            Matcher matcher = pattern.matcher(value);
            if (!matcher.matches()) {
                return false;
            }
            for (String groupName : groupNames) {
                captures.put(groupName, matcher.group(groupName));
            }
            return true;
        }
    }
}
