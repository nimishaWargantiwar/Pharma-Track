const LOCAL_DEV_HOSTNAMES = new Set([
  'localhost',
  '127.0.0.1',
  '0.0.0.0',
  '::1',
  '[::1]',
]);

/**
 * Normalizes an API base URL by trimming whitespace and removing trailing slashes.
 */
export function normalizeApiBaseUrl(rawUrl) {
  if (typeof rawUrl !== 'string') {
    return '';
  }
  return rawUrl.trim().replace(/\/+$/, '');
}

/**
 * Returns true if the supplied hostname points to localhost, a loopback IP, or a .local host.
 */
export function isLocalhostHostname(hostname) {
  if (typeof hostname !== 'string') {
    return false;
  }
  const normalized = hostname.trim().toLowerCase();
  return (
    LOCAL_DEV_HOSTNAMES.has(normalized) ||
    normalized.endsWith('.localhost') ||
    normalized.endsWith('.local')
  );
}

/**
 * Strictly validates VITE_API_BASE_URL for production builds and production runtime.
 * Requires a non-empty HTTPS URL ending with `/api`, with no localhost/loopback host,
 * no embedded credentials, and no query/hash suffixes.
 */
export function validateProductionApiBaseUrl(rawUrl) {
  const normalized = normalizeApiBaseUrl(rawUrl);
  if (!normalized) {
    throw new Error(
      '[PharmaTrack Build Error] VITE_API_BASE_URL is required for production builds ' +
        '(for example: https://<your-backend>.onrender.com/api). ' +
        'Missing API URLs and localhost fallbacks are blocked in production.'
    );
  }

  let parsed;
  try {
    parsed = new URL(normalized);
  } catch (err) {
    throw new Error(
      `[PharmaTrack Build Error] VITE_API_BASE_URL must be a valid absolute HTTPS URL (received: "${normalized}").`
    );
  }

  if (parsed.protocol !== 'https:') {
    throw new Error(
      `[PharmaTrack Build Error] VITE_API_BASE_URL must use https:// in production builds (received: "${normalized}").`
    );
  }

  if (parsed.username || parsed.password) {
    throw new Error(
      '[PharmaTrack Build Error] VITE_API_BASE_URL must not contain embedded user credentials.'
    );
  }

  if (isLocalhostHostname(parsed.hostname)) {
    throw new Error(
      `[PharmaTrack Build Error] VITE_API_BASE_URL must not point to localhost or a loopback host in production builds (received: "${normalized}").`
    );
  }

  const cleanPath = parsed.pathname.replace(/\/+$/, '');
  if (!cleanPath.endsWith('/api')) {
    throw new Error(
      `[PharmaTrack Build Error] VITE_API_BASE_URL must end with the /api path suffix (e.g. https://<your-backend>.onrender.com/api, received: "${normalized}").`
    );
  }

  if (parsed.search || parsed.hash) {
    throw new Error(
      `[PharmaTrack Build Error] VITE_API_BASE_URL must not contain query parameters or fragments (received: "${normalized}").`
    );
  }

  return normalized;
}

