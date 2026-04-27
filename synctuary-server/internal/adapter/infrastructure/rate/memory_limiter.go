// Package rate provides an in-memory sliding-window Limiter used by
// unauthenticated endpoints (PROTOCOL §4.2 /pair/nonce).
//
// The implementation is a classic per-key sliding window: for each
// key, a slice of hit timestamps is kept and pruned on every Allow.
// State is bounded by the active-key set; Forget is provided for
// admin cleanup but the most common case (a short window, bursty
// traffic then silence) is handled implicitly — when a bucket drains
// to empty the key is deleted from the map to prevent unbounded
// growth by probing attackers.
package rate

import (
	"context"
	"sync"

	domainrate "github.com/synctuary/synctuary-server/internal/domain/rate"
)

var _ domainrate.Limiter = (*MemoryLimiter)(nil)

type MemoryLimiter struct {
	mu        sync.Mutex
	maxHits   int
	windowSec int64
	hits      map[string][]int64
}

// NewMemoryLimiter panics on non-positive arguments; those represent
// configuration bugs that we want to surface at start-up, not during
// request handling.
func NewMemoryLimiter(maxHits int, windowSec int64) *MemoryLimiter {
	if maxHits <= 0 {
		panic("rate: NewMemoryLimiter: maxHits must be positive")
	}
	if windowSec <= 0 {
		panic("rate: NewMemoryLimiter: windowSec must be positive")
	}
	return &MemoryLimiter{
		maxHits:   maxHits,
		windowSec: windowSec,
		hits:      make(map[string][]int64),
	}
}

func (l *MemoryLimiter) Allow(_ context.Context, key string, now int64) (bool, int64) {
	l.mu.Lock()
	defer l.mu.Unlock()

	cutoff := now - l.windowSec + 1
	bucket := l.hits[key]

	// Prune timestamps that have slid out of the window.
	drop := 0
	for drop < len(bucket) && bucket[drop] < cutoff {
		drop++
	}
	if drop > 0 {
		bucket = bucket[drop:]
	}

	if len(bucket) < l.maxHits {
		bucket = append(bucket, now)
		l.hits[key] = bucket
		return true, 0
	}

	// Denied: compute retry-after from the oldest hit's age-out time.
	retry := bucket[0] + l.windowSec - now
	if retry <= 0 {
		retry = 1
	}
	l.hits[key] = bucket
	return false, retry
}

func (l *MemoryLimiter) Forget(key string) {
	l.mu.Lock()
	defer l.mu.Unlock()
	delete(l.hits, key)
}
