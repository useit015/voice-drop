package engine

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"sort"
	"strings"
	"time"

	"go.mau.fi/whatsmeow"
	"go.mau.fi/whatsmeow/proto/waE2E"
	"go.mau.fi/whatsmeow/types"
	"go.mau.fi/whatsmeow/types/events"
	"google.golang.org/protobuf/proto"
)

// mediaPart returns the downloadable sub-message and a setter for its direct path.
func mediaPart(m *waE2E.Message) (whatsmeow.DownloadableMessage, func(string)) {
	switch {
	case m.GetImageMessage() != nil:
		x := m.GetImageMessage()
		return x, func(p string) { x.DirectPath = &p }
	case m.GetVideoMessage() != nil:
		x := m.GetVideoMessage()
		return x, func(p string) { x.DirectPath = &p }
	case m.GetAudioMessage() != nil:
		x := m.GetAudioMessage()
		return x, func(p string) { x.DirectPath = &p }
	case m.GetDocumentMessage() != nil:
		x := m.GetDocumentMessage()
		return x, func(p string) { x.DirectPath = &p }
	case m.GetStickerMessage() != nil:
		x := m.GetStickerMessage()
		return x, func(p string) { x.DirectPath = &p }
	}
	return nil, nil
}

// downloadMedia decrypts an attachment to `out`. Media gone from WhatsApp's servers is
// re-requested from the phone once; the refreshed raw message is returned so the app can store it.
func (e *eng) downloadMedia(raw json.RawMessage) (any, error) {
	a, err := parse[struct {
		Raw    string `json:"raw"`
		Out    string `json:"out"`
		Jid    string `json:"jid"`
		ID     string `json:"id"`
		FromMe bool   `json:"fromMe"`
		Sender string `json:"sender"`
	}](raw)
	if err != nil {
		return nil, err
	}
	b, err := base64.StdEncoding.DecodeString(a.Raw)
	if err != nil {
		return nil, err
	}
	var m waE2E.Message
	if err := proto.Unmarshal(b, &m); err != nil {
		return nil, err
	}
	part, setPath := mediaPart(&m)
	if part == nil {
		return nil, errors.New("not a media message")
	}
	if err := e.requireOpen(); err != nil {
		return nil, err
	}
	ctx := context.Background()
	data, err := e.cli.Download(ctx, part)
	refreshed := ""
	if errors.Is(err, whatsmeow.ErrMediaDownloadFailedWith404) || errors.Is(err, whatsmeow.ErrMediaDownloadFailedWith410) {
		path, rerr := e.mediaRetry(ctx, a.Jid, a.ID, a.FromMe, a.Sender, part.GetMediaKey())
		if rerr != nil {
			return nil, fmt.Errorf("this attachment is no longer available on WhatsApp")
		}
		setPath(path)
		if nb, merr := proto.Marshal(&m); merr == nil {
			refreshed = base64.StdEncoding.EncodeToString(nb)
		}
		data, err = e.cli.Download(ctx, part)
	}
	if err != nil {
		return nil, err
	}
	if err := writeAtomic(a.Out, data); err != nil {
		return nil, err
	}
	return map[string]any{"path": a.Out, "size": len(data), "raw": refreshed}, nil
}

func (e *eng) mediaRetry(ctx context.Context, jid, id string, fromMe bool, sender string, key []byte) (string, error) {
	chat, err := parseJID(jid)
	if err != nil {
		return "", err
	}
	info := &types.MessageInfo{MessageSource: types.MessageSource{Chat: chat, IsFromMe: fromMe, IsGroup: chat.Server == types.GroupServer}, ID: id}
	if sender != "" {
		info.Sender, _ = types.ParseJID(sender)
	} else if fromMe {
		info.Sender = *e.cli.Store.ID
	} else {
		info.Sender = chat
	}
	ch := make(chan *events.MediaRetry, 1)
	e.retries.Store(id, ch)
	defer e.retries.Delete(id)
	if err := e.cli.SendMediaRetryReceipt(ctx, info, key); err != nil {
		return "", err
	}
	select {
	case evt := <-ch:
		notif, err := whatsmeow.DecryptMediaRetryNotification(evt, key)
		if err != nil {
			return "", err
		}
		if notif.GetDirectPath() == "" {
			return "", errors.New("phone has no copy")
		}
		return notif.GetDirectPath(), nil
	case <-time.After(30 * time.Second):
		return "", errors.New("phone did not answer")
	}
}

// avatar saves the small profile photo to `out`. {"none":true} when there is none or it is hidden.
func (e *eng) avatar(raw json.RawMessage) (any, error) {
	a, err := parse[struct {
		Jid      string `json:"jid"`
		Out      string `json:"out"`
		Existing string `json:"existing"`
		Full     bool   `json:"full"`
	}](raw)
	if err != nil {
		return nil, err
	}
	j, err := parseJID(a.Jid)
	if err != nil {
		return nil, err
	}
	if err := e.requireOpen(); err != nil {
		return nil, err
	}
	info, err := e.cli.GetProfilePictureInfo(context.Background(), j, &whatsmeow.GetProfilePictureParams{Preview: !a.Full, ExistingID: a.Existing})
	if errors.Is(err, whatsmeow.ErrProfilePictureNotSet) || errors.Is(err, whatsmeow.ErrProfilePictureUnauthorized) {
		return map[string]any{"none": true}, nil
	}
	if err != nil {
		return nil, err
	}
	if info == nil { // unchanged since `existing`
		return map[string]any{"unchanged": true}, nil
	}
	res, err := http.Get(info.URL)
	if err != nil {
		return nil, err
	}
	defer res.Body.Close()
	if res.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("avatar download: %s", res.Status)
	}
	data, err := io.ReadAll(io.LimitReader(res.Body, 8<<20))
	if err != nil {
		return nil, err
	}
	if err := writeAtomic(a.Out, data); err != nil {
		return nil, err
	}
	return map[string]any{"path": a.Out, "id": info.ID}, nil
}

// contacts returns the address book WhatsApp synced to this device (names from the phone, push names).
func (e *eng) contacts(_ json.RawMessage) (any, error) {
	all, err := e.cli.Store.Contacts.GetAllContacts(context.Background())
	if err != nil {
		return nil, err
	}
	seen := map[string]*Contact{}
	for j, c := range all {
		n := e.norm(j, types.EmptyJID)
		if n.Server != types.DefaultUserServer && n.Server != types.HiddenUserServer {
			continue
		}
		key := n.String()
		cur := seen[key]
		if cur == nil {
			cur = &Contact{Jid: key}
			seen[key] = cur
		}
		cur.Name = or(cur.Name, or(c.FullName, or(c.FirstName, c.BusinessName)))
		cur.Notify = or(cur.Notify, c.PushName)
		if j.Server == types.HiddenUserServer {
			cur.Lid = j.String()
		}
	}
	out := make([]*Contact, 0, len(seen))
	for _, c := range seen {
		if c.Name != "" || c.Notify != "" || strings.HasSuffix(c.Jid, "@"+types.DefaultUserServer) {
			out = append(out, c)
		}
	}
	sort.Slice(out, func(i, k int) bool { return out[i].Jid < out[k].Jid })
	return out, nil
}

type groupOut struct {
	Jid          string        `json:"jid"`
	Name         string        `json:"name"`
	Topic        string        `json:"topic,omitempty"`
	Size         int           `json:"size"`
	Announce     bool          `json:"announce,omitempty"`
	Locked       bool          `json:"locked,omitempty"`
	Created      int64         `json:"created,omitempty"`
	Participants []participant `json:"participants,omitempty"`
}

type participant struct {
	Jid        string `json:"jid"`
	Admin      bool   `json:"admin,omitempty"`
	SuperAdmin bool   `json:"superAdmin,omitempty"`
}

func (e *eng) groupView(g *types.GroupInfo, withMembers bool) groupOut {
	o := groupOut{Jid: g.JID.String(), Name: g.Name, Topic: g.Topic, Size: len(g.Participants), Announce: g.IsAnnounce, Locked: g.IsLocked, Created: g.GroupCreated.UnixMilli()}
	if g.ParticipantCount > o.Size {
		o.Size = g.ParticipantCount
	}
	if withMembers {
		for _, p := range g.Participants {
			o.Participants = append(o.Participants, participant{Jid: e.norm(p.JID, p.PhoneNumber).String(), Admin: p.IsAdmin, SuperAdmin: p.IsSuperAdmin})
		}
	}
	return o
}

func (e *eng) groups(_ json.RawMessage) (any, error) {
	if err := e.requireOpen(); err != nil {
		return nil, err
	}
	gs, err := e.cli.GetJoinedGroups(context.Background())
	if err != nil {
		return nil, err
	}
	out := make([]groupOut, 0, len(gs))
	for _, g := range gs {
		out = append(out, e.groupView(g, false))
	}
	return out, nil
}

func (e *eng) groupInfo(raw json.RawMessage) (any, error) {
	a, err := parse[struct{ Jid string }](raw)
	if err != nil {
		return nil, err
	}
	j, err := parseJID(a.Jid)
	if err != nil {
		return nil, err
	}
	if err := e.requireOpen(); err != nil {
		return nil, err
	}
	g, err := e.cli.GetGroupInfo(context.Background(), j)
	if err != nil {
		return nil, err
	}
	return e.groupView(g, true), nil
}

// resolve turns a typed phone number into a WhatsApp JID.
func (e *eng) resolve(raw json.RawMessage) (any, error) {
	a, err := parse[struct{ Phone string }](raw)
	if err != nil {
		return nil, err
	}
	num := digitsOnly(a.Phone)
	if len(num) < 8 || len(num) > 15 {
		return nil, errors.New("enter the full number with country code")
	}
	if err := e.requireOpen(); err != nil {
		return nil, err
	}
	res, err := e.cli.IsOnWhatsApp(context.Background(), []string{"+" + num})
	if err != nil {
		return nil, err
	}
	if len(res) == 0 || !res[0].IsIn {
		return nil, fmt.Errorf("+%s is not on WhatsApp", num)
	}
	return map[string]any{"jid": res[0].JID.ToNonAD().String()}, nil
}

func writeAtomic(path string, data []byte) error {
	tmp := path + ".part"
	if err := os.WriteFile(tmp, data, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, path)
}
