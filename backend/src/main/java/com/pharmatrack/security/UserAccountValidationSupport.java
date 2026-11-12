package com.pharmatrack.security;

import com.pharmatrack.exception.InvalidUserOperationException;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Shared validation and normalization helpers for user account creation and bootstrap provisioning.
 */
public final class UserAccountValidationSupport {

    public static final int USERNAME_MIN_LENGTH = 3;
    public static final int USERNAME_MAX_LENGTH = 50;
    public static final int FULL_NAME_MIN_LENGTH = 2;
    public static final int FULL_NAME_MAX_LENGTH = 100;
    public static final int PASSWORD_MIN_LENGTH = 8;
    public static final int PASSWORD_MAX_BYTES = 72;

    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9._-]{2,49}$");

    private UserAccountValidationSupport() {
    }

    public static String validateAndNormalizeUsername(String rawUsername) {
        if (rawUsername == null || rawUsername.trim().isEmpty()) {
            throw new InvalidUserOperationException("Username is mandatory");
        }
        String normalized = rawUsername.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() < USERNAME_MIN_LENGTH || normalized.length() > USERNAME_MAX_LENGTH) {
            throw new InvalidUserOperationException("Username must be between 3 and 50 characters");
        }
        if (!USERNAME_PATTERN.matcher(normalized).matches()) {
            throw new InvalidUserOperationException(
                    "Username must start with an alphanumeric character and contain only letters, digits, dots, underscores, or hyphens");
        }
        return normalized;
    }

    public static String validateAndNormalizeFullName(String rawFullName) {
        if (rawFullName == null || rawFullName.trim().isEmpty()) {
            throw new InvalidUserOperationException("Full name is mandatory");
        }
        String trimmed = rawFullName.trim();
        if (trimmed.length() < FULL_NAME_MIN_LENGTH || trimmed.length() > FULL_NAME_MAX_LENGTH) {
            throw new InvalidUserOperationException("Full name must be between 2 and 100 characters");
        }
        boolean hasControl = trimmed.chars().anyMatch(Character::isISOControl);
        if (hasControl) {
            throw new InvalidUserOperationException("Full name must not contain control characters");
        }
        return trimmed;
    }

    public static void validatePassword(String rawPassword, String normalizedUsername) {
        if (rawPassword == null || rawPassword.trim().isEmpty()) {
            throw new InvalidUserOperationException("Password is mandatory");
        }
        if (rawPassword.length() < PASSWORD_MIN_LENGTH) {
            throw new InvalidUserOperationException(
                    "Password must be at least " + PASSWORD_MIN_LENGTH + " characters long");
        }
        if (rawPassword.getBytes(StandardCharsets.UTF_8).length > PASSWORD_MAX_BYTES) {
            throw new InvalidUserOperationException(
                    "Password must not exceed " + PASSWORD_MAX_BYTES + " UTF-8 bytes");
        }
        boolean hasControl = rawPassword.chars().anyMatch(Character::isISOControl);
        if (hasControl) {
            throw new InvalidUserOperationException("Password must not contain control characters");
        }
        boolean hasLetter = rawPassword.chars().anyMatch(Character::isLetter);
        boolean hasDigit = rawPassword.chars().anyMatch(Character::isDigit);
        if (!hasLetter || !hasDigit) {
            throw new InvalidUserOperationException(
                    "Password must contain at least one letter and one digit");
        }
        if (normalizedUsername != null && rawPassword.equalsIgnoreCase(normalizedUsername)) {
            throw new InvalidUserOperationException("Password must not be identical to the username");
        }
    }
}

