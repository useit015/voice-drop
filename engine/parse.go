package engine

import (
	"context"
	"encoding/base64"
	"strings"

	"go.mau.fi/whatsmeow/proto/waE2E"
	"go.mau.fi/whatsmeow/types"
	"go.mau.fi/whatsmeow/types/events"
	"google.golang.org/protobuf/proto"
)

// Row is one stored/shown message; mirrors the POC's msgs table (poc/lib/store.mjs).
type Row struct {
	Jid       string   `json:"jid"`
	ID        string   `json:"id"`
	FromMe    bool     `json:"fromMe"`
	Ts        int64    `json:"ts"` // ms
	Sender    string   `json:"sender,omitempty"`
	Push      string   `json:"push,omitempty"`
	Kind      string   `json:"kind"` // text voice audio image video gif sticker document contact location poll other
	Text      string   `json:"text,omitempty"`
	Meta      *Meta    `json:"meta,omitempty"`
	Raw       string   `json:"raw,omitempty"` // base64 waE2E.Message, only for downloadable media
	Quote     *Quote   `json:"quote,omitempty"`
	Mentions  []string `json:"mentions,omitempty"`
	Forwarded bool     `json:"forwarded,omitempty"`
}

type Meta struct {
	Seconds  uint32   `json:"seconds,omitempty"`
	Wave     string   `json:"wave,omitempty"`  // base64 of 64 bars 0-100
	Thumb    string   `json:"thumb,omitempty"` // base64 jpeg
	W        uint32   `json:"w,omitempty"`
	H        uint32   `json:"h,omitempty"`
	Mime     string   `json:"mime,omitempty"`
	Size     uint64   `json:"size,omitempty"`
	Name     string   `json:"name,omitempty"`
	Lat      float64  `json:"lat,omitempty"`
	Lng      float64  `json:"lng,omitempty"`
	Options  []string `json:"options,omitempty"`
	URL      string   `json:"url,omitempty"` // link preview
	Title    string   `json:"title,omitempty"`
	Desc     string   `json:"desc,omitempty"`
	Animated bool     `json:"animated,omitempty"`
	ViewOnce bool     `json:"viewOnce,omitempty"`
}

type Quote struct {
	ID     string `json:"id"`
	Sender string `json:"sender,omitempty"`
	Kind   string `json:"kind,omitempty"`
	Text   string `json:"text,omitempty"`
}

type Reaction struct {
	Jid    string `json:"jid"`
	ID     string `json:"id"` // target message
	Sender string `json:"sender"`
	FromMe bool   `json:"fromMe"`
	Emoji  string `json:"emoji"` // empty = removed
	Ts     int64  `json:"ts"`
}

type Revoke struct {
	Jid string `json:"jid"`
	ID  string `json:"id"`
}

type Edit struct {
	Jid  string `json:"jid"`
	ID   string `json:"id"`
	Text string `json:"text"`
}

// parsed is what one incoming message turns into: at most one of these is set.
type parsed struct {
	row      *Row
	revoke   *Revoke
	edit     *Edit
	reaction *Reaction
}

// norm keeps one JID per person: device suffix dropped, LIDs mapped to phone numbers when known.
func (e *eng) norm(j types.JID, alt types.JID) types.JID {
	if j.IsEmpty() {
		return j
	}
	j = j.ToNonAD()
	if j.Server != types.HiddenUserServer {
		return j
	}
	if !alt.IsEmpty() && alt.Server == types.DefaultUserServer {
		return alt.ToNonAD()
	}
	if pn, err := e.cli.Store.LIDs.GetPNForLID(context.Background(), j); err == nil && !pn.IsEmpty() {
		return pn.ToNonAD()
	}
	return j
}

func (e *eng) normStr(s string) string {
	if s == "" {
		return ""
	}
	j, err := types.ParseJID(s)
	if err != nil {
		return s
	}
	return e.norm(j, types.EmptyJID).String()
}

func (e *eng) chatOf(info *types.MessageInfo) types.JID {
	alt := types.EmptyJID
	if !info.IsGroup {
		if info.IsFromMe {
			alt = info.RecipientAlt
		} else {
			alt = info.SenderAlt
		}
	}
	return e.norm(info.Chat, alt)
}

func skipChat(j types.JID) bool {
	return j.IsEmpty() || j.Server == types.NewsletterServer || j.Server == types.BroadcastServer
}

type ctxHolder interface{ GetContextInfo() *waE2E.ContextInfo }

func (e *eng) parseEvent(evt *events.Message) *parsed {
	info := &evt.Info
	chat := e.chatOf(info)
	if skipChat(chat) || evt.Message == nil {
		return nil
	}
	m := evt.Message
	jid := chat.String()

	if p := m.GetProtocolMessage(); p != nil {
		switch p.GetType() {
		case waE2E.ProtocolMessage_REVOKE:
			if id := p.GetKey().GetID(); id != "" {
				return &parsed{revoke: &Revoke{Jid: jid, ID: id}}
			}
		case waE2E.ProtocolMessage_MESSAGE_EDIT:
			if id := p.GetKey().GetID(); id != "" {
				if text, ok := textOf(p.GetEditedMessage()); ok {
					return &parsed{edit: &Edit{Jid: jid, ID: id, Text: text}}
				}
			}
		}
		return nil
	}
	if r := m.GetReactionMessage(); r != nil {
		sender := e.norm(info.Sender, info.SenderAlt).String()
		if info.IsFromMe {
			sender = e.me()
		}
		return &parsed{reaction: &Reaction{Jid: jid, ID: r.GetKey().GetID(), Sender: sender, FromMe: info.IsFromMe, Emoji: r.GetText(), Ts: info.Timestamp.UnixMilli()}}
	}

	row := &Row{Jid: jid, ID: info.ID, FromMe: info.IsFromMe, Ts: info.Timestamp.UnixMilli(), Push: info.PushName, Kind: "other"}
	if info.IsGroup && !info.IsFromMe {
		row.Sender = e.norm(info.Sender, info.SenderAlt).String()
	}
	if !fillRow(row, m) {
		return nil
	}
	if evt.IsViewOnce {
		if row.Meta == nil {
			row.Meta = &Meta{}
		}
		row.Meta.ViewOnce = true
	}
	if ci := contextOf(m); ci != nil {
		if id := ci.GetStanzaID(); id != "" {
			q := &Quote{ID: id, Sender: e.normStr(ci.GetParticipant())}
			if qm := ci.GetQuotedMessage(); qm != nil {
				tmp := &Row{}
				fillRow(tmp, qm)
				q.Kind, q.Text = tmp.Kind, tmp.Text
			}
			row.Quote = q
		}
		for _, mj := range ci.GetMentionedJID() {
			row.Mentions = append(row.Mentions, e.normStr(mj))
		}
		row.Forwarded = ci.GetIsForwarded()
	}
	if isMedia(row.Kind) {
		if b, err := proto.Marshal(m); err == nil {
			row.Raw = base64.StdEncoding.EncodeToString(b)
		}
	}
	return &parsed{row: row}
}

func isMedia(kind string) bool {
	switch kind {
	case "voice", "audio", "image", "video", "gif", "sticker", "document":
		return true
	}
	return false
}

func b64(b []byte) string {
	if len(b) == 0 {
		return ""
	}
	return base64.StdEncoding.EncodeToString(b)
}

func textOf(m *waE2E.Message) (string, bool) {
	if m == nil {
		return "", false
	}
	if m.Conversation != nil {
		return m.GetConversation(), true
	}
	if t := m.GetExtendedTextMessage(); t != nil {
		return t.GetText(), true
	}
	return "", false
}

func contextOf(m *waE2E.Message) *waE2E.ContextInfo {
	for _, c := range []ctxHolder{
		m.GetExtendedTextMessage(), m.GetImageMessage(), m.GetVideoMessage(), m.GetAudioMessage(),
		m.GetDocumentMessage(), m.GetStickerMessage(), m.GetContactMessage(), m.GetLocationMessage(),
	} {
		if c == nil || isNilPtr(c) {
			continue
		}
		if ci := c.GetContextInfo(); ci != nil {
			return ci
		}
	}
	return nil
}

// A typed nil pointer inside an interface is not == nil.
func isNilPtr(c ctxHolder) bool {
	switch v := c.(type) {
	case *waE2E.ExtendedTextMessage:
		return v == nil
	case *waE2E.ImageMessage:
		return v == nil
	case *waE2E.VideoMessage:
		return v == nil
	case *waE2E.AudioMessage:
		return v == nil
	case *waE2E.DocumentMessage:
		return v == nil
	case *waE2E.StickerMessage:
		return v == nil
	case *waE2E.ContactMessage:
		return v == nil
	case *waE2E.LocationMessage:
		return v == nil
	}
	return false
}

// fillRow sets kind/text/meta from message content; false for things we don't show.
func fillRow(row *Row, m *waE2E.Message) bool {
	switch {
	case m.Conversation != nil:
		row.Kind, row.Text = "text", m.GetConversation()
	case m.GetExtendedTextMessage() != nil:
		t := m.GetExtendedTextMessage()
		row.Kind, row.Text = "text", t.GetText()
		if t.GetMatchedText() != "" {
			row.Meta = &Meta{URL: t.GetMatchedText(), Title: t.GetTitle(), Desc: t.GetDescription(), Thumb: b64(t.GetJPEGThumbnail())}
		}
	case m.GetAudioMessage() != nil:
		a := m.GetAudioMessage()
		row.Kind = "audio"
		if a.GetPTT() {
			row.Kind = "voice"
		}
		row.Meta = &Meta{Seconds: a.GetSeconds(), Wave: b64(a.GetWaveform()), Mime: a.GetMimetype(), Size: a.GetFileLength()}
	case m.GetImageMessage() != nil:
		i := m.GetImageMessage()
		row.Kind, row.Text = "image", i.GetCaption()
		row.Meta = &Meta{Thumb: b64(i.GetJPEGThumbnail()), W: i.GetWidth(), H: i.GetHeight(), Mime: or(i.GetMimetype(), "image/jpeg"), Size: i.GetFileLength()}
	case m.GetVideoMessage() != nil:
		v := m.GetVideoMessage()
		row.Kind, row.Text = "video", v.GetCaption()
		if v.GetGifPlayback() {
			row.Kind = "gif"
		}
		row.Meta = &Meta{Thumb: b64(v.GetJPEGThumbnail()), W: v.GetWidth(), H: v.GetHeight(), Seconds: v.GetSeconds(), Mime: or(v.GetMimetype(), "video/mp4"), Size: v.GetFileLength()}
	case m.GetStickerMessage() != nil:
		s := m.GetStickerMessage()
		row.Kind = "sticker"
		row.Meta = &Meta{W: s.GetWidth(), H: s.GetHeight(), Mime: or(s.GetMimetype(), "image/webp"), Size: s.GetFileLength(), Animated: s.GetIsAnimated(), Thumb: b64(s.GetPngThumbnail())}
	case m.GetDocumentMessage() != nil:
		d := m.GetDocumentMessage()
		row.Kind, row.Text = "document", d.GetCaption()
		row.Meta = &Meta{Name: or(d.GetFileName(), or(d.GetTitle(), "document")), Mime: or(d.GetMimetype(), "application/octet-stream"), Size: d.GetFileLength(), Thumb: b64(d.GetJPEGThumbnail())}
	case m.GetContactMessage() != nil:
		row.Kind, row.Text = "contact", or(m.GetContactMessage().GetDisplayName(), "Contact")
	case m.GetContactsArrayMessage() != nil:
		row.Kind, row.Text = "contact", or(m.GetContactsArrayMessage().GetDisplayName(), "Contacts")
	case m.GetLocationMessage() != nil:
		l := m.GetLocationMessage()
		row.Kind, row.Text = "location", or(l.GetName(), or(l.GetAddress(), "Location"))
		row.Meta = &Meta{Lat: l.GetDegreesLatitude(), Lng: l.GetDegreesLongitude(), Thumb: b64(l.GetJPEGThumbnail())}
	case m.GetLiveLocationMessage() != nil:
		l := m.GetLiveLocationMessage()
		row.Kind, row.Text = "location", "Live location"
		row.Meta = &Meta{Lat: l.GetDegreesLatitude(), Lng: l.GetDegreesLongitude()}
	case pollOf(m) != nil:
		p := pollOf(m)
		row.Kind, row.Text = "poll", or(p.GetName(), "Poll")
		opts := make([]string, 0, len(p.GetOptions()))
		for _, o := range p.GetOptions() {
			opts = append(opts, o.GetOptionName())
		}
		row.Meta = &Meta{Options: opts}
	default:
		return false
	}
	row.Text = strings.ToValidUTF8(row.Text, "")
	return true
}

func pollOf(m *waE2E.Message) *waE2E.PollCreationMessage {
	if p := m.GetPollCreationMessage(); p != nil {
		return p
	}
	if p := m.GetPollCreationMessageV2(); p != nil {
		return p
	}
	return m.GetPollCreationMessageV3()
}

func or(a, b string) string {
	if a != "" {
		return a
	}
	return b
}

func (e *eng) me() string {
	if id := e.cli.Store.ID; id != nil {
		return id.ToNonAD().String()
	}
	return ""
}
