package usecase

import (
	"context"
	"errors"
	"fmt"
	"time"
	"unicode/utf8"

	"github.com/synctuary/synctuary-server/internal/adapter/infrastructure/crypto"
	"github.com/synctuary/synctuary-server/internal/domain/favorite"
)

// FavoriteService composes favorite.Repository with cross-cutting
// concerns (id generation, name validation, time stamping) for the
// PROTOCOL v0.2.3 §8 endpoints.
//
// Validation lives here rather than in the repository because the
// PROTOCOL §8.4/§8.5 rules (1..256 NFC chars, no leading/trailing
// whitespace, no control bytes) are wire-shape concerns, not storage
// constraints — and the same path-style rules used by §6 file ops
// belong to the file_service today, so we keep the symmetry.
type FavoriteService struct {
	repo favorite.Repository
	now  func() int64
}

// NewFavoriteService constructs the service. The clock is injectable
// so integration tests can pin time without globals; nil falls back
// to time.Now().Unix.
func NewFavoriteService(repo favorite.Repository, now func() int64) (*FavoriteService, error) {
	if repo == nil {
		return nil, fmt.Errorf("favorite_service: missing repo")
	}
	if now == nil {
		now = func() int64 { return time.Now().Unix() }
	}
	return &FavoriteService{repo: repo, now: now}, nil
}

// ErrFavoriteNameInvalid is returned when a list name fails §8.4
// validation. Mapped by the handler to 400 favorite_name_invalid.
var ErrFavoriteNameInvalid = errors.New("favorite_name_invalid")

// ErrFavoriteEmptyPatch is returned when PATCH (§8.5) carries no
// effective fields. Mapped to 400 bad_request.
var ErrFavoriteEmptyPatch = errors.New("favorite_empty_patch")

// validateName enforces the §8.4 / §8.5 rules:
//   - 1..256 NFC characters after trimming
//   - no leading / trailing whitespace
//   - no embedded control bytes (per §1 path rules — applied here
//     to list names too because they end up rendered in clients,
//     where a control byte is a UI hazard)
//
// We require the caller to pass an already-trimmed name so the
// "no leading/trailing whitespace" rule is enforced at the wire
// boundary (the handler trims and compares).
func validateName(name string) error {
	if !utf8.ValidString(name) {
		return ErrFavoriteNameInvalid
	}
	runeCount := utf8.RuneCountInString(name)
	if runeCount < 1 || runeCount > 256 {
		return ErrFavoriteNameInvalid
	}
	for _, r := range name {
		// Reject ASCII control bytes (0x00–0x1F, 0x7F) and the
		// Unicode line/paragraph separators that clients render
		// inconsistently. Tab is rejected too — a tab in a list
		// name is a sign of accidental paste, not user intent.
		if r < 0x20 || r == 0x7F || r == 0x2028 || r == 0x2029 {
			return ErrFavoriteNameInvalid
		}
	}
	return nil
}

// CreateList implements §8.4. Caller passes the already-NFC-trimmed
// name and the originating device_id (from BearerAuth context).
//
// On the astronomically improbable id collision we retry once with
// fresh entropy; a second collision is an irrecoverable RNG fault and
// surfaces to the client as 500.
func (s *FavoriteService) CreateList(ctx context.Context, name string, hidden bool, deviceID []byte) (*favorite.List, error) {
	if err := validateName(name); err != nil {
		return nil, err
	}

	now := s.now()
	for attempt := 0; attempt < 2; attempt++ {
		id, err := crypto.GenerateRandomBytes(16)
		if err != nil {
			return nil, fmt.Errorf("favorite_service: id entropy: %w", err)
		}
		list := &favorite.List{
			ID:                id,
			Name:              name,
			Hidden:            hidden,
			ItemCount:         0,
			CreatedAt:         now,
			ModifiedAt:        now,
			CreatedByDeviceID: deviceID,
		}
		err = s.repo.CreateList(ctx, list)
		switch {
		case err == nil:
			return list, nil
		case errors.Is(err, favorite.ErrDuplicate):
			// retry with fresh entropy
			continue
		default:
			return nil, fmt.Errorf("favorite_service: create: %w", err)
		}
	}
	return nil, fmt.Errorf("favorite_service: id collision twice — RNG fault")
}

// ListAll is the §8.2 read.
func (s *FavoriteService) ListAll(ctx context.Context, includeHidden bool) ([]favorite.List, error) {
	return s.repo.ListAll(ctx, includeHidden)
}

// GetList is the §8.3 read.
func (s *FavoriteService) GetList(ctx context.Context, id []byte) (*favorite.ListWithItems, error) {
	return s.repo.GetList(ctx, id)
}

// UpdateList is the §8.5 PATCH. The handler is responsible for
// trimming `*patch.Name` before passing it in.
func (s *FavoriteService) UpdateList(ctx context.Context, id []byte, patch favorite.ListPatch) error {
	if patch.Name == nil && patch.Hidden == nil {
		return ErrFavoriteEmptyPatch
	}
	if patch.Name != nil {
		if err := validateName(*patch.Name); err != nil {
			return err
		}
	}
	return s.repo.UpdateList(ctx, id, patch, s.now())
}

// DeleteList is the §8.6 endpoint.
func (s *FavoriteService) DeleteList(ctx context.Context, id []byte) error {
	return s.repo.DeleteList(ctx, id)
}

// AddItem is the §8.7 POST. Path validation is delegated to the
// handler — the handler already performs the §1 path rules check
// for every other endpoint via validatedPath().
func (s *FavoriteService) AddItem(ctx context.Context, listID []byte, path string, deviceID []byte) (favorite.Item, bool, error) {
	now := s.now()
	item := favorite.Item{
		Path:            path,
		AddedAt:         now,
		AddedByDeviceID: deviceID,
	}
	return s.repo.AddItem(ctx, listID, item)
}

// RemoveItem is the §8.8 DELETE.
func (s *FavoriteService) RemoveItem(ctx context.Context, listID []byte, path string) error {
	return s.repo.RemoveItem(ctx, listID, path, s.now())
}
