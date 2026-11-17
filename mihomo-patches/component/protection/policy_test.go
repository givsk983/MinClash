package protection

import (
	"bytes"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestProtectionPolicyValidation(t *testing.T) {
	p := Policy{Version: 1, Apps: []App{{"org.example.app", 10123, "Safe"}}, StrictDNS: true, DNSProxy: "Safe"}
	data, _ := json.Marshal(p)
	got, err := Decode(data)
	if err != nil || !got.Enabled() {
		t.Fatal(err)
	}
	if target, ok := got.Target(10123); !ok || target != "Safe" {
		t.Fatal("UID not bound")
	}
	for _, bad := range []string{`{"version":2}`, `{"version":1,"unknown":true}`, string(data) + " {}", strings.Replace(string(data), "Safe", "DIRECT", 1), strings.Replace(string(data), "10123", "0", 1)} {
		if _, err := Decode([]byte(bad)); err == nil {
			t.Fatalf("accepted %s", bad)
		}
	}
	p.Apps = append(p.Apps, App{"org.example.other", 10123, "Other"})
	data, _ = json.Marshal(p)
	if _, err := Decode(data); err == nil {
		t.Fatal("ambiguous shared UID")
	}
}

// A small stateful firewall, including rule references and -C, not canned success.
type fakeFW struct {
	chains map[string]map[string][][]string
	fail6  bool
	calls  []string
}

func (m *fakeFW) run(name string, args ...string) (string, error) {
	family := "4"
	if strings.Contains(name, "ip6tables") {
		family = "6"
	}
	m.calls = append(m.calls, family+" "+strings.Join(args, " "))
	if family == "6" && m.fail6 {
		return "", errors.New("IPv6 filter unavailable")
	}
	if m.chains == nil {
		m.chains = map[string]map[string][][]string{}
	}
	if m.chains[family] == nil {
		m.chains[family] = map[string][][]string{"OUTPUT": {}}
	}
	chains := m.chains[family]
	op := args[4]
	chain := args[5]
	rules, exists := chains[chain]
	equal := func(a, b []string) bool { return strings.Join(a, " ") == strings.Join(b, " ") }
	switch op {
	case "-N":
		if exists {
			return "", errors.New("exists")
		}
		chains[chain] = nil
	case "-S":
		if !exists {
			return "", errors.New("missing")
		}
		var s string
		for _, r := range rules {
			s += "-A " + chain + " " + strings.Join(r, " ") + "\n"
		}
		return s, nil
	case "-F":
		if !exists {
			return "", errors.New("missing")
		}
		chains[chain] = nil
	case "-X":
		delete(chains, chain)
	case "-A":
		if !exists {
			return "", errors.New("missing")
		}
		chains[chain] = append(rules, append([]string{}, args[6:]...))
	case "-I":
		chains[chain] = append([][]string{append([]string{}, args[7:]...)}, rules...)
	case "-C", "-D":
		for i, r := range rules {
			if equal(r, args[6:]) {
				if op == "-D" {
					chains[chain] = append(rules[:i], rules[i+1:]...)
				}
				return "", nil
			}
		}
		return "", errors.New("rule missing")
	default:
		return "", errors.New("unexpected operation")
	}
	return "", nil
}
func TestProtectionFirewallLifecycle(t *testing.T) {
	m := &fakeFW{}
	f := Firewall{m.run}
	p := Policy{Version: 1, Apps: []App{{"org.example.app", 10123, "Safe"}}, StrictDNS: true, DNSProxy: "Safe"}
	if err := f.Hold(Policy{}, p); err != nil {
		t.Fatal(err)
	}
	if f.Healthy(p) {
		t.Fatal("HOLD reported active")
	}
	if err := f.Activate(p); err != nil {
		t.Fatal(err)
	}
	if !f.Healthy(p) {
		t.Fatal("not healthy")
	}
	if err := f.Hold(p, p); err != nil {
		t.Fatal(err)
	}
	if f.Healthy(p) {
		t.Fatal("staging must stay blocked")
	}
	m.fail6 = true
	if err := f.Activate(p); err == nil {
		t.Fatal("accepted partial IPv6 failure")
	}
	if _, err := f.call("iptables", "-C", "OUTPUT", "-j", hold); err != nil {
		t.Fatal("failure released IPv4 HOLD")
	}
	m.fail6 = false
	if err := f.Remove(); err != nil {
		t.Fatal(err)
	}
	for _, chains := range m.chains {
		if len(chains) != 1 || len(chains["OUTPUT"]) != 0 {
			t.Fatal("cleanup left private rules")
		}
	}
}
func TestProtectionStageCompareAndFailureRecovery(t *testing.T) {
	m := &fakeFW{}
	f := Firewall{m.run}
	path := filepath.Join(t.TempDir(), "policy.json")
	old := Policy{Version: 1}
	next := Policy{Version: 1, Apps: []App{{"org.example.app", 10123, "Safe"}}}
	verify := func(Policy) error { return nil }
	request := func(expected string) *bytes.Reader {
		data, _ := json.Marshal(struct {
			Expected string `json:"expected"`
			Policy   Policy `json:"policy"`
		}{expected, next})
		return bytes.NewReader(data)
	}
	if err := manage("stage", path, request("stale"), &bytes.Buffer{}, f, verify); err == nil {
		t.Fatal("stale overwrite")
	}
	if len(m.calls) != 0 {
		t.Fatal("stale request touched firewall")
	}
	if err := manage("stage", path, request(old.Digest()), &bytes.Buffer{}, f, verify); err != nil {
		t.Fatal(err)
	}
	p, err := Read(path)
	if err != nil || p.Digest() != next.Digest() {
		t.Fatal("policy not committed", err)
	}
	if f.Healthy(p) {
		t.Fatal("stage released HOLD before old core stopped")
	}
	m.fail6 = true
	if err := manage("activate", path, nil, &bytes.Buffer{}, f, verify); err == nil {
		t.Fatal("partial activation succeeded")
	}
	m.fail6 = false
	if err = os.WriteFile(path, []byte("corrupt"), 0600); err != nil {
		t.Fatal(err)
	}
	if err = manage("disable", path, nil, &bytes.Buffer{}, f, verify); err != nil {
		t.Fatal("explicit recovery failed", err)
	}
	p, err = Read(path)
	if err != nil || p.Enabled() {
		t.Fatal("recovery did not disable policy")
	}
}

func TestProtectionOffDoesNotRequireFirewall(t *testing.T) {
	m := &fakeFW{fail6: true}
	f := Firewall{m.run}
	path := filepath.Join(t.TempDir(), "policy.json")
	for _, command := range []string{"hold", "activate", "disable"} {
		if err := manage(command, path, nil, &bytes.Buffer{}, f, func(Policy) error { return nil }); err != nil {
			t.Fatal(err)
		}
	}
	if len(m.calls) != 0 {
		t.Fatal("default off probed or changed firewall")
	}
}

func TestBatchPackageVerification(t *testing.T) {
	p := Policy{Version: 1, Apps: []App{{"org.example.one", 10123, "Safe"}, {"org.example.two", 10124, "Safe"}, {"org.example.work", 1010123, "Safe"}}}
	calls := make(map[uint32]int)
	list := func(user uint32) ([]byte, error) {
		calls[user]++
		if user == 0 {
			return []byte("package:org.example.one uid:10123\npackage:org.example.two uid:10124\n"), nil
		}
		return []byte("package:org.example.work uid:1010123\n"), nil
	}
	if err := verifyPackages(p, list); err != nil {
		t.Fatal(err)
	}
	if calls[0] != 1 || calls[10] != 1 {
		t.Fatal(calls)
	}
	p.Apps[0].UID++
	if verifyPackages(p, list) == nil {
		t.Fatal("accepted changed UID")
	}
	if verifyPackages(p, func(uint32) ([]byte, error) { return nil, errors.New("unavailable") }) == nil {
		t.Fatal("accepted failed inventory")
	}
	if verifyPackages(Policy{}, func(uint32) ([]byte, error) { t.Fatal("queried empty policy"); return nil, nil }) != nil {
		t.Fatal("empty policy")
	}
}

func TestProtectionPolicyReadSkipsHealthChecks(t *testing.T) {
	path := filepath.Join(t.TempDir(), "policy.json")
	p := Policy{Version: 1, Apps: []App{{"org.example.app", 10123, "Safe"}}}
	if err := writePolicy(path, p); err != nil {
		t.Fatal(err)
	}
	var out bytes.Buffer
	f := Firewall{func(string, ...string) (string, error) { t.Fatal("policy read called firewall"); return "", nil }}
	if err := manage("policy", path, strings.NewReader(""), &out, f, func(Policy) error { t.Fatal("policy read called package manager"); return nil }); err != nil {
		t.Fatal(err)
	}
	var result struct {
		Policy Policy `json:"policy"`
	}
	if json.Unmarshal(out.Bytes(), &result) != nil || result.Policy.Digest() != p.Digest() {
		t.Fatal(out.String())
	}
}
