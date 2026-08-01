package cn.superiormc.economyriskbridge.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

public final class Money {

    public static final int SCALE = 6;
    public static final long FACTOR = 1_000_000L;

    private Money() {
    }

    public static long parseMicros(String input) {
        if (input == null) {
            throw new IllegalArgumentException("amount is missing");
        }

        String normalized = input.trim().toLowerCase(Locale.ROOT)
            .replace(",", "")
            .replace("_", "");
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("amount is empty");
        }

        BigDecimal multiplier = BigDecimal.ONE;
        char suffix = normalized.charAt(normalized.length() - 1);
        if (Character.isLetter(suffix)) {
            multiplier = switch (suffix) {
                case 'k' -> BigDecimal.valueOf(1_000L);
                case 'm' -> BigDecimal.valueOf(1_000_000L);
                case 'b' -> BigDecimal.valueOf(1_000_000_000L);
                case 't' -> BigDecimal.valueOf(1_000_000_000_000L);
                default -> throw new IllegalArgumentException("unsupported amount suffix");
            };
            normalized = normalized.substring(0, normalized.length() - 1);
        }

        if (normalized.isEmpty() || normalized.startsWith("-") || normalized.startsWith("+")) {
            throw new IllegalArgumentException("amount must be positive");
        }

        try {
            BigDecimal amount = new BigDecimal(normalized).multiply(multiplier);
            return toMicros(amount);
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new IllegalArgumentException("invalid or oversized amount", exception);
        }
    }

    public static long toMicros(BigDecimal amount) {
        return amount.setScale(SCALE, RoundingMode.HALF_UP)
            .movePointRight(SCALE)
            .longValueExact();
    }

    public static long fromDouble(double amount) {
        if (!Double.isFinite(amount)) {
            throw new IllegalArgumentException("amount must be finite");
        }
        return toMicros(BigDecimal.valueOf(amount));
    }

    public static double toDouble(long micros) {
        return BigDecimal.valueOf(micros, SCALE).doubleValue();
    }

    public static String format(long micros) {
        return BigDecimal.valueOf(micros, SCALE).stripTrailingZeros().toPlainString();
    }

    public static long nonNegativeConfig(String value, String path) {
        long amount = parseMicros(value == null ? "0" : value);
        if (amount < 0) {
            throw new IllegalArgumentException(path + " must not be negative");
        }
        return amount;
    }
}
