/**
 * Frontend validation helpers mirroring backend UserAccountValidationSupport rules.
 */

const USERNAME_PATTERN = /^[a-z0-9][a-z0-9._-]{2,49}$/;
// eslint-disable-next-line no-control-regex
const ISO_CONTROL_PATTERN = /[\u0000-\u001F\u007F-\u009F]/;

function utf8ByteLength(text) {
  if (typeof TextEncoder !== 'undefined') {
    return new TextEncoder().encode(text).length;
  }
  return unescape(encodeURIComponent(text)).length;
}

export function validateUsernameInput(rawUsername) {
  const normalized = typeof rawUsername === 'string' ? rawUsername.trim().toLowerCase() : '';
  if (!normalized) {
    return 'Username is required';
  }
  if (normalized.length < 3 || normalized.length > 50) {
    return 'Username must be between 3 and 50 characters';
  }
  if (!USERNAME_PATTERN.test(normalized)) {
    return 'Username must start with a letter or digit and contain only letters, digits, dots, underscores, or hyphens';
  }
  return '';
}

export function validateFullNameInput(rawFullName) {
  const trimmed = typeof rawFullName === 'string' ? rawFullName.trim() : '';
  if (!trimmed) {
    return 'Full name is required';
  }
  if (trimmed.length < 2 || trimmed.length > 100) {
    return 'Full name must be between 2 and 100 characters';
  }
  if (ISO_CONTROL_PATTERN.test(trimmed)) {
    return 'Full name must not contain control characters';
  }
  return '';
}

export function validatePasswordInput(rawPassword, rawUsername = '') {
  if (typeof rawPassword !== 'string' || rawPassword.trim() === '') {
    return 'Password is required';
  }
  if (rawPassword.length < 8) {
    return 'Password must be at least 8 characters long';
  }
  if (utf8ByteLength(rawPassword) > 72) {
    return 'Password must not exceed 72 UTF-8 bytes';
  }
  if (ISO_CONTROL_PATTERN.test(rawPassword)) {
    return 'Password must not contain control characters';
  }
  const hasLetter = /[A-Za-z\p{L}]/u.test(rawPassword);
  const hasDigit = /[0-9]/.test(rawPassword);
  if (!hasLetter || !hasDigit) {
    return 'Password must contain at least one letter and one digit';
  }
  const normalizedUser = typeof rawUsername === 'string' ? rawUsername.trim().toLowerCase() : '';
  if (normalizedUser && rawPassword.toLowerCase() === normalizedUser) {
    return 'Password must not be identical to the username';
  }
  return '';
}

export function getPasswordPolicyChecks(rawPassword = '', rawUsername = '') {
  const pwd = typeof rawPassword === 'string' ? rawPassword : '';
  const normalizedUser = typeof rawUsername === 'string' ? rawUsername.trim().toLowerCase() : '';
  return {
    minLength: pwd.length >= 8,
    maxBytes: pwd.length > 0 && utf8ByteLength(pwd) <= 72,
    hasLetterAndDigit: /[A-Za-z\p{L}]/u.test(pwd) && /[0-9]/.test(pwd),
    notUsername: pwd.length > 0 && (!normalizedUser || pwd.toLowerCase() !== normalizedUser),
    noControlChars: !ISO_CONTROL_PATTERN.test(pwd),
  };
}

