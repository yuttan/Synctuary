package usecase

import (
	"context"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"time"

	"github.com/synctuary/synctuary-server/internal/adapter/infrastructure/crypto"
	"github.com/synctuary/synctuary-server/internal/domain/share"
)

// ShareService composes share.Repository with validation and
// host-path verification for the admin Web UI and client share
// discovery endpoints.
type ShareService struct {
	repo share.Repository
	now  func() int64
}

func NewShareService(repo share.Repository, now func() int64) (*ShareService, error) {
	if repo == nil {
		return nil, fmt.Errorf("share_service: missing repo")
	}
	if now == nil {
		now = func() int64 { return time.Now().Unix() }
	}
	return &ShareService{repo: repo, now: now}, nil
}

var (
	ErrShareNameInvalid    = errors.New("share_name_invalid")
	ErrShareHostPathEmpty  = errors.New("share_host_path_empty")
	ErrShareHostPathNotDir = errors.New("share_host_path_not_directory")
	ErrShareEmptyPatch     = errors.New("share_empty_patch")
)

func (s *ShareService) Create(ctx context.Context, name, hostPath, icon string, readOnly bool, sortOrder int) (*share.Share, error) {
	if err := validateName(name); err != nil {
		return nil, ErrShareNameInvalid
	}
	absPath, err := validateHostPath(hostPath)
	if err != nil {
		return nil, err
	}

	now := s.now()
	for attempt := 0; attempt < 2; attempt++ {
		id, err := crypto.GenerateRandomBytes(16)
		if err != nil {
			return nil, fmt.Errorf("share_service: id entropy: %w", err)
		}
		sh := &share.Share{
			ID:         id,
			Name:       name,
			HostPath:   absPath,
			ReadOnly:   readOnly,
			Icon:       icon,
			SortOrder:  sortOrder,
			IsDefault:  false,
			CreatedAt:  now,
			ModifiedAt: now,
		}
		err = s.repo.Create(ctx, sh)
		switch {
		case err == nil:
			return sh, nil
		case errors.Is(err, share.ErrDuplicate):
			continue
		default:
			return nil, fmt.Errorf("share_service: create: %w", err)
		}
	}
	return nil, fmt.Errorf("share_service: id collision twice — RNG fault")
}

// EnsureDefault creates the default share from the legacy root_path
// if no default share exists yet. Called at startup.
func (s *ShareService) EnsureDefault(ctx context.Context, rootPath string) (*share.Share, error) {
	existing, err := s.repo.GetDefault(ctx)
	if err == nil {
		return existing, nil
	}
	if !errors.Is(err, share.ErrNotFound) {
		return nil, fmt.Errorf("share_service: check default: %w", err)
	}

	absPath, err := filepath.Abs(rootPath)
	if err != nil {
		return nil, fmt.Errorf("share_service: abs path: %w", err)
	}

	now := s.now()
	id, err := crypto.GenerateRandomBytes(16)
	if err != nil {
		return nil, fmt.Errorf("share_service: id entropy: %w", err)
	}
	sh := &share.Share{
		ID:         id,
		Name:       "Files",
		HostPath:   absPath,
		ReadOnly:   false,
		Icon:       "folder",
		SortOrder:  0,
		IsDefault:  true,
		CreatedAt:  now,
		ModifiedAt: now,
	}
	if err := s.repo.Create(ctx, sh); err != nil {
		return nil, fmt.Errorf("share_service: create default: %w", err)
	}
	return sh, nil
}

func (s *ShareService) List(ctx context.Context) ([]share.Share, error) {
	return s.repo.List(ctx)
}

func (s *ShareService) GetByID(ctx context.Context, id []byte) (*share.Share, error) {
	return s.repo.GetByID(ctx, id)
}

func (s *ShareService) GetDefault(ctx context.Context) (*share.Share, error) {
	return s.repo.GetDefault(ctx)
}

func (s *ShareService) Update(ctx context.Context, id []byte, patch share.SharePatch) error {
	if patch.Name == nil && patch.HostPath == nil && patch.ReadOnly == nil && patch.Icon == nil && patch.SortOrder == nil {
		return ErrShareEmptyPatch
	}
	if patch.Name != nil {
		if err := validateName(*patch.Name); err != nil {
			return ErrShareNameInvalid
		}
	}
	if patch.HostPath != nil {
		abs, err := validateHostPath(*patch.HostPath)
		if err != nil {
			return err
		}
		patch.HostPath = &abs
	}
	return s.repo.Update(ctx, id, patch, s.now())
}

func (s *ShareService) Delete(ctx context.Context, id []byte) error {
	return s.repo.Delete(ctx, id)
}

func validateHostPath(p string) (string, error) {
	if p == "" {
		return "", ErrShareHostPathEmpty
	}
	abs, err := filepath.Abs(p)
	if err != nil {
		return "", fmt.Errorf("share_service: abs path: %w", err)
	}
	info, err := os.Stat(abs)
	if err != nil {
		return "", fmt.Errorf("share_service: stat %q: %w", abs, err)
	}
	if !info.IsDir() {
		return "", ErrShareHostPathNotDir
	}
	return abs, nil
}
