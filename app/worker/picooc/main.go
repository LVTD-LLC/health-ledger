// Command health-ledger-picooc reads the signed-in primary profile with SmartScaleConnect.
// Credentials arrive on stdin. Only a successful full history is written to stdout.
package main

import (
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"os"
	"strconv"

	"github.com/AlexxIT/SmartScaleConnect/pkg/core"
	"github.com/AlexxIT/SmartScaleConnect/pkg/picooc"
)

var denied = errors.New("sign-in rejected")
var invalid = errors.New("unexpected PICOOC response")

// SmartScaleConnect omits these source fields from its normalized weights.
type composition struct {
	MuscleRatio float32     `json:"muscle_race,omitempty"`
	SourceFat   json.Number `json:"subcutaneous_fat,omitempty"`
	Resistance  json.Number `json:"electric_resistance,omitempty"`
	Correction  json.Number `json:"correction_value_r,omitempty"`
}

type recordDetails struct {
	composition
	Role     json.Number `json:"role_id"`
	Time     int64       `json:"bodyTime"`
	Source   string      `json:"mac"`
	Deleted  int         `json:"is_del"`
	Abnormal int         `json:"abnormal_flag"`
}

type reading struct {
	*core.Weight
	composition
}

// The upstream client decodes history without checking the provider status.
// Check it before letting an error response look like a successful empty import.
type checkedTransport struct {
	base      http.RoundTripper
	remaining int64
	profile   string
	details   []recordDetails
}

func (t *checkedTransport) RoundTrip(r *http.Request) (*http.Response, error) {
	if r.Response != nil || r.URL.Scheme != "https" || r.URL.Host != "api2.picooc-int.com" {
		return nil, invalid
	}
	login := r.Method == http.MethodPost
	if !login && (t.profile == "" || r.URL.Query().Get("roleId") != t.profile) {
		return nil, invalid
	}
	resp, err := t.base.RoundTrip(r)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode == http.StatusUnauthorized && login {
		return nil, denied
	}
	if resp.StatusCode != http.StatusOK {
		return nil, invalid
	}
	data, err := io.ReadAll(io.LimitReader(resp.Body, min(4<<20, t.remaining)+1))
	t.remaining -= int64(len(data))
	if err != nil || t.remaining < 0 || len(data) > 4<<20 {
		return nil, invalid
	}
	var result struct {
		Code *int
		Resp struct {
			UserID   string `json:"user_id"`
			RoleID   string `json:"role_id"`
			Records  *[]recordDetails
			Continue bool
			LastTime int64
		}
	}
	if json.Unmarshal(data, &result) != nil || result.Code == nil {
		return nil, invalid
	}
	if *result.Code != 0 && (login || *result.Code != http.StatusOK) {
		if login {
			return nil, denied
		}
		return nil, invalid
	}
	if login {
		if result.Resp.RoleID == "" || result.Resp.UserID == "" {
			return nil, invalid
		}
		t.profile = result.Resp.RoleID
	} else {
		if result.Resp.Records == nil {
			return nil, invalid
		}
		for _, record := range *result.Resp.Records {
			if record.Role != "" && record.Role.String() != t.profile {
				return nil, invalid
			}
			if record.Deleted == 0 && record.Abnormal == 0 {
				t.details = append(t.details, record)
			}
		}
		if result.Resp.Continue {
			before, err := strconv.ParseInt(r.URL.Query().Get("time"), 10, 64)
			if err != nil || result.Resp.LastTime <= 0 || result.Resp.LastTime >= before {
				return nil, invalid
			}
		}
	}
	resp.Body = io.NopCloser(bytes.NewReader(data))
	return resp, nil
}

func readHistory(in io.Reader, out io.Writer, transport *checkedTransport) error {
	var account struct{ Email, Password string }
	if err := json.NewDecoder(io.LimitReader(in, 16<<10)).Decode(&account); err != nil || account.Email == "" || account.Password == "" {
		return invalid
	}
	client := picooc.NewClient()
	if err := client.Login(account.Email, account.Password); err != nil {
		return err
	}
	weights, err := client.GetAllWeights()
	if err != nil {
		return err
	}
	if len(weights) != len(transport.details) {
		return invalid
	}
	readings := make([]reading, 0, len(weights))
	for i, weight := range weights {
		extra := transport.details[i]
		if extra.Time != weight.Date.Unix() || extra.Source != weight.Source {
			return invalid
		}
		readings = append(readings, reading{weight, extra.composition})
	}
	return json.NewEncoder(out).Encode(readings)
}

func main() {
	transport := &checkedTransport{base: http.DefaultTransport, remaining: 32 << 20}
	http.DefaultTransport = transport
	if err := readHistory(os.Stdin, os.Stdout, transport); err != nil {
		if errors.Is(err, denied) {
			os.Exit(2)
		}
		os.Exit(1)
	}
}
