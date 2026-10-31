package engine

import (
	"fmt"
	"os"
	"sync"
	"time"

	waLog "go.mau.fi/whatsmeow/util/log"
)

// fileLog keeps warnings and errors in <dataDir>/engine.log (truncated past 512 KB),
// the Android counterpart of the POC's data/wa.log.
type fileLog struct {
	path   string
	module string
	mu     *sync.Mutex
}

func newFileLog(path string) *fileLog { return &fileLog{path: path, mu: &sync.Mutex{}} }

func (l *fileLog) write(level, msg string, args ...any) {
	l.mu.Lock()
	defer l.mu.Unlock()
	if st, err := os.Stat(l.path); err == nil && st.Size() > 512<<10 {
		_ = os.Truncate(l.path, 0)
	}
	f, err := os.OpenFile(l.path, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0o600)
	if err != nil {
		return
	}
	defer f.Close()
	fmt.Fprintf(f, "%s %s [%s] %s\n", time.Now().Format(time.RFC3339), level, l.module, fmt.Sprintf(msg, args...))
}

func (l *fileLog) Warnf(msg string, args ...any)  { l.write("WARN", msg, args...) }
func (l *fileLog) Errorf(msg string, args ...any) { l.write("ERROR", msg, args...) }
func (l *fileLog) Infof(msg string, args ...any)  {}
func (l *fileLog) Debugf(msg string, args ...any) {}
func (l *fileLog) Sub(module string) waLog.Logger {
	m := module
	if l.module != "" {
		m = l.module + "/" + module
	}
	return &fileLog{path: l.path, module: m, mu: l.mu}
}
