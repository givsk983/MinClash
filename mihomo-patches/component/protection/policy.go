// Package protection owns the opt-in policy independently of subscription YAML.
package protection

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"os"
	"regexp"
	"strings"
)

const DNSProxy = "MINCLASH-STRICT-DNS"
const PolicyPath = "/data/adb/minclash/protection.json"
const MaxBytes = 65536

type App struct {
	Package string `json:"package"`
	UID     uint32 `json:"uid"`
	Target  string `json:"target"`
}
type Policy struct {
	Version   int    `json:"version"`
	Apps      []App  `json:"apps"`
	StrictDNS bool   `json:"strictDns"`
	DNSProxy  string `json:"dnsProxy"`
	Bootstrap bool   `json:"bootstrap"`
}

var Runtime = Policy{Version: 1}
var packageName = regexp.MustCompile(`^[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+$`)

func Decode(data []byte) (Policy, error) {
	p := Policy{}
	if len(data) > MaxBytes {
		return p, errors.New("protection policy too large")
	}
	d := json.NewDecoder(bytes.NewReader(data))
	d.DisallowUnknownFields()
	if err := d.Decode(&p); err != nil {
		return p, err
	}
	var trailing any
	if err := d.Decode(&trailing); err != io.EOF {
		return p, errors.New("trailing protection data")
	}
	if p.Version != 1 || len(p.Apps) > 128 {
		return p, errors.New("unsupported protection policy")
	}
	seen := map[string]bool{}
	uids := map[uint32]string{}
	for _, a := range p.Apps {
		if !packageName.MatchString(a.Package) || a.UID%100000 < 10000 || a.UID%100000 >= 20000 || a.UID/100000 > 999 || !validTarget(a.Target) {
			return p, errors.New("invalid protected application or proxy")
		}
		if seen[a.Package] {
			return p, errors.New("duplicate application")
		}
		seen[a.Package] = true
		if target, ok := uids[a.UID]; ok && target != a.Target {
			return p, errors.New("shared UID applications must use the same proxy")
		}
		uids[a.UID] = a.Target
	}
	if p.StrictDNS && !validTarget(p.DNSProxy) {
		return p, errors.New("select a DNS proxy")
	}
	if !p.StrictDNS && p.Bootstrap {
		return p, errors.New("bootstrap requires strict DNS")
	}
	return p, nil
}
func validTarget(s string) bool {
	if len(s) == 0 || len(s) > 256 || strings.ContainsAny(s, "\r\n\x00#&") {
		return false
	}
	switch strings.ToUpper(s) {
	case "DIRECT", "COMPATIBLE", "PASS", "REJECT", "REJECT-DROP", "DNS", "GLOBAL", DNSProxy:
		return false
	}
	return true
}
func (p Policy) Enabled() bool { return len(p.Apps) != 0 || p.StrictDNS }
func (p Policy) Target(uid uint32) (string, bool) {
	for _, a := range p.Apps {
		if a.UID == uid {
			return a.Target, true
		}
	}
	return "", false
}
func (p Policy) Digest() string {
	data, _ := json.Marshal(p)
	h := sha256.Sum256(data)
	return hex.EncodeToString(h[:])
}
func Read(path string) (Policy, error) {
	f, err := os.Open(path)
	if os.IsNotExist(err) {
		return Policy{Version: 1}, nil
	}
	if err != nil {
		return Policy{}, err
	}
	defer f.Close()
	data, err := io.ReadAll(io.LimitReader(f, MaxBytes+1))
	if err != nil {
		return Policy{}, err
	}
	return Decode(data)
}
func LoadRuntime() error {
	path := os.Getenv("MINCLASH_PROTECTION_FILE")
	if path == "" {
		return nil
	}
	p, err := Read(path)
	if err != nil {
		return err
	}
	Runtime = p
	return nil
}
