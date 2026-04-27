// Package rate defines the rate-limiter interface used by the pairing
// nonce endpoint (PROTOCOL §4.2 "RECOMMENDED: 5 requests per minute
// per source IP"). Factored out of infrastructure so handler tests can
// inject a deterministic fake.
package rate

import "context"

// Limiter is a keyed sliding-window rate limiter. Keys are arbitrary
// strings — the handler passes the source IP (possibly normalized)
// for /pair/nonce; future endpoints MAY use device_id or a composite.
//
// Implementations MUST be safe for concurrent use. The default
// in-memory implementation uses a sliding window to avoid the
// thundering-herd failure mode of fixed-bucket counters at window
// boundaries.
type Limiter interface {
	// Allow records a hit for `key` at time `now` (unix seconds).
	// Returns true if the hit is within the configured budget and
	// the caller may proceed; false otherwise.
	//
	// When false, `retryAfterSec` is a hint for the HTTP
	// Retry-After header — seconds until the oldest counted hit
	// ages out of the window. Zero when allowed=true.
	//
	// Implementations MUST NOT allocate per-call for the hot path
	// beyond what the sliding-window state machine requires;
	// /pair/nonce is unauthenticated and deliberately exposed.
	Allow(ctx context.Context, key string, now int64) (allowed bool, retryAfterSec int64)

	// Forget drops the state for `key`. Called from admin tools
	// after an operator confirms a false-positive lockout; not
	// reachable from the public API.
	Forget(key string)
}
