/**
 * Simple in-memory cache for JS spiders.
 * Equivalent to Python's cachetools.TTLCache.
 */
const __cache = {};

/**
 * Store a value in the cache with an optional TTL (time-to-live) in milliseconds.
 * @param {string} key - Cache key
 * @param {*} value - Value to cache
 * @param {number} [ttlMs] - Time-to-live in milliseconds. If omitted, never expires.
 */
function cachePut(key, value, ttlMs) {
    __cache[key] = {
        value: value,
        expiresAt: ttlMs ? Date.now() + ttlMs : Infinity
    };
}

/**
 * Get a value from the cache. Returns undefined if not found or expired.
 * @param {string} key - Cache key
 * @returns {*} Cached value or undefined
 */
function cacheGet(key) {
    const entry = __cache[key];
    if (!entry) return undefined;
    if (Date.now() > entry.expiresAt) {
        cacheDel(key);
        return undefined;
    }
    return entry.value;
}

/**
 * Delete a value from the cache.
 * @param {string} key - Cache key
 */
function cacheDel(key) {
    delete __cache[key];
}

/**
 * Clear all cached entries.
 */
function cacheClear() {
    for (const key in __cache) delete __cache[key];
}

/**
 * Get or compute a cached value. If the key exists and is not expired, return it.
 * Otherwise, call the compute function, cache the result, and return it.
 * @param {string} key - Cache key
 * @param {function} compute - Function to compute the value if not cached
 * @param {number} [ttlMs] - Time-to-live in milliseconds
 * @returns {*} Cached or computed value
 */
function cacheGetOrPut(key, compute, ttlMs) {
    const cached = cacheGet(key);
    if (cached !== undefined) return cached;
    const value = compute();
    cachePut(key, value, ttlMs);
    return value;
}