package engine

import (
	"context"

	"go.mau.fi/whatsmeow/proto/waHistorySync"
	"go.mau.fi/whatsmeow/types"
	"go.mau.fi/whatsmeow/types/events"
)

// Chat is a partial chat-settings update; nil fields are unchanged.
type Chat struct {
	Jid          string  `json:"jid"`
	Name         *string `json:"name,omitempty"`
	Pinned       *bool   `json:"pinned,omitempty"`
	Archived     *bool   `json:"archived,omitempty"`
	MutedUntil   *int64  `json:"mutedUntil,omitempty"` // ms, 0 = not muted
	Unread       *int    `json:"unread,omitempty"`
	MarkedUnread *bool   `json:"markedUnread,omitempty"`
}

type Contact struct {
	Jid    string `json:"jid"`
	Name   string `json:"name,omitempty"`
	Notify string `json:"notify,omitempty"`
	Lid    string `json:"lid,omitempty"`
}

// batch collects one event's worth of rows and side effects, emitted together.
type batch struct {
	Rows      []*Row      `json:"rows"`
	Revokes   []*Revoke   `json:"revokes,omitempty"`
	Edits     []*Edit     `json:"edits,omitempty"`
	Reactions []*Reaction `json:"reactions,omitempty"`
	Live      bool        `json:"live"`
	OnDemand  bool        `json:"onDemand,omitempty"`
	Progress  *uint32     `json:"progress,omitempty"`
	SyncType  string      `json:"syncType,omitempty"`
}

func (b *batch) add(p *parsed) {
	switch {
	case p == nil:
	case p.row != nil:
		b.Rows = append(b.Rows, p.row)
	case p.revoke != nil:
		b.Revokes = append(b.Revokes, p.revoke)
	case p.edit != nil:
		b.Edits = append(b.Edits, p.edit)
	case p.reaction != nil:
		b.Reactions = append(b.Reactions, p.reaction)
	}
}

func (b *batch) empty() bool {
	return len(b.Rows)+len(b.Revokes)+len(b.Edits)+len(b.Reactions) == 0
}

func (e *eng) handle(raw any) {
	switch evt := raw.(type) {
	case *events.Connected:
		e.setStatus("open")
		_ = e.cli.SendPresence(context.Background(), types.PresenceAvailable)
	case *events.Disconnected:
		if e.state()["status"] == "open" {
			e.setStatus("connecting") // auto-reconnect is on
		}
	case *events.KeepAliveTimeout:
		e.emit("network", map[string]any{"ok": false})
	case *events.KeepAliveRestored:
		e.emit("network", map[string]any{"ok": true})
	case *events.LoggedOut:
		e.setStatus("logged-out")
	case *events.StreamReplaced:
		e.setStatus("disconnected")
		e.emit("error", map[string]any{"code": "replaced", "message": "This session was opened somewhere else"})
	case *events.TemporaryBan:
		e.emit("error", map[string]any{"code": "temp-ban", "message": evt.String()})
	case *events.ConnectFailure:
		e.emit("error", map[string]any{"code": "connect-failure", "message": evt.Message})
	case *events.ClientOutdated:
		e.emit("error", map[string]any{"code": "outdated", "message": "WhatsApp says this client version is outdated"})
	case *events.PairSuccess:
		e.emit("paired", map[string]any{"jid": evt.ID.ToNonAD().String()})
	case *events.OfflineSyncCompleted:
		e.emit("synced", map[string]any{"count": evt.Count})

	case *events.Message:
		b := &batch{Live: true}
		b.add(e.parseEvent(evt))
		if !b.empty() {
			e.emit("msgs", b)
		}
	case *events.HistorySync:
		e.history(evt.Data)

	case *events.Receipt:
		e.emit("receipt", map[string]any{
			"jid": e.chatOf(&types.MessageInfo{MessageSource: evt.MessageSource}).String(), "ids": evt.MessageIDs,
			"type": receiptType(evt.Type), "sender": e.norm(evt.Sender, evt.SenderAlt).String(),
			"fromMe": evt.IsFromMe, "ts": evt.Timestamp.UnixMilli(),
		})
	case *events.ChatPresence:
		e.emit("typing", map[string]any{
			"jid": e.norm(evt.Chat, evt.SenderAlt).String(), "sender": e.norm(evt.Sender, evt.SenderAlt).String(),
			"state": string(evt.State), "media": string(evt.Media),
		})
	case *events.Presence:
		e.emit("presence", map[string]any{"jid": e.norm(evt.From, types.EmptyJID).String(), "online": !evt.Unavailable, "lastSeen": evt.LastSeen.UnixMilli()})

	case *events.Pin:
		v := evt.Action.GetPinned()
		e.emit("chats", []Chat{{Jid: e.norm(evt.JID, types.EmptyJID).String(), Pinned: &v}})
	case *events.Archive:
		v := evt.Action.GetArchived()
		e.emit("chats", []Chat{{Jid: e.norm(evt.JID, types.EmptyJID).String(), Archived: &v}})
	case *events.Mute:
		var until int64
		if evt.Action.GetMuted() {
			until = evt.Action.GetMuteEndTimestamp()
			if until <= 0 {
				until = -1 // forever
			}
		}
		e.emit("chats", []Chat{{Jid: e.norm(evt.JID, types.EmptyJID).String(), MutedUntil: &until}})
	case *events.MarkChatAsRead:
		unread := !evt.Action.GetRead()
		c := Chat{Jid: e.norm(evt.JID, types.EmptyJID).String(), MarkedUnread: &unread}
		if !unread {
			zero := 0
			c.Unread = &zero
		}
		e.emit("chats", []Chat{c})
	case *events.DeleteForMe:
		e.emit("msgs", &batch{Live: true, Revokes: []*Revoke{{Jid: e.norm(evt.ChatJID, types.EmptyJID).String(), ID: evt.MessageID}}})
	case *events.Star:
		e.emit("star", map[string]any{"jid": e.norm(evt.ChatJID, types.EmptyJID).String(), "id": evt.MessageID, "starred": evt.Action.GetStarred()})

	case *events.Contact:
		e.emit("contacts", []Contact{{Jid: e.norm(evt.JID, types.EmptyJID).String(), Name: evt.Action.GetFullName()}})
	case *events.PushName:
		e.emit("contacts", []Contact{{Jid: e.norm(evt.JID, evt.JIDAlt).String(), Notify: evt.NewPushName}})
	case *events.JoinedGroup, *events.GroupInfo:
		e.emit("groups-changed", map[string]any{})
	case *events.Picture:
		e.emit("avatar-changed", map[string]any{"jid": e.norm(evt.JID, types.EmptyJID).String(), "removed": evt.Remove})

	case *events.MediaRetry:
		if ch, ok := e.retries.Load(evt.MessageID); ok {
			select {
			case ch.(chan *events.MediaRetry) <- evt:
			default:
			}
		}
	}
}

func receiptType(t types.ReceiptType) string {
	switch t {
	case types.ReceiptTypeDelivered:
		return "delivered"
	case types.ReceiptTypeRead, types.ReceiptTypeReadSelf:
		return "read"
	case types.ReceiptTypePlayed, types.ReceiptTypePlayedSelf:
		return "played"
	}
	return string(t)
}

// history turns one history-sync blob into chat settings, contact names and message rows.
func (e *eng) history(data *waHistorySync.HistorySync) {
	if data == nil {
		return
	}
	st := data.GetSyncType()
	b := &batch{OnDemand: st == waHistorySync.HistorySync_ON_DEMAND, SyncType: st.String()}
	if p := data.GetProgress(); p > 0 || st == waHistorySync.HistorySync_INITIAL_BOOTSTRAP || st == waHistorySync.HistorySync_RECENT {
		b.Progress = &p
	}

	var contacts []Contact
	for _, pn := range data.GetPushnames() {
		if pn.GetPushname() != "" {
			contacts = append(contacts, Contact{Jid: e.normStr(pn.GetID()), Notify: pn.GetPushname()})
		}
	}
	if len(contacts) > 0 {
		e.emit("contacts", contacts)
	}

	var chats []Chat
	for _, conv := range data.GetConversations() {
		chatJID, err := types.ParseJID(conv.GetID())
		if err != nil {
			continue
		}
		alt := types.EmptyJID
		if pn := conv.GetPnJID(); pn != "" {
			alt, _ = types.ParseJID(pn)
		}
		norm := e.norm(chatJID, alt)
		if skipChat(norm) {
			continue
		}
		if st != waHistorySync.HistorySync_ON_DEMAND {
			c := Chat{Jid: norm.String()}
			if n := or(conv.GetName(), conv.GetDisplayName()); n != "" {
				c.Name = &n
			}
			pinned := conv.GetPinned() > 0
			archived := conv.GetArchived()
			unread := int(conv.GetUnreadCount())
			c.Pinned, c.Archived, c.Unread = &pinned, &archived, &unread
			if mt := conv.GetMuteEndTime(); mt > 0 {
				ms := int64(mt) * 1000
				c.MutedUntil = &ms
			}
			chats = append(chats, c)
		}
		for _, hm := range conv.GetMessages() {
			evt, err := e.cli.ParseWebMessage(chatJID, hm.GetMessage())
			if err != nil {
				continue
			}
			b.add(e.parseEvent(evt))
		}
	}
	if len(chats) > 0 {
		e.emit("chats", chats)
	}
	if !b.empty() || b.Progress != nil || b.OnDemand {
		e.emit("msgs", b)
	}
}
