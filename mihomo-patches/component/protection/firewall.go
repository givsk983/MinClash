package protection

import (
	"context"
	"crypto/sha256"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"time"
)

// Only these private chains are ever modified. No system chain is flushed.
const anchor = "MC_PRIVACY"
const hold = "MC_PRIVACY_HOLD"
const active = "MC_PRIVACY_RUN"

var firewallFamilies = [...]string{"iptables", "ip6tables"}

type Firewall struct {
	Run func(string, ...string) (string, error)
}

func SystemFirewall() Firewall {
	return Firewall{Run: func(name string, args ...string) (string, error) {
		ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
		defer cancel()
		out, err := exec.CommandContext(ctx, name, args...).CombinedOutput()
		if err != nil {
			return string(out), fmt.Errorf("firewall command failed: %s: %w", name, err)
		}
		return string(out), nil
	}}
}
func (f Firewall) call(family, op string, args ...string) (string, error) {
	return f.Run("/system/bin/"+family, append([]string{"-w", "5", "-t", "filter", op}, args...)...)
}
func (f Firewall) ensure(family, chain string) error {
	if _, err := f.call(family, "-S", chain); err == nil {
		return nil
	}
	_, err := f.call(family, "-N", chain)
	return err
}
func unionUIDs(policies ...Policy) []uint32 {
	seen := map[uint32]bool{}
	var result []uint32
	for _, p := range policies {
		for _, a := range p.Apps {
			if !seen[a.UID] {
				seen[a.UID] = true
				result = append(result, a.UID)
			}
		}
	}
	sort.Slice(result, func(i, j int) bool { return result[i] < result[j] })
	return result
}
func (f Firewall) append(family, chain string, args ...string) error {
	_, err := f.call(family, "-A", append([]string{chain}, args...)...)
	return err
}

func (f Firewall) appendMissing(family, chain string, rule ...string) error {
	if _, err := f.call(family, "-C", append([]string{chain}, rule...)...); err == nil {
		return nil
	}
	return f.append(family, chain, rule...)
}

func (f Firewall) listRules(family, chain string) ([]string, error) {
	output, err := f.call(family, "-S", chain)
	if err != nil {
		return nil, err
	}
	var rules []string
	for _, line := range strings.Split(output, "\n") {
		if strings.HasPrefix(line, "-A "+chain+" ") {
			rules = append(rules, line)
		}
	}
	return rules, nil
}

func (f Firewall) firstOutputRule(family string) (string, error) {
	rules, err := f.listRules(family, "OUTPUT")
	if err != nil {
		return "", err
	}
	if len(rules) > 0 {
		return rules[0], nil
	}
	return "", nil
}

func (f Firewall) attachFirst(family, chain string) error {
	first, err := f.firstOutputRule(family)
	if err != nil || first == "-A OUTPUT -j "+chain {
		return err
	}
	// Keep an existing jump in place until there is a replacement. Duplicate
	// jumps are harmless and can be removed with the owned policy.
	_, err = f.call(family, "-I", "OUTPUT", "1", "-j", chain)
	return err
}

// Fingerprint only the owned policy and its entry points. Unrelated OUTPUT
// rules can change without requiring a firewall rebuild.
func (f Firewall) fingerprint(allowHold bool) (string, error) {
	hash := sha256.New()
	for _, family := range firewallFamilies {
		output, err := f.listRules(family, "OUTPUT")
		if err != nil {
			return "", err
		}
		if allowHold {
			if len(output) == 0 || output[0] != "-A OUTPUT -j "+hold {
				return "", errors.New("protection HOLD has lost priority")
			}
			output = output[1:]
		}
		for _, rule := range output {
			if !allowHold && rule == "-A OUTPUT -j "+hold {
				return "", errors.New("protection HOLD is active")
			}
		}
		if len(output) == 0 || output[0] != "-A OUTPUT -j "+anchor {
			return "", errors.New("protection anchor is missing or has lost priority")
		}
		for _, chain := range []string{anchor, active} {
			rules, err := f.call(family, "-S", chain)
			if err != nil {
				return "", err
			}
			fmt.Fprintf(hash, "%s %s\n%s\n", family, chain, rules)
		}
	}
	return fmt.Sprintf("%x", hash.Sum(nil)), nil
}

func ownerReject(uid uint32, tunOnly bool) []string {
	rule := []string{"-m", "owner", "--uid-owner", strconv.FormatUint(uint64(uid), 10)}
	if tunOnly {
		rule = append(rule, "!", "-o", "minclash0")
	}
	return append(rule, "-j", "REJECT")
}

func activeRules(p Policy) [][]string {
	var rules [][]string
	if p.StrictDNS {
		for _, proto := range []string{"tcp", "udp"} {
			rules = append(rules,
				[]string{"-p", proto, "--dport", "853", "-m", "owner", "!", "--uid-owner", "0", "-j", "REJECT"},
				[]string{"!", "-o", "minclash0", "-p", proto, "--dport", "53", "-j", "REJECT"},
			)
		}
	}
	for _, uid := range unionUIDs(p) {
		rules = append(rules, ownerReject(uid, true))
	}
	return rules
}

// Hold is installed BEFORE stopping a core or replacing policy. On a partial
// failure the old rules and successfully installed blocks stay in place.
func (f Firewall) Hold(old, next Policy) error {
	var rules [][]string
	for _, uid := range unionUIDs(old, next) {
		rules = append(rules, ownerReject(uid, false))
	}
	if old.StrictDNS || next.StrictDNS {
		for _, proto := range []string{"tcp", "udp"} {
			for _, port := range []string{"53", "853"} {
				rules = append(rules, []string{"-p", proto, "--dport", port, "-j", "REJECT"})
			}
		}
	}
	var result error
	for _, family := range firewallFamilies {
		result = errors.Join(result, f.holdFamily(family, rules))
	}
	return result
}

func (f Firewall) holdFamily(family string, rules [][]string) error {
	if err := f.ensure(family, hold); err != nil {
		return err
	}
	installed, err := f.listRules(family, hold)
	if err != nil {
		return err
	}
	// Only REJECT rules are created here. If the chain was changed, put
	// required blocks ahead of any rules that could short-circuit them.
	var result error
	for _, rule := range installed {
		jump := strings.LastIndex(rule, " -j ")
		if jump < 0 || !strings.HasPrefix(rule[jump+4:]+" ", "REJECT ") {
			for _, reject := range rules {
				if _, err := f.call(family, "-I", append([]string{hold, "1"}, reject...)...); err != nil {
					result = err
					break
				}
			}
			return errors.Join(result, f.attachFirst(family, hold))
		}
	}
	// Never flush an existing HOLD; prior blocks must survive a failed write.
	for _, rule := range rules {
		if err := f.appendMissing(family, hold, rule...); err != nil {
			result = err
			break
		}
	}
	return errors.Join(result, f.attachFirst(family, hold))
}
func (f Firewall) Activate(p Policy) error {
	// The caller must have installed HOLD before reaching this point.
	rules := activeRules(p)
	for _, family := range firewallFamilies {
		if err := f.ensure(family, anchor); err != nil {
			return err
		}
		if err := f.ensure(family, active); err != nil {
			return err
		}
		if _, err := f.call(family, "-F", active); err != nil {
			return err
		}
		for _, rule := range rules {
			if err := f.append(family, active, rule...); err != nil {
				return err
			}
		}
		if _, err := f.call(family, "-F", anchor); err != nil {
			return err
		}
		if err := f.append(family, anchor, "-j", active); err != nil {
			return err
		}
		output, err := f.listRules(family, "OUTPUT")
		if err != nil {
			return err
		}
		if len(output) == 0 || output[0] != "-A OUTPUT -j "+hold {
			return errors.New("protection HOLD must precede activation")
		}
		if len(output) < 2 || output[1] != "-A OUTPUT -j "+anchor {
			if _, err := f.call(family, "-I", "OUTPUT", "2", "-j", anchor); err != nil {
				return err
			}
		}
	}
	if err := f.verifyActiveRules(p); err != nil {
		return err
	}
	if _, err := f.fingerprint(true); err != nil {
		return err
	}
	for _, family := range firewallFamilies {
		if err := f.detach(family, hold); err != nil {
			return err
		}
		if _, err := f.call(family, "-F", hold); err != nil {
			return err
		}
	}
	return nil
}

func (f Firewall) detach(family, chain string) error {
	output, err := f.listRules(family, "OUTPUT")
	if err != nil {
		return err
	}
	for _, rule := range output {
		if rule == "-A OUTPUT -j "+chain {
			if _, err := f.call(family, "-D", "OUTPUT", "-j", chain); err != nil {
				return err
			}
		}
	}
	return nil
}

func (f Firewall) Remove() error {
	for _, family := range firewallFamilies {
		if out, err := f.call(family, "-S", "OUTPUT"); err != nil {
			if errors.Is(err, os.ErrNotExist) || strings.Contains(strings.ToLower(out), "table does not exist") || strings.Contains(strings.ToLower(out), "protocol not available") {
				continue
			}
			return err
		}
		for _, chain := range []string{hold, anchor} {
			if err := f.detach(family, chain); err != nil {
				return err
			}
		}
		for _, chain := range []string{anchor, active, hold} {
			if _, err := f.call(family, "-S", chain); err != nil {
				continue
			}
			if _, err := f.call(family, "-F", chain); err != nil {
				return err
			}
			if _, err := f.call(family, "-X", chain); err != nil {
				return err
			}
		}
	}
	return nil
}

func (f Firewall) verifyActiveRules(p Policy) error {
	rules := activeRules(p)
	for _, family := range firewallFamilies {
		jump, err := f.listRules(family, anchor)
		if err != nil {
			return err
		}
		if len(jump) != 1 || jump[0] != "-A "+anchor+" -j "+active {
			return errors.New("unexpected protection anchor rules")
		}
		actual, err := f.listRules(family, active)
		if err != nil {
			return err
		}
		if len(actual) != len(rules) {
			return errors.New("unexpected protection firewall rules")
		}
		// Each expected rule is a distinct reject, so the count and exact
		// matches exclude earlier rules that could bypass those rejects.
		for _, rule := range rules {
			if _, err := f.call(family, "-C", append([]string{active}, rule...)...); err != nil {
				return err
			}
		}
	}
	return nil
}
func verifySystemPackages(p Policy) (Policy, error) {
	return verifyPackages(p, func(user uint32) ([]byte, error) {
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		return exec.CommandContext(ctx, "/system/bin/cmd", "package", "list", "packages", "-U", "--user", strconv.Itoa(int(user))).Output()
	})
}

// Read each Android user's inventory once, without caching across operations.
func verifyPackages(p Policy, list func(uint32) ([]byte, error)) (Policy, error) {
	inventories := make(map[uint32]map[string][]uint32)
	var replacements Policy
	var result error
	for _, a := range p.Apps {
		user := a.UID / 100000
		installed, ok := inventories[user]
		if !ok {
			out, err := list(user)
			inventories[user] = nil
			if err != nil {
				result = errors.Join(result, errors.New("cannot verify installed applications"))
				continue
			}
			installed = make(map[string][]uint32)
			for _, line := range strings.Split(string(out), "\n") {
				record, ok := strings.CutPrefix(strings.TrimSpace(line), "package:")
				if !ok {
					continue
				}
				pkg, uidText, ok := strings.Cut(record, " uid:")
				uid, err := strconv.ParseUint(uidText, 10, 32)
				if ok && err == nil && uint32(uid)/100000 == user {
					installed[pkg] = append(installed[pkg], uint32(uid))
				}
			}
			inventories[user] = installed
		}
		uids := installed[a.Package]
		if len(uids) == 1 && uids[0] == a.UID {
			continue
		}
		result = errors.Join(result, fmt.Errorf("application UID changed or application unavailable: %s; reselect it", a.Package))
		for _, uid := range uids {
			if uid != a.UID && uid%100000 >= 10000 && uid%100000 < 20000 {
				// A new UID can be blocked immediately; only a newly saved
				// policy can grant it use of the selected proxy.
				replacements.Apps = append(replacements.Apps, App{Package: a.Package, UID: uid})
			}
		}
	}
	return replacements, result
}
func writePolicy(path string, p Policy) error {
	data, err := json.Marshal(p)
	if err != nil {
		return err
	}
	f, err := os.OpenFile(path+".new", os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0600)
	if err != nil {
		return err
	}
	if _, err = f.Write(data); err == nil {
		err = f.Sync()
	}
	closeErr := f.Close()
	if err != nil {
		return err
	}
	if closeErr != nil {
		return closeErr
	}
	return os.Rename(path+".new", path)
}

// Manage is called only by the root control script under its action lock.
func Manage(command string) error {
	return manage(command, PolicyPath, os.Stdin, os.Stdout, SystemFirewall(), verifySystemPackages, systemPrivateDNSOff)
}
func manage(command, path string, input io.Reader, output io.Writer, f Firewall, verify func(Policy) (Policy, error), privateDNSOff ...func() bool) error {
	owned := filepath.Join(filepath.Dir(path), "protection-owned")
	_, ownershipErr := os.Stat(owned)
	if ownershipErr != nil && !os.IsNotExist(ownershipErr) {
		return ownershipErr
	}
	hasOwnership := ownershipErr == nil
	stampPath := filepath.Join(filepath.Dir(path), "protection-firewall")
	claim := func() error { return os.WriteFile(owned, []byte("1\n"), 0600) }
	remove := func() error {
		if !hasOwnership {
			return nil
		}
		if err := f.Remove(); err != nil {
			return err
		}
		return os.Remove(owned)
	}
	if command == "disable" {
		// Commit the user's intent before removing any existing blocks.
		if err := writePolicy(path, Policy{Version: 1}); err != nil {
			return err
		}
		return remove()
	}
	p, err := Read(path)
	if err != nil {
		return err
	}
	dnsConflict := func(policy Policy) bool {
		return policy.StrictDNS && len(privateDNSOff) != 0 && !privateDNSOff[0]()
	}
	checkPrivateDNS := func(policy Policy) error {
		if dnsConflict(policy) {
			return errors.New("严格 DNS 需要先在系统设置中关闭私人 DNS。")
		}
		return nil
	}
	verifiedRules := func() bool {
		if !p.Enabled() || !hasOwnership {
			return false
		}
		stamp, err := os.ReadFile(stampPath)
		if err != nil {
			return false
		}
		actual, err := f.fingerprint(false)
		return err == nil && string(stamp) == p.Digest()+" "+actual+"\n"
	}
	switch command {
	case "policy":
		return json.NewEncoder(output).Encode(struct {
			Policy Policy `json:"policy"`
		}{p})
	case "stage":
		data, err := io.ReadAll(io.LimitReader(input, MaxBytes+1))
		if err != nil {
			return err
		}
		var input struct {
			Expected string          `json:"expected"`
			Policy   json.RawMessage `json:"policy"`
		}
		if len(data) > MaxBytes {
			return errors.New("policy too large")
		}
		d := json.NewDecoder(strings.NewReader(string(data)))
		d.DisallowUnknownFields()
		if err = d.Decode(&input); err != nil {
			return err
		}
		var extra any
		if d.Decode(&extra) != io.EOF {
			return errors.New("trailing data")
		}
		if input.Expected != p.Digest() {
			return errors.New("保护设置已变化，请刷新后重试。")
		}
		next, err := Decode(input.Policy)
		if err != nil {
			return err
		}
		if err = checkPrivateDNS(next); err != nil {
			return err
		}
		if _, err = verify(next); err != nil {
			return err
		}
		if p.Enabled() || next.Enabled() {
			if err = claim(); err != nil {
				return err
			}
			if err = f.Hold(p, next); err != nil {
				return err
			}
		}
		return writePolicy(path, next)
	case "hold":
		if !p.Enabled() {
			return nil
		}
		if err = claim(); err != nil {
			return err
		}
		return f.Hold(p, p)
	case "activate":
		if !p.Enabled() {
			return remove()
		}
		replacements, bindingErr := verify(p)
		if err = errors.Join(bindingErr, checkPrivateDNS(p)); err != nil {
			return errors.Join(err, f.Hold(p, replacements))
		}
		return activateAndRecord(f, p, stampPath)
	case "status":
		if !verifiedRules() {
			return errors.New("protection firewall needs recovery")
		}
		return nil
	case "check", "repair", "repair-running":
		if !hasOwnership {
			return nil
		}
		if !p.Enabled() {
			return errors.New("protection ownership remains without a saved policy")
		}
		coreMatches := true
		if command == "repair-running" {
			marker, _ := os.ReadFile(filepath.Join(filepath.Dir(path), "protection-core"))
			coreMatches = strings.TrimSpace(string(marker)) == p.Digest()
		}
		replacements, bindingErr := verify(p)
		policyErr := errors.Join(bindingErr, checkPrivateDNS(p))
		if coreMatches && policyErr == nil && verifiedRules() {
			return nil
		}
		if command == "check" {
			return errors.Join(policyErr, errors.New("protection firewall needs recovery"))
		}
		if err = f.Hold(p, replacements); err != nil {
			return errors.Join(policyErr, err)
		}
		if policyErr != nil {
			// The watcher can keep its normal cadence for a verified HOLD.
			// Command failure still indicates this policy cannot be activated.
			fmt.Fprintln(output, "protection_held=1")
			return policyErr
		}
		if !coreMatches {
			return errors.New("running core policy differs; restart required")
		}
		return activateAndRecord(f, p, stampPath)
	case "get":
		_, bindingErr := verify(p)
		residual := false
		for _, family := range firewallFamilies {
			for _, chain := range []string{hold, anchor} {
				if _, err := f.call(family, "-C", "OUTPUT", "-j", chain); err == nil {
					residual = true
				}
			}
		}
		state := struct {
			Policy             Policy `json:"policy"`
			Firewall           bool   `json:"firewall"`
			Binding            bool   `json:"binding"`
			CorePolicy         bool   `json:"corePolicy"`
			Digest             string `json:"digest"`
			Residual           bool   `json:"residual"`
			PrivateDNSConflict bool   `json:"privateDNSConflict"`
		}{Policy: p, Firewall: verifiedRules(), Binding: bindingErr == nil, Digest: p.Digest(), Residual: residual, PrivateDNSConflict: dnsConflict(p)}
		marker, _ := os.ReadFile(filepath.Join(filepath.Dir(path), "protection-core"))
		state.CorePolicy = strings.TrimSpace(string(marker)) == p.Digest()
		return json.NewEncoder(output).Encode(state)
	case "remove":
		return remove()
	case "check-hotspot":
		if p.Enabled() {
			return errors.New("请先关闭应用防泄漏保护，再开启热点共享代理。")
		}
		return nil
	case "mark":
		return os.WriteFile(filepath.Join(filepath.Dir(path), "protection-core"), []byte(p.Digest()), 0600)
	default:
		return errors.New("unknown protection command")
	}
}

func activateAndRecord(f Firewall, p Policy, path string) error {
	if err := f.Activate(p); err != nil {
		return err
	}
	fingerprint, err := f.fingerprint(false)
	if err != nil {
		return err
	}
	return os.WriteFile(path, []byte(p.Digest()+" "+fingerprint+"\n"), 0600)
}
