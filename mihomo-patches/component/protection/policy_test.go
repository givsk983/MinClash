package protection

import (
	"bytes"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"strconv"
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
	fail4  bool
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
	if family == "4" && m.fail4 {
		return "", errors.New("IPv4 filter unavailable")
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
		position, err := strconv.Atoi(args[6])
		if err != nil || position < 1 || position > len(rules)+1 {
			return "", errors.New("invalid insertion")
		}
		updated := append([][]string{}, rules[:position-1]...)
		updated = append(updated, append([]string{}, args[7:]...))
		chains[chain] = append(updated, rules[position-1:]...)
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
	if _, err := f.fingerprint(false); err == nil {
		t.Fatal("HOLD reported active")
	}
	if err := f.Activate(p); err != nil {
		t.Fatal(err)
	}
	if _, err := f.fingerprint(false); err != nil {
		t.Fatal("not healthy")
	}
	if err := f.Hold(p, p); err != nil {
		t.Fatal(err)
	}
	if _, err := f.fingerprint(false); err == nil {
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

func TestProtectionHoldTriesBothFamilies(t *testing.T) {
	for _, failed := range []string{"4", "6"} {
		t.Run(failed, func(t *testing.T) {
			mock := &fakeFW{fail4: failed == "4", fail6: failed == "6"}
			f := Firewall{mock.run}
			p := Policy{Version: 1, Apps: []App{{"org.example.app", 10123, "Safe"}}}
			if err := f.Hold(p, p); err == nil {
				t.Fatal("one family is unavailable")
			}
			working := "iptables"
			if failed == "4" {
				working = "ip6tables"
			}
			if first, err := f.firstOutputRule(working); err != nil || first != "-A OUTPUT -j "+hold {
				t.Fatal("working family did not retain the hold", first, err)
			}
			if _, err := f.call(working, "-C", append([]string{hold}, ownerReject(10123, false)...)...); err != nil {
				t.Fatal("working family did not block the protected app", err)
			}
		})
	}
}

func verifyUnchanged(Policy) (Policy, error) { return Policy{}, nil }

func TestProtectionWatchRestoresOwnedRules(t *testing.T) {
	mock := &fakeFW{}
	f := Firewall{mock.run}
	dir := t.TempDir()
	path := filepath.Join(dir, "policy.json")
	p := Policy{Version: 1, Apps: []App{{"org.example.app", 10123, "Safe"}}}
	if err := writePolicy(path, p); err != nil {
		t.Fatal(err)
	}
	verify := verifyUnchanged
	for _, command := range []string{"hold", "activate", "mark", "check"} {
		if err := manage(command, path, nil, &bytes.Buffer{}, f, verify); err != nil {
			t.Fatal(command, err)
		}
	}
	mock.chains["6"]["OUTPUT"] = [][]string{{"-j", "VENDOR_CHAIN"}}
	if err := manage("check", path, nil, &bytes.Buffer{}, f, verify); err == nil {
		t.Fatal("watchdog reported a missing anchor as healthy")
	}
	if err := manage("repair-running", path, nil, &bytes.Buffer{}, f, verify); err != nil {
		t.Fatal(err)
	}
	if err := manage("status", path, nil, &bytes.Buffer{}, f, verify); err != nil || strings.Join(mock.chains["6"]["OUTPUT"][1], " ") != "-j VENDOR_CHAIN" {
		t.Fatal("repair failed to restore the owned policy while retaining the vendor rule")
	}
	mock.chains["4"][active] = append(mock.chains["4"][active], []string{"-j", "RETURN"})
	if err := manage("status", path, nil, &bytes.Buffer{}, f, verify); err == nil {
		t.Fatal("status ignored an unexpected rule")
	}
	var output bytes.Buffer
	if err := manage("get", path, nil, &output, f, verify); err != nil || !strings.Contains(output.String(), `"firewall":false`) {
		t.Fatal("protection settings ignored an unexpected rule", output.String(), err)
	}
	if err := os.Remove(filepath.Join(dir, "protection-core")); err != nil {
		t.Fatal(err)
	}
	if err := manage("repair-running", path, nil, &bytes.Buffer{}, f, verify); err == nil {
		t.Fatal("released protection without a matching running policy")
	}
	if _, err := f.call("ip6tables", "-C", "OUTPUT", "-j", hold); err != nil {
		t.Fatal("repair must keep HOLD when the loaded protection policy is unknown")
	}
}

func TestProtectionWatchChecksReplacementUIDs(t *testing.T) {
	mock := &fakeFW{}
	f := Firewall{mock.run}
	path := filepath.Join(t.TempDir(), "policy.json")
	p := Policy{Version: 1, Apps: []App{{"org.example.app", 10123, "Safe"}}}
	if err := writePolicy(path, p); err != nil {
		t.Fatal(err)
	}
	for _, command := range []string{"hold", "activate", "mark"} {
		if err := manage(command, path, nil, &bytes.Buffer{}, f, verifyUnchanged); err != nil {
			t.Fatal(command, err)
		}
	}
	verify := func(p Policy) (Policy, error) {
		return verifyPackages(p, func(uint32) ([]byte, error) { return []byte("package:org.example.app uid:10124\n"), nil })
	}
	if err := manage("check", path, nil, &bytes.Buffer{}, f, verify); err == nil {
		t.Fatal("accepted stale binding with unchanged firewall")
	}
	if err := manage("repair-running", path, nil, &bytes.Buffer{}, f, verify); err == nil {
		t.Fatal("reactivated a stale binding")
	}
	for _, family := range firewallFamilies {
		for _, uid := range []uint32{10123, 10124} {
			if _, err := f.call(family, "-C", append([]string{hold}, ownerReject(uid, false)...)...); err != nil {
				t.Fatal("UID did not retain a hold", family, uid, err)
			}
		}
	}
	var output bytes.Buffer
	if err := manage("get", path, nil, &output, f, verify); err != nil || !strings.Contains(output.String(), `"binding":false`) {
		t.Fatal("protection settings ignored a stale binding", output.String(), err)
	}
}

func TestProtectionPrivateDNSConflictRetainsHoldAndRecovers(t *testing.T) {
	mock := &fakeFW{}
	f := Firewall{mock.run}
	path := filepath.Join(t.TempDir(), "policy.json")
	p := Policy{Version: 1, Apps: []App{{"org.example.app", 10123, "Safe"}}, StrictDNS: true, DNSProxy: "Safe"}
	policy, err := json.Marshal(p)
	if err != nil {
		t.Fatal(err)
	}
	off := false
	privateDNSOff := func() bool { return off }
	var output bytes.Buffer
	command := func(name string, input string) error {
		output.Reset()
		return manage(name, path, strings.NewReader(input), &output, f, verifyUnchanged, privateDNSOff)
	}
	request := `{"expected":"` + (Policy{Version: 1}).Digest() + `","policy":` + string(policy) + `}`
	if err := command("stage", request); err == nil || len(mock.calls) != 0 {
		t.Fatal("conflicting settings reached firewall", err)
	}
	off = true
	for _, name := range []string{"stage", "activate", "mark", "check"} {
		if err := command(name, request); err != nil {
			t.Fatal(name, err)
		}
	}
	off = false
	if err := command("check", ""); err == nil {
		t.Fatal("accepted a new Private DNS conflict")
	}
	if err := command("repair-running", ""); err == nil || output.String() != "protection_held=1\n" {
		t.Fatal("did not confirm conflict remained held", err, output.String())
	}
	if err := command("get", ""); err != nil || !strings.Contains(output.String(), `"privateDNSConflict":true`) {
		t.Fatal("did not report Private DNS conflict", err, output.String())
	}
	off = true
	if err := command("repair-running", ""); err != nil {
		t.Fatal("protection did not resume after clearing conflict", err)
	}
	if err := command("check", ""); err != nil {
		t.Fatal("recovered protection is not healthy", err)
	}
}

func TestProtectionStageCompareAndFailureRecovery(t *testing.T) {
	m := &fakeFW{}
	f := Firewall{m.run}
	path := filepath.Join(t.TempDir(), "policy.json")
	old := Policy{Version: 1}
	next := Policy{Version: 1, Apps: []App{{"org.example.app", 10123, "Safe"}}}
	verify := verifyUnchanged
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
	if _, err := f.fingerprint(false); err == nil {
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
		if err := manage(command, path, nil, &bytes.Buffer{}, f, verifyUnchanged); err != nil {
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
	if _, err := verifyPackages(p, list); err != nil {
		t.Fatal(err)
	}
	if calls[0] != 1 || calls[10] != 1 {
		t.Fatal(calls)
	}
	p.Apps[0].UID++
	if pending, err := verifyPackages(p, list); err == nil || len(pending.Apps) != 1 || pending.Apps[0].UID != 10123 {
		t.Fatal("failed to identify changed UID for temporary blocking", pending, err)
	}
	if _, err := verifyPackages(p, func(uint32) ([]byte, error) { return nil, errors.New("unavailable") }); err == nil {
		t.Fatal("accepted failed inventory")
	}
	if _, err := verifyPackages(Policy{}, func(uint32) ([]byte, error) { t.Fatal("queried empty policy"); return nil, nil }); err != nil {
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
	if err := manage("policy", path, strings.NewReader(""), &out, f, func(Policy) (Policy, error) {
		t.Fatal("policy read called package manager")
		return Policy{}, nil
	}); err != nil {
		t.Fatal(err)
	}
	var result struct {
		Policy Policy `json:"policy"`
	}
	if json.Unmarshal(out.Bytes(), &result) != nil || result.Policy.Digest() != p.Digest() {
		t.Fatal(out.String())
	}
}
