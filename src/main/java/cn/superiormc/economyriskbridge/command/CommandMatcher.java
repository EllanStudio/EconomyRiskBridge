package cn.superiormc.economyriskbridge.command;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Routes command lines by their first token before doing the more expensive amount and argument parsing.
 */
public final class CommandMatcher {

    private final Map<String, List<CommandPattern>> patternsByRoot;

    public CommandMatcher(List<CommandPattern> patterns) {
        Map<String, List<CommandPattern>> indexed = new HashMap<>();
        for (CommandPattern pattern : patterns) {
            indexed.computeIfAbsent(pattern.rootCommand(), ignored -> new ArrayList<>()).add(pattern);
        }
        indexed.replaceAll((ignored, values) -> List.copyOf(values));
        this.patternsByRoot = Map.copyOf(indexed);
    }

    public Optional<CommandPattern.Match> match(String commandLine) {
        List<CommandPattern> candidates = this.patternsByRoot.get(root(commandLine));
        if (candidates == null) {
            return Optional.empty();
        }
        for (CommandPattern pattern : candidates) {
            Optional<CommandPattern.Match> matched = pattern.match(commandLine);
            if (matched.isPresent()) {
                return matched;
            }
        }
        return Optional.empty();
    }

    static String root(String commandLine) {
        if (commandLine == null) {
            return "";
        }
        int start = 0;
        int length = commandLine.length();
        while (start < length && Character.isWhitespace(commandLine.charAt(start))) {
            start++;
        }
        if (start < length && commandLine.charAt(start) == '/') {
            start++;
        }
        int end = start;
        while (end < length && !Character.isWhitespace(commandLine.charAt(end))) {
            end++;
        }
        int colon = commandLine.lastIndexOf(':', end - 1);
        if (colon >= start) {
            start = colon + 1;
        }
        return start >= end ? "" : commandLine.substring(start, end).toLowerCase(Locale.ROOT);
    }
}
