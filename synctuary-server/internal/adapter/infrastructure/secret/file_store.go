// Package secret provides the on-disk FileStore backing for domain/secret.Store.
//
// The key is written atomically (write-to-tmp + rename) at mode 0600.
// No encryption-at-rest is applied; confidentiality rests on filesystem
// permissions and full-disk encryption (see domain/secret comment).
package secret

import (
	"context"
	"errors"
	"fmt"
	"os"
	"path/filepath"

	domainsecret "github.com/synctuary/synctuary-server/internal/domain/secret"
)

var _ domainsecret.Store = (*FileStore)(nil)

// masterKeyLen matches PROTOCOL §3.2 HKDF output.
const masterKeyLen = 32

type FileStore struct {
	path string
}

func NewFileStore(path string) *FileStore {
	return &FileStore{path: path}
}

func (f *FileStore) SaveMasterKey(_ context.Context, key []byte) error {
	if len(key) != masterKeyLen {
		return fmt.Errorf("secret: master_key length %d, expected %d", len(key), masterKeyLen)
	}

	if err := os.MkdirAll(filepath.Dir(f.path), 0o700); err != nil {
		return fmt.Errorf("secret: mkdir parent: %w", err)
	}

	tmp := f.path + ".tmp"
	fh, err := os.OpenFile(tmp, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o600)
	if err != nil {
		return fmt.Errorf("secret: open tmp: %w", err)
	}

	writeErr := func() error {
		if _, err := fh.Write(key); err != nil {
			return err
		}
		return fh.Sync()
	}()
	if cerr := fh.Close(); cerr != nil && writeErr == nil {
		writeErr = cerr
	}
	if writeErr != nil {
		_ = os.Remove(tmp)
		return fmt.Errorf("secret: write tmp: %w", writeErr)
	}

	if err := os.Rename(tmp, f.path); err != nil {
		_ = os.Remove(tmp)
		return fmt.Errorf("secret: rename: %w", err)
	}
	return nil
}

func (f *FileStore) LoadMasterKey(_ context.Context) ([]byte, error) {
	data, err := os.ReadFile(f.path)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return nil, domainsecret.ErrNotFound
		}
		return nil, fmt.Errorf("secret: read: %w", err)
	}
	if len(data) != masterKeyLen {
		return nil, fmt.Errorf("secret: master_key file has wrong length: got %d, want %d", len(data), masterKeyLen)
	}
	return data, nil
}
