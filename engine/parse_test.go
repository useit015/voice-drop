package engine

import (
	"testing"

	"go.mau.fi/whatsmeow/proto/waE2E"
	"google.golang.org/protobuf/proto"
)

func TestFillRowKinds(t *testing.T) {
	cases := []struct {
		name string
		msg  *waE2E.Message
		kind string
		text string
	}{
		{"conversation", &waE2E.Message{Conversation: proto.String("hi")}, "text", "hi"},
		{"extended", &waE2E.Message{ExtendedTextMessage: &waE2E.ExtendedTextMessage{Text: proto.String("yo")}}, "text", "yo"},
		{"voice", &waE2E.Message{AudioMessage: &waE2E.AudioMessage{PTT: proto.Bool(true), Seconds: proto.Uint32(3)}}, "voice", ""},
		{"audio", &waE2E.Message{AudioMessage: &waE2E.AudioMessage{}}, "audio", ""},
		{"image", &waE2E.Message{ImageMessage: &waE2E.ImageMessage{Caption: proto.String("cap")}}, "image", "cap"},
		{"gif", &waE2E.Message{VideoMessage: &waE2E.VideoMessage{GifPlayback: proto.Bool(true)}}, "gif", ""},
		{"video", &waE2E.Message{VideoMessage: &waE2E.VideoMessage{}}, "video", ""},
		{"sticker", &waE2E.Message{StickerMessage: &waE2E.StickerMessage{}}, "sticker", ""},
		{"document", &waE2E.Message{DocumentMessage: &waE2E.DocumentMessage{FileName: proto.String("a.pdf")}}, "document", ""},
		{"location", &waE2E.Message{LocationMessage: &waE2E.LocationMessage{Name: proto.String("Home")}}, "location", "Home"},
		{"poll", &waE2E.Message{PollCreationMessageV3: &waE2E.PollCreationMessage{Name: proto.String("Lunch?")}}, "poll", "Lunch?"},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			r := &Row{}
			if !fillRow(r, c.msg) {
				t.Fatal("not shown")
			}
			if r.Kind != c.kind || r.Text != c.text {
				t.Fatalf("got %s/%q, want %s/%q", r.Kind, r.Text, c.kind, c.text)
			}
		})
	}
	if fillRow(&Row{}, &waE2E.Message{}) {
		t.Fatal("empty message should be skipped")
	}
}

func TestVoiceMeta(t *testing.T) {
	r := &Row{}
	fillRow(r, &waE2E.Message{AudioMessage: &waE2E.AudioMessage{PTT: proto.Bool(true), Seconds: proto.Uint32(4), Waveform: []byte{0, 50, 100}, Mimetype: proto.String("audio/ogg; codecs=opus")}})
	if r.Meta.Seconds != 4 || r.Meta.Wave != "ADJk" || r.Meta.Mime != "audio/ogg; codecs=opus" {
		t.Fatalf("meta %+v", r.Meta)
	}
}

func TestContextOf(t *testing.T) {
	ci := &waE2E.ContextInfo{StanzaID: proto.String("ABC")}
	m := &waE2E.Message{ImageMessage: &waE2E.ImageMessage{ContextInfo: ci}}
	if got := contextOf(m); got.GetStanzaID() != "ABC" {
		t.Fatalf("got %v", got)
	}
	if contextOf(&waE2E.Message{Conversation: proto.String("x")}) != nil {
		t.Fatal("plain text has no context")
	}
}

func TestDigitsOnly(t *testing.T) {
	if got := digitsOnly("+212 (612) 34-56-78"); got != "212612345678" {
		t.Fatal(got)
	}
}
