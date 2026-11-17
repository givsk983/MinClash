package protection

import (
	"context"
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

// Hold is installed BEFORE stopping a core or replacing policy. On a partial
// failure the old rules and successfully installed blocks stay in place.
func (f Firewall) Hold(old, next Policy) error {
	for _, family := range []string{"iptables", "ip6tables"} {
		if err := f.ensure(family, hold); err != nil {
			return err
		}
		// Append blocks while an existing HOLD may still be attached. Never flush it.
		for _, uid := range unionUIDs(old, next) {
			rule := []string{"-m", "owner", "--uid-owner", strconv.FormatUint(uint64(uid), 10), "-j", "REJECT"}
			if _, err := f.call(family, "-C", append([]string{hold}, rule...)...); err != nil {
				if err = f.append(family, hold, rule...); err != nil {
					return err
				}
			}
		}
		if old.StrictDNS || next.StrictDNS {
			for _, proto := range []string{"tcp", "udp"} {
				for _, port := range []string{"53", "853"} {
					if err := f.append(family, hold, "-p", proto, "--dport", port, "-j", "REJECT"); err != nil {
						return err
					}
				}
			}
		}
		if _, err := f.call(family, "-C", "OUTPUT", "-j", hold); err != nil {
			if _, err = f.call(family, "-I", "OUTPUT", "1", "-j", hold); err != nil {
				return err
			}
		}
	}
	return nil
}
func (f Firewall) Activate(p Policy) error {
	// Caller must have stopped the old core. HOLD remains until BOTH families work.
	for _, family := range []string{"iptables", "ip6tables"} {
		if err := f.ensure(family, anchor); err != nil {
			return err
		}
		if err := f.ensure(family, active); err != nil {
			return err
		}
		if _, err := f.call(family, "-F", active); err != nil {
			return err
		}
		if p.StrictDNS {
			for _, proto := range []string{"tcp", "udp"} {
				// Android Private DNS is intentionally incompatible with strict mode.
				if err := f.append(family, active, "-p", proto, "--dport", "853", "-m", "owner", "!", "--uid-owner", "0", "-j", "REJECT"); err != nil {
					return err
				}
				if err := f.append(family, active, "!", "-o", "minclash0", "-p", proto, "--dport", "53", "-j", "REJECT"); err != nil {
					return err
				}
			}
		}
		for _, uid := range unionUIDs(p) {
			base := []string{"-m", "owner", "--uid-owner", strconv.FormatUint(uint64(uid), 10)}
			if err := f.append(family, active, append(append([]string{}, base...), "!", "-o", "minclash0", "-j", "REJECT")...); err != nil {
				return err
			}
		}
		if _, err := f.call(family, "-F", anchor); err != nil {
			return err
		}
		if err := f.append(family, anchor, "-j", active); err != nil {
			return err
		}
		if _, err := f.call(family, "-C", "OUTPUT", "-j", anchor); err != nil {
			if _, err = f.call(family, "-I", "OUTPUT", "1", "-j", anchor); err != nil {
				return err
			}
		}
	}
	for _, family := range []string{"iptables", "ip6tables"} {
		if _, err := f.call(family, "-C", "OUTPUT", "-j", hold); err == nil {
			if _, err = f.call(family, "-D", "OUTPUT", "-j", hold); err != nil {
				return err
			}
		}
		if _, err := f.call(family, "-F", hold); err != nil {
			return err
		}
	}
	return nil
}
func (f Firewall) Remove() error {
	for _, family := range []string{"iptables", "ip6tables"} {
		if out, err := f.call(family, "-S", "OUTPUT"); err != nil {
			if errors.Is(err, os.ErrNotExist) || strings.Contains(strings.ToLower(out), "table does not exist") || strings.Contains(strings.ToLower(out), "protocol not available") {
				continue
			}
			return err
		}
		for _, chain := range []string{hold, anchor} {
			for {
				if _, err := f.call(family, "-C", "OUTPUT", "-j", chain); err != nil {
					break
				}
				if _, err := f.call(family, "-D", "OUTPUT", "-j", chain); err != nil {
					return err
				}
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
func (f Firewall) Healthy(p Policy) bool {
	if !p.Enabled() {
		return false
	}
	for _, family := range []string{"iptables", "ip6tables"} {
		if _, err := f.call(family, "-C", "OUTPUT", "-j", hold); err == nil {
			return false
		}
		if _, err := f.call(family, "-C", "OUTPUT", "-j", anchor); err != nil {
			return false
		}
		output, err := f.call(family, "-S", "OUTPUT")
		if err != nil {
			return false
		}
		first := ""
		for _, line := range strings.Split(output, "\n") {
			if strings.HasPrefix(line, "-A OUTPUT ") {
				first = line
				break
			}
		}
		if first != "-A OUTPUT -j "+anchor {
			return false
		}
		if _, err := f.call(family, "-C", anchor, "-j", active); err != nil {
			return false
		}
		for _, uid := range unionUIDs(p) {
			if _, err := f.call(family, "-C", active, "-m", "owner", "--uid-owner", strconv.FormatUint(uint64(uid), 10), "!", "-o", "minclash0", "-j", "REJECT"); err != nil {
				return false
			}
		}
		if p.StrictDNS {
			for _, proto := range []string{"tcp", "udp"} {
				if _, err := f.call(family, "-C", active, "!", "-o", "minclash0", "-p", proto, "--dport", "53", "-j", "REJECT"); err != nil {
					return false
				}
				if _, err := f.call(family, "-C", active, "-p", proto, "--dport", "853", "-m", "owner", "!", "--uid-owner", "0", "-j", "REJECT"); err != nil {
					return false
				}
			}
		}
	}
	return true
}
func VerifyPackages(p Policy) error {
	return verifyPackages(p, func(user uint32) ([]byte, error) {
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		return exec.CommandContext(ctx, "/system/bin/cmd", "package", "list", "packages", "-U", "--user", strconv.Itoa(int(user))).Output()
	})
}

// Read each Android user's inventory once, without caching across operations.
func verifyPackages(p Policy, list func(uint32) ([]byte, error)) error {
	inventories := make(map[uint32]map[string]bool)
	for _, a := range p.Apps {
		user := a.UID / 100000
		installed, ok := inventories[user]
		if !ok {
			out, err := list(user)
			if err != nil {
				return errors.New("cannot verify installed applications")
			}
			installed = make(map[string]bool)
			for _, line := range strings.Split(string(out), "\n") {
				installed[strings.TrimSpace(line)] = true
			}
			inventories[user] = installed
		}
		if !installed[fmt.Sprintf("package:%s uid:%d", a.Package, a.UID)] {
			return fmt.Errorf("application UID changed or application unavailable: %s; reselect it", a.Package)
		}
	}
	return nil
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
	return manage(command, PolicyPath, os.Stdin, os.Stdout, SystemFirewall(), VerifyPackages)
}
func manage(command, path string, input io.Reader, output io.Writer, f Firewall, verify func(Policy) error) error {
	owned := filepath.Join(filepath.Dir(path), "protection-owned")
	_, ownershipErr := os.Stat(owned)
	if ownershipErr != nil && !os.IsNotExist(ownershipErr) {
		return ownershipErr
	}
	hasOwnership := ownershipErr == nil
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
		if err := remove(); err != nil {
			return err
		}
		return writePolicy(path, Policy{Version: 1})
	}
	p, err := Read(path)
	if err != nil {
		return err
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
		if err = verify(next); err != nil {
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
		if err = verify(p); err != nil {
			return err
		}
		return f.Activate(p)
	case "get":
		binding := verify(p) == nil
		residual := false
		for _, family := range []string{"iptables", "ip6tables"} {
			for _, chain := range []string{hold, anchor} {
				if _, err := f.call(family, "-C", "OUTPUT", "-j", chain); err == nil {
					residual = true
				}
			}
		}
		state := struct {
			Policy     Policy `json:"policy"`
			Firewall   bool   `json:"firewall"`
			Binding    bool   `json:"binding"`
			CorePolicy bool   `json:"corePolicy"`
			Digest     string `json:"digest"`
			Residual   bool   `json:"residual"`
		}{p, f.Healthy(p), binding, false, p.Digest(), residual}
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
