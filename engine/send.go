package engine

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"strings"
	"time"

	"go.mau.fi/whatsmeow"
	"go.mau.fi/whatsmeow/appstate"
	"go.mau.fi/whatsmeow/proto/waCommon"
	"go.mau.fi/whatsmeow/proto/waE2E"
	"go.mau.fi/whatsmeow/types"
	"go.mau.fi/whatsmeow/types/events"
	"google.golang.org/protobuf/proto"
)

type quoteArg struct {
	ID     string `json:"id"`
	Sender string `json:"sender"` // empty for our own message
	FromMe bool   `json:"fromMe"`
	Text   string `json:"text"`
}

func (e *eng) requireOpen() error {
	if !e.cli.IsConnected() || !e.cli.IsLoggedIn() {
		return errors.New("not connected to WhatsApp yet")
	}
	return nil
}

func parseJID(s string) (types.JID, error) {
	j, err := types.ParseJID(s)
	if err != nil || j.User == "" {
		return types.EmptyJID, fmt.Errorf("bad jid %q", s)
	}
	return j, nil
}

func (e *eng) contextInfo(q *quoteArg, mentions []string) *waE2E.ContextInfo {
	if q == nil && len(mentions) == 0 {
		return nil
	}
	ci := &waE2E.ContextInfo{MentionedJID: mentions}
	if q != nil && q.ID != "" {
		participant := q.Sender
		if q.FromMe || participant == "" {
			participant = e.me()
		}
		ci.StanzaID = proto.String(q.ID)
		ci.Participant = proto.String(participant)
		ci.QuotedMessage = &waE2E.Message{Conversation: proto.String(q.Text)}
	}
	return ci
}

// send delivers a message and returns the row to store, built the same way as incoming ones.
// id may be empty; the app passes one from "newId" so it can show the message before the send finishes.
func (e *eng) send(to types.JID, msg *waE2E.Message, id string) (*Row, error) {
	if err := e.requireOpen(); err != nil {
		return nil, err
	}
	resp, err := e.cli.SendMessage(context.Background(), to, msg, whatsmeow.SendRequestExtra{ID: types.MessageID(id)})
	if err != nil {
		return nil, err
	}
	me := *e.cli.Store.ID
	evt := &events.Message{
		Info:    types.MessageInfo{MessageSource: types.MessageSource{Chat: to, Sender: me, IsFromMe: true, IsGroup: to.Server == types.GroupServer}, ID: resp.ID, Timestamp: resp.Timestamp},
		Message: msg,
	}
	p := e.parseEvent(evt)
	if p == nil || p.row == nil {
		return &Row{Jid: e.norm(to, types.EmptyJID).String(), ID: resp.ID, FromMe: true, Ts: resp.Timestamp.UnixMilli(), Kind: "other"}, nil
	}
	return p.row, nil
}

func (e *eng) sendText(raw json.RawMessage) (any, error) {
	a, err := parse[struct {
		Jid      string    `json:"jid"`
		ID       string    `json:"id"`
		Text     string    `json:"text"`
		Quote    *quoteArg `json:"quote"`
		Mentions []string  `json:"mentions"`
	}](raw)
	if err != nil {
		return nil, err
	}
	to, err := parseJID(a.Jid)
	if err != nil {
		return nil, err
	}
	text := strings.TrimRight(a.Text, " \n\t")
	if text == "" {
		return nil, errors.New("empty message")
	}
	msg := &waE2E.Message{}
	if ci := e.contextInfo(a.Quote, a.Mentions); ci != nil {
		msg.ExtendedTextMessage = &waE2E.ExtendedTextMessage{Text: proto.String(text), ContextInfo: ci}
	} else {
		msg.Conversation = proto.String(text)
	}
	return e.send(to, msg, a.ID)
}

type mediaArg struct {
	Jid      string    `json:"jid"`
	ID       string    `json:"id"`
	Kind     string    `json:"kind"` // image video gif voice audio document sticker
	Path     string    `json:"path"`
	Mime     string    `json:"mime"`
	Caption  string    `json:"caption"`
	Name     string    `json:"name"`
	Seconds  uint32    `json:"seconds"`
	Wave     string    `json:"wave"`  // base64
	Thumb    string    `json:"thumb"` // path to a small jpeg
	W        uint32    `json:"w"`
	H        uint32    `json:"h"`
	Animated bool      `json:"animated"`
	Quote    *quoteArg `json:"quote"`
}

func (e *eng) sendMedia(raw json.RawMessage) (any, error) {
	a, err := parse[mediaArg](raw)
	if err != nil {
		return nil, err
	}
	to, err := parseJID(a.Jid)
	if err != nil {
		return nil, err
	}
	if err := e.requireOpen(); err != nil {
		return nil, err
	}
	data, err := os.ReadFile(a.Path)
	if err != nil {
		return nil, err
	}
	var thumb []byte
	if a.Thumb != "" {
		thumb, _ = os.ReadFile(a.Thumb)
	}
	wave, _ := base64.StdEncoding.DecodeString(a.Wave)

	mt := whatsmeow.MediaImage
	switch a.Kind {
	case "video", "gif":
		mt = whatsmeow.MediaVideo
	case "voice", "audio":
		mt = whatsmeow.MediaAudio
	case "document":
		mt = whatsmeow.MediaDocument
	}
	up, err := e.cli.Upload(context.Background(), data, mt)
	if err != nil {
		return nil, fmt.Errorf("upload: %w", err)
	}
	ci := e.contextInfo(a.Quote, nil)
	size := proto.Uint64(up.FileLength)
	msg := &waE2E.Message{}
	switch a.Kind {
	case "image":
		msg.ImageMessage = &waE2E.ImageMessage{
			URL: &up.URL, DirectPath: &up.DirectPath, MediaKey: up.MediaKey, FileEncSHA256: up.FileEncSHA256, FileSHA256: up.FileSHA256, FileLength: size,
			Mimetype: proto.String(or(a.Mime, "image/jpeg")), Caption: optStr(a.Caption), JPEGThumbnail: thumb, Width: optU32(a.W), Height: optU32(a.H), ContextInfo: ci,
		}
	case "video", "gif":
		msg.VideoMessage = &waE2E.VideoMessage{
			URL: &up.URL, DirectPath: &up.DirectPath, MediaKey: up.MediaKey, FileEncSHA256: up.FileEncSHA256, FileSHA256: up.FileSHA256, FileLength: size,
			Mimetype: proto.String(or(a.Mime, "video/mp4")), Caption: optStr(a.Caption), JPEGThumbnail: thumb, Width: optU32(a.W), Height: optU32(a.H),
			Seconds: optU32(a.Seconds), GifPlayback: proto.Bool(a.Kind == "gif"), ContextInfo: ci,
		}
		if a.Kind == "gif" {
			msg.VideoMessage.GifAttribution = waE2E.VideoMessage_GIPHY.Enum()
		}
	case "voice", "audio":
		msg.AudioMessage = &waE2E.AudioMessage{
			URL: &up.URL, DirectPath: &up.DirectPath, MediaKey: up.MediaKey, FileEncSHA256: up.FileEncSHA256, FileSHA256: up.FileSHA256, FileLength: size,
			Mimetype: proto.String(or(a.Mime, "audio/ogg; codecs=opus")), Seconds: optU32(a.Seconds), PTT: proto.Bool(a.Kind == "voice"), Waveform: wave, ContextInfo: ci,
		}
	case "document":
		msg.DocumentMessage = &waE2E.DocumentMessage{
			URL: &up.URL, DirectPath: &up.DirectPath, MediaKey: up.MediaKey, FileEncSHA256: up.FileEncSHA256, FileSHA256: up.FileSHA256, FileLength: size,
			Mimetype: proto.String(or(a.Mime, "application/octet-stream")), FileName: proto.String(or(a.Name, "file")), Title: proto.String(or(a.Name, "file")),
			Caption: optStr(a.Caption), JPEGThumbnail: thumb, ContextInfo: ci,
		}
	case "sticker":
		msg.StickerMessage = &waE2E.StickerMessage{
			URL: &up.URL, DirectPath: &up.DirectPath, MediaKey: up.MediaKey, FileEncSHA256: up.FileEncSHA256, FileSHA256: up.FileSHA256, FileLength: size,
			Mimetype: proto.String("image/webp"), Width: optU32(a.W), Height: optU32(a.H), IsAnimated: proto.Bool(a.Animated), ContextInfo: ci,
		}
	default:
		return nil, fmt.Errorf("unknown media kind %q", a.Kind)
	}
	return e.send(to, msg, a.ID)
}

func optStr(s string) *string {
	if s == "" {
		return nil
	}
	return &s
}

func optU32(v uint32) *uint32 {
	if v == 0 {
		return nil
	}
	return &v
}

type msgRef struct {
	Jid    string `json:"jid"`
	ID     string `json:"id"`
	Sender string `json:"sender"`
	FromMe bool   `json:"fromMe"`
}

func (e *eng) senderOf(r msgRef) types.JID {
	if r.FromMe || r.Sender == "" {
		return *e.cli.Store.ID
	}
	j, _ := types.ParseJID(r.Sender)
	return j
}

func (e *eng) react(raw json.RawMessage) (any, error) {
	a, err := parse[struct {
		msgRef
		Emoji string `json:"emoji"`
	}](raw)
	if err != nil {
		return nil, err
	}
	chat, err := parseJID(a.Jid)
	if err != nil {
		return nil, err
	}
	if err := e.requireOpen(); err != nil {
		return nil, err
	}
	_, err = e.cli.SendMessage(context.Background(), chat, e.cli.BuildReaction(chat, e.senderOf(a.msgRef), a.ID, a.Emoji))
	return nil, err
}

func (e *eng) edit(raw json.RawMessage) (any, error) {
	a, err := parse[struct {
		Jid  string `json:"jid"`
		ID   string `json:"id"`
		Text string `json:"text"`
	}](raw)
	if err != nil {
		return nil, err
	}
	chat, err := parseJID(a.Jid)
	if err != nil {
		return nil, err
	}
	if err := e.requireOpen(); err != nil {
		return nil, err
	}
	_, err = e.cli.SendMessage(context.Background(), chat, e.cli.BuildEdit(chat, a.ID, &waE2E.Message{Conversation: proto.String(a.Text)}))
	return nil, err
}

// Delete for everyone (own messages, or others' when group admin).
func (e *eng) revoke(raw json.RawMessage) (any, error) {
	a, err := parse[msgRef](raw)
	if err != nil {
		return nil, err
	}
	chat, err := parseJID(a.Jid)
	if err != nil {
		return nil, err
	}
	if err := e.requireOpen(); err != nil {
		return nil, err
	}
	sender := types.EmptyJID
	if !a.FromMe {
		sender = e.senderOf(a)
	}
	_, err = e.cli.SendMessage(context.Background(), chat, e.cli.BuildRevoke(chat, sender, a.ID))
	return nil, err
}

func (e *eng) markRead(raw json.RawMessage) (any, error) {
	a, err := parse[struct {
		Jid    string   `json:"jid"`
		IDs    []string `json:"ids"`
		Sender string   `json:"sender"` // group: the author of these messages
		Played bool     `json:"played"`
	}](raw)
	if err != nil {
		return nil, err
	}
	chat, err := parseJID(a.Jid)
	if err != nil {
		return nil, err
	}
	if len(a.IDs) == 0 || e.requireOpen() != nil {
		return nil, nil
	}
	sender := chat
	if a.Sender != "" {
		sender, _ = types.ParseJID(a.Sender)
	}
	var extra []types.ReceiptType
	if a.Played {
		extra = append(extra, types.ReceiptTypePlayed)
	}
	return nil, e.cli.MarkRead(context.Background(), a.IDs, time.Now(), chat, sender, extra...)
}

func (e *eng) typing(raw json.RawMessage) (any, error) {
	a, err := parse[struct {
		Jid   string `json:"jid"`
		State string `json:"state"` // composing | recording | paused
	}](raw)
	if err != nil {
		return nil, err
	}
	chat, err := parseJID(a.Jid)
	if err != nil || e.requireOpen() != nil {
		return nil, err
	}
	state, media := types.ChatPresenceComposing, types.ChatPresenceMediaText
	switch a.State {
	case "recording":
		media = types.ChatPresenceMediaAudio
	case "paused":
		state = types.ChatPresencePaused
	}
	return nil, e.cli.SendChatPresence(context.Background(), chat, state, media)
}

func (e *eng) subscribePresence(raw json.RawMessage) (any, error) {
	a, err := parse[struct{ Jid string }](raw)
	if err != nil {
		return nil, err
	}
	j, err := parseJID(a.Jid)
	if err != nil || e.requireOpen() != nil {
		return nil, err
	}
	return nil, e.cli.SubscribePresence(context.Background(), j)
}

// Ask the phone for `count` messages older than the anchor (the oldest stored one).
// The answer arrives later as a "msgs" event with onDemand=true.
func (e *eng) fetchHistory(raw json.RawMessage) (any, error) {
	a, err := parse[struct {
		Jid    string `json:"jid"`
		ID     string `json:"id"`
		FromMe bool   `json:"fromMe"`
		Sender string `json:"sender"`
		Ts     int64  `json:"ts"`
		Count  int    `json:"count"`
	}](raw)
	if err != nil {
		return nil, err
	}
	chat, err := parseJID(a.Jid)
	if err != nil {
		return nil, err
	}
	if err := e.requireOpen(); err != nil {
		return nil, err
	}
	if a.Count <= 0 || a.Count > 500 {
		a.Count = 50
	}
	info := &types.MessageInfo{
		MessageSource: types.MessageSource{Chat: chat, IsFromMe: a.FromMe, IsGroup: chat.Server == types.GroupServer},
		ID:            a.ID, Timestamp: time.UnixMilli(a.Ts),
	}
	if a.Sender != "" {
		info.Sender, _ = types.ParseJID(a.Sender)
	}
	_, err = e.cli.SendPeerMessage(context.Background(), e.cli.BuildHistorySyncRequest(info, a.Count))
	return nil, err
}

// Pin / archive / mute / mark read-unread, synced to every device through app state.
func (e *eng) setChat(raw json.RawMessage) (any, error) {
	a, err := parse[struct {
		Jid        string `json:"jid"`
		Pinned     *bool  `json:"pinned"`
		Archived   *bool  `json:"archived"`
		MuteMs     *int64 `json:"muteMs"` // 0 = unmute, -1 = forever
		Read       *bool  `json:"read"`
		LastID     string `json:"lastId"`
		LastTs     int64  `json:"lastTs"`
		LastFromMe bool   `json:"lastFromMe"`
	}](raw)
	if err != nil {
		return nil, err
	}
	chat, err := parseJID(a.Jid)
	if err != nil {
		return nil, err
	}
	if err := e.requireOpen(); err != nil {
		return nil, err
	}
	ctx := context.Background()
	var key *waCommon.MessageKey
	if a.LastID != "" {
		key = &waCommon.MessageKey{RemoteJID: proto.String(chat.String()), FromMe: proto.Bool(a.LastFromMe), ID: proto.String(a.LastID)}
	}
	last := time.UnixMilli(a.LastTs)
	var patches []appstate.PatchInfo
	if a.Pinned != nil {
		patches = append(patches, appstate.BuildPin(chat, *a.Pinned))
	}
	if a.Archived != nil {
		patches = append(patches, appstate.BuildArchive(chat, *a.Archived, last, key))
	}
	if a.MuteMs != nil {
		d := time.Duration(*a.MuteMs) * time.Millisecond
		if *a.MuteMs < 0 {
			d = 0
		}
		patches = append(patches, appstate.BuildMute(chat, *a.MuteMs != 0, d))
	}
	if a.Read != nil {
		patches = append(patches, appstate.BuildMarkChatAsRead(chat, *a.Read, last, key))
	}
	for _, p := range patches {
		if err := e.cli.SendAppState(ctx, p); err != nil {
			return nil, err
		}
	}
	return nil, nil
}
