package fs

import (
	"bytes"
	"context"
	"crypto/sha256"
	"io"
	"os"
	"path/filepath"
	"testing"

	domainfile "github.com/synctuary/synctuary-server/internal/domain/file"
)

type stubResolver struct {
	path string
}

func (s *stubResolver) ResolvePath(_ context.Context, _ []byte) (string, error) {
	if s.path == "" {
		return "", domainfile.ErrFileNotFound
	}
	return s.path, nil
}

func setupBench(b *testing.B, fileSize int) (*FileStorage, string, []byte) {
	b.Helper()
	tmpDir := b.TempDir()
	root := filepath.Join(tmpDir, "root")
	staging := filepath.Join(tmpDir, "staging")

	content := make([]byte, fileSize)
	for i := range content {
		content[i] = byte(i & 0xff)
	}

	srcPath := filepath.Join(root, "source.bin")
	if err := os.MkdirAll(root, 0o755); err != nil {
		b.Fatal(err)
	}
	if err := os.MkdirAll(staging, 0o755); err != nil {
		b.Fatal(err)
	}
	if err := os.WriteFile(srcPath, content, 0o644); err != nil {
		b.Fatal(err)
	}

	hash := sha256.Sum256(content)
	resolver := &stubResolver{path: srcPath}

	storage, err := NewFileStorage(root, staging, resolver)
	if err != nil {
		b.Fatal(err)
	}
	return storage, srcPath, hash[:]
}

func BenchmarkSyncCopy(b *testing.B) {
	sizes := []struct {
		name string
		size int
	}{
		{"1KiB", 1 << 10},
		{"64KiB", 64 << 10},
		{"1MiB", 1 << 20},
		{"8MiB", 8 << 20},
		{"32MiB", 32 << 20},
		{"128MiB", 128 << 20},
	}

	for _, sz := range sizes {
		b.Run(sz.name, func(b *testing.B) {
			storage, _, hash := setupBench(b, sz.size)
			ctx := context.Background()
			b.SetBytes(int64(sz.size))
			b.ResetTimer()

			for i := 0; i < b.N; i++ {
				target := "/bench-target.bin"
				if err := storage.SyncCopy(ctx, hash, target); err != nil {
					b.Fatal(err)
				}
				// Clean up for next iteration.
				abs, _ := storage.resolveUserPath(target)
				_ = os.Remove(abs)
			}
		})
	}
}

func BenchmarkCopyCtx(b *testing.B) {
	bufSizes := []struct {
		name string
		size int
	}{
		{"64KiB-buf", 64 << 10},
		{"256KiB-buf", 256 << 10},
		{"1MiB-buf", 1 << 20},
		{"4MiB-buf", 4 << 20},
	}

	dataSize := 32 << 20 // 32 MiB payload
	data := make([]byte, dataSize)
	for i := range data {
		data[i] = byte(i & 0xff)
	}

	for _, bs := range bufSizes {
		b.Run(bs.name, func(b *testing.B) {
			tmpDir := b.TempDir()
			ctx := context.Background()
			b.SetBytes(int64(dataSize))
			b.ResetTimer()

			for i := 0; i < b.N; i++ {
				dst, err := os.Create(filepath.Join(tmpDir, "dst.bin"))
				if err != nil {
					b.Fatal(err)
				}
				src := bytes.NewReader(data)
				if _, err := copyCtxBuf(ctx, dst, src, bs.size); err != nil {
					_ = dst.Close()
					b.Fatal(err)
				}
				_ = dst.Close()
			}
		})
	}
}

// copyCtxBuf is copyCtx with a configurable buffer size for benchmarking.
func copyCtxBuf(ctx context.Context, dst *os.File, src *bytes.Reader, bufSize int) (int64, error) {
	buf := make([]byte, bufSize)
	var total int64
	for {
		select {
		case <-ctx.Done():
			return total, ctx.Err()
		default:
		}
		n, readErr := src.Read(buf)
		if n > 0 {
			w, writeErr := dst.Write(buf[:n])
			total += int64(w)
			if writeErr != nil {
				return total, writeErr
			}
		}
		if readErr == io.EOF {
			return total, nil
		}
		if readErr != nil {
			return total, readErr
		}
	}
}
