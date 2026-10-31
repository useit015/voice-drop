// Package engine wraps whatsmeow behind a tiny JSON bridge that gomobile can bind:
// Start/Stop, Call(method, json) -> json, and a Listener that receives events as (kind, json).
// The engine keeps only the WhatsApp session; every chat/message row is handed to the app to store.
package engine

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"time"

	_ "github.com/mattn/go-sqlite3"
	"go.mau.fi/whatsmeow"
	"go.mau.fi/whatsmeow/proto/waCompanionReg"
	"go.mau.fi/whatsmeow/store"
	"go.mau.fi/whatsmeow/store/sqlstore"
	"google.golang.org/protobuf/proto"
)

// Listener receives every event the engine emits. Called from engine goroutines.
type Listener interface {
	OnEvent(kind string, payload string)
}

type eng struct {
	dir       string
	listener  Listener
	container *sqlstore.Container
	cli       *whatsmeow.Client
	log       *fileLog

	mu       sync.Mutex
	status   string // unpaired | connecting | qr | open | disconnected | logged-out
	qrCancel context.CancelFunc

	retries sync.Map // message id -> chan *events.MediaRetry
}

var (
	startMu sync.Mutex
	cur     *eng
)

// Start opens the session store. It does not connect; call "connect" or one of the pairing methods.
// Calling it again only swaps the listener.
func Start(dataDir string, l Listener) error {
	startMu.Lock()
	defer startMu.Unlock()
	if cur != nil {
		cur.listener = l
		return nil
	}
	if err := os.MkdirAll(dataDir, 0o700); err != nil {
		return err
	}
	log := newFileLog(filepath.Join(dataDir, "engine.log"))
	db, err := sql.Open("sqlite3", "file:"+filepath.Join(dataDir, "session.db")+"?_foreign_keys=on&_journal_mode=WAL&_busy_timeout=5000")
	if err != nil {
		return err
	}
	ctx := context.Background()
	container := sqlstore.NewWithDB(db, "sqlite3", log.Sub("store"))
	if err := container.Upgrade(ctx); err != nil {
		return fmt.Errorf("session store: %w", err)
	}
	dev, err := container.GetFirstDevice(ctx)
	if err != nil {
		return err
	}
	store.SetOSInfo("Voice Drop", [3]uint32{1, 0, 0})
	store.DeviceProps.PlatformType = waCompanionReg.DeviceProps_CHROME.Enum()
	store.DeviceProps.RequireFullSync = proto.Bool(false)

	cli := whatsmeow.NewClient(dev, log.Sub("client"))
	cli.EmitAppStateEventsOnFullSync = true
	cli.AutomaticMessageRerequestFromPhone = true

	e := &eng{dir: dataDir, listener: l, container: container, cli: cli, log: log, status: "disconnected"}
	if dev.ID == nil {
		e.status = "unpaired"
	}
	cli.AddEventHandler(e.handle)
	cur = e
	return nil
}

// Stop disconnects. The session store stays open so Start/connect can resume quickly.
func Stop() {
	if e := get(); e != nil {
		e.cli.Disconnect()
		e.setStatus("disconnected")
	}
}

// Call runs one method with JSON args and returns a JSON result.
func Call(method string, args string) (string, error) {
	e := get()
	if e == nil {
		return "", errors.New("engine not started")
	}
	h, ok := methods[method]
	if !ok {
		return "", fmt.Errorf("unknown method %q", method)
	}
	raw := json.RawMessage(args)
	if args == "" {
		raw = json.RawMessage("{}")
	}
	out, err := h(e, raw)
	if err != nil {
		e.log.Warnf("call %s: %v", method, err)
		return "", err
	}
	if out == nil {
		out = map[string]any{"ok": true}
	}
	b, err := json.Marshal(out)
	return string(b), err
}

func get() *eng {
	startMu.Lock()
	defer startMu.Unlock()
	return cur
}

func (e *eng) emit(kind string, v any) {
	b, err := json.Marshal(v)
	if err != nil {
		e.log.Errorf("emit %s: %v", kind, err)
		return
	}
	if l := e.listener; l != nil {
		l.OnEvent(kind, string(b))
	}
}

func (e *eng) setStatus(s string) {
	e.mu.Lock()
	e.status = s
	e.mu.Unlock()
	e.emit("status", e.state())
}

func (e *eng) state() map[string]any {
	e.mu.Lock()
	status := e.status
	e.mu.Unlock()
	out := map[string]any{"status": status}
	if id := e.cli.Store.ID; id != nil {
		out["me"] = map[string]any{"jid": id.ToNonAD().String(), "name": e.cli.Store.PushName}
	}
	return out
}

// ---- methods ----

type handler func(e *eng, args json.RawMessage) (any, error)

var methods = map[string]handler{
	"state": func(e *eng, _ json.RawMessage) (any, error) { return e.state(), nil },
	"newId": func(e *eng, _ json.RawMessage) (any, error) {
		return map[string]string{"id": e.cli.GenerateMessageID()}, nil
	},
	"connect":           (*eng).connect,
	"disconnect":        func(e *eng, _ json.RawMessage) (any, error) { Stop(); return nil, nil },
	"pairQR":            (*eng).pairQR,
	"pairPhone":         (*eng).pairPhone,
	"logout":            (*eng).logout,
	"sendText":          (*eng).sendText,
	"sendMedia":         (*eng).sendMedia,
	"react":             (*eng).react,
	"edit":              (*eng).edit,
	"revoke":            (*eng).revoke,
	"markRead":          (*eng).markRead,
	"typing":            (*eng).typing,
	"subscribePresence": (*eng).subscribePresence,
	"fetchHistory":      (*eng).fetchHistory,
	"downloadMedia":     (*eng).downloadMedia,
	"avatar":            (*eng).avatar,
	"contacts":          (*eng).contacts,
	"groups":            (*eng).groups,
	"groupInfo":         (*eng).groupInfo,
	"resolve":           (*eng).resolve,
	"setChat":           (*eng).setChat,
}

func parse[T any](raw json.RawMessage) (T, error) {
	var v T
	err := json.Unmarshal(raw, &v)
	if err != nil {
		err = fmt.Errorf("bad args: %w", err)
	}
	return v, err
}

func (e *eng) connect(_ json.RawMessage) (any, error) {
	if e.cli.Store.ID == nil {
		e.setStatus("unpaired")
		return e.state(), nil
	}
	if e.cli.IsConnected() {
		return e.state(), nil
	}
	e.setStatus("connecting")
	if err := e.cli.Connect(); err != nil {
		e.setStatus("disconnected")
		return nil, err
	}
	return e.state(), nil
}

// Opens a fresh socket for linking; QR codes arrive as "qr" events until linked or cancelled.
func (e *eng) beginPairing() error {
	if e.cli.Store.ID != nil {
		return errors.New("already linked")
	}
	e.mu.Lock()
	if e.qrCancel != nil {
		e.qrCancel()
	}
	ctx, cancel := context.WithCancel(context.Background())
	e.qrCancel = cancel
	e.mu.Unlock()
	e.cli.Disconnect()
	ch, err := e.cli.GetQRChannel(ctx)
	if err != nil {
		cancel()
		return err
	}
	go func() {
		for item := range ch {
			switch item.Event {
			case whatsmeow.QRChannelEventCode:
				e.setStatus("qr")
				e.emit("qr", map[string]any{"code": item.Code, "timeoutMs": item.Timeout.Milliseconds()})
			case whatsmeow.QRChannelSuccess.Event:
				e.emit("paired", e.state())
			default:
				e.setStatus("unpaired")
				e.emit("pair-error", map[string]any{"event": item.Event, "error": errString(item.Error)})
			}
		}
	}()
	return e.cli.Connect()
}

func (e *eng) pairQR(_ json.RawMessage) (any, error) {
	return nil, e.beginPairing()
}

// Linking with an 8-character code typed into WhatsApp > Linked devices > Link with phone number.
func (e *eng) pairPhone(raw json.RawMessage) (any, error) {
	a, err := parse[struct{ Phone string }](raw)
	if err != nil {
		return nil, err
	}
	if err := e.beginPairing(); err != nil {
		return nil, err
	}
	// The socket must be up (first QR received) before the code can be requested.
	deadline := time.Now().Add(20 * time.Second)
	for e.state()["status"] != "qr" && time.Now().Before(deadline) {
		time.Sleep(100 * time.Millisecond)
	}
	code, err := e.cli.PairPhone(context.Background(), digitsOnly(a.Phone), true, whatsmeow.PairClientChrome, "Chrome (Linux)")
	if err != nil {
		return nil, err
	}
	return map[string]any{"code": code}, nil
}

func (e *eng) logout(_ json.RawMessage) (any, error) {
	ctx := context.Background()
	if err := e.cli.Logout(ctx); err != nil && e.cli.Store.ID != nil {
		// Server unreachable: drop the local session anyway so the user can relink.
		_ = e.cli.Store.Delete(ctx)
	}
	e.setStatus("unpaired")
	return nil, nil
}

func errString(err error) string {
	if err == nil {
		return ""
	}
	return err.Error()
}

func digitsOnly(s string) string {
	out := make([]rune, 0, len(s))
	for _, r := range s {
		if r >= '0' && r <= '9' {
			out = append(out, r)
		}
	}
	return string(out)
}
