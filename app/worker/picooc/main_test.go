package main

import (
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strings"
	"testing"
)

type roundTripFunc func(*http.Request) (*http.Response, error)

func (f roundTripFunc) RoundTrip(r *http.Request) (*http.Response, error) { return f(r) }

const primary = `{"code":0,"resp":{"user_id":"13","role_id":"11","roles":[{"role_name":"Family","role_id":"12"}]}}`

func historyFixture(t *testing.T, replies ...string) (string, error) {
	t.Helper()
	original := http.DefaultTransport
	t.Cleanup(func() { http.DefaultTransport = original })
	calls := 0
	http.DefaultTransport = &checkedTransport{remaining: 32 << 20, base: roundTripFunc(func(r *http.Request) (*http.Response, error) {
		if calls >= len(replies) {
			t.Fatal("unexpected provider request")
		}
		if calls == 0 && r.Method != http.MethodPost {
			t.Fatal("login must precede reading history")
		}
		if calls > 0 && (r.Method != http.MethodGet || r.URL.Query().Get("roleId") != "11") {
			t.Fatal("expected primary profile")
		}
		if calls == 2 && r.URL.Query().Get("time") != "100" {
			t.Fatal("expected next page")
		}
		reply := replies[calls]
		calls++
		return &http.Response{StatusCode: 200, Body: io.NopCloser(strings.NewReader(reply)), Header: make(http.Header)}, nil
	})}
	var out bytes.Buffer
	err := readHistory(strings.NewReader(`{"email":"a@example.test","password":"fixture"}`), &out)
	if calls != len(replies) {
		t.Fatalf("used %d of %d responses", calls, len(replies))
	}
	return out.String(), err
}

func TestPrimaryProfileHistory(t *testing.T) {
	raw, err := historyFixture(t, primary,
		`{"code":200,"resp":{"records":[{"role_id":11,"bodyTime":150,"weight":71.2,"body_fat":21}],"continue":true,"lastTime":100}}`,
		`{"code":0,"resp":{"records":[{"bodyTime":99,"weight":70},{"bodyTime":98,"weight":81,"is_del":1},{"bodyTime":97,"weight":82,"abnormal_flag":1}]}}`)
	if err != nil {
		t.Fatal(err)
	}
	var rows []struct {
		Date            string
		Weight, BodyFat float64
	}
	if json.Unmarshal([]byte(raw), &rows) != nil || len(rows) != 2 || rows[0].Weight != 71.2 || rows[0].BodyFat != 21 || rows[1].Weight != 70 {
		t.Fatal("expected two valid primary profile readings")
	}
}

func TestRejectedLogin(t *testing.T) {
	raw, err := historyFixture(t, `{"code":1}`)
	if !errors.Is(err, denied) || raw != "" {
		t.Fatal("rejected account must produce no readings")
	}
}

func TestBadHistoryDoesNotLookLikeEmptySuccess(t *testing.T) {
	for _, reply := range []string{`{"code":42}`, `{"code":0,"resp":{}}`, `{"code":0,"resp":{"records":[{"role_id":12,"weight":81}]}}`, `{"code":0,"resp":{"records":[],"continue":true,"lastTime":0}}`} {
		t.Run(reply, func(t *testing.T) {
			raw, err := historyFixture(t, primary, reply)
			if err == nil || raw != "" {
				t.Fatal("expected an atomic failure")
			}
		})
	}
}
