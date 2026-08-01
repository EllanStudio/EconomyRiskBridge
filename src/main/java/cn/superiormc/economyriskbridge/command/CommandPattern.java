package cn.superiormc.economyriskbridge.command;

import cn.superiormc.economyriskbridge.util.Money;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

public final class CommandPattern {

    private static final String TARGET = "{target}";
    private static final String AMOUNT = "{amount}";

    private final String source;
    private final String currencyId;
    private final String[] tokens;

    public CommandPattern(String source, String currencyId) {
        this.source = stripSlash(source);
        this.currencyId = currencyId.toLowerCase(Locale.ROOT);
        this.tokens = split(this.source);

        long targetCount = Arrays.stream(this.tokens).filter(TARGET::equalsIgnoreCase).count();
        long amountCount = Arrays.stream(this.tokens).filter(AMOUNT::equalsIgnoreCase).count();
        if (targetCount != 1 || amountCount != 1) {
            throw new IllegalArgumentException("Command pattern must contain one {target} and one {amount}: " + source);
        }
    }

    public Optional<Match> match(String commandLine) {
        String[] actual = split(stripSlash(commandLine));
        if (actual.length != this.tokens.length) {
            return Optional.empty();
        }

        String target = null;
        long amount = 0L;
        for (int index = 0; index < this.tokens.length; index++) {
            String expected = this.tokens[index];
            String value = actual[index];
            if (index == 0) {
                value = stripNamespace(value);
            }
            if (TARGET.equalsIgnoreCase(expected)) {
                target = value;
            } else if (AMOUNT.equalsIgnoreCase(expected)) {
                try {
                    amount = Money.parseMicros(value);
                } catch (IllegalArgumentException exception) {
                    return Optional.empty();
                }
            } else if (!expected.equalsIgnoreCase(value)) {
                return Optional.empty();
            }
        }

        if (target == null || target.isBlank() || amount <= 0L) {
            return Optional.empty();
        }
        return Optional.of(new Match(target, this.currencyId, amount));
    }

    public String source() {
        return this.source;
    }

    public String currencyId() {
        return this.currencyId;
    }

    public String rootCommand() {
        return this.tokens.length == 0 ? "" : stripNamespace(this.tokens[0]).toLowerCase(Locale.ROOT);
    }

    private static String[] split(String input) {
        if (input.isBlank()) {
            return new String[0];
        }
        return input.trim().split("\\s+");
    }

    private static String stripSlash(String input) {
        String value = input == null ? "" : input.trim();
        return value.startsWith("/") ? value.substring(1) : value;
    }

    private static String stripNamespace(String input) {
        int colon = input.indexOf(':');
        return colon >= 0 && colon + 1 < input.length() ? input.substring(colon + 1) : input;
    }

    public record Match(String targetName, String currencyId, long amountMicros) {
    }
}
