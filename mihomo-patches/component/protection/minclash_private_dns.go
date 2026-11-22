package protection

import (
	"context"
	"os/exec"
	"runtime"
	"strings"
	"time"
)

func systemPrivateDNSOff() bool {
	if runtime.GOOS != "android" {
		return true
	}
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	return privateDNSOff(func(key string) (string, error) {
		command := exec.CommandContext(ctx, "/system/bin/settings", "get", "global", key)
		command.WaitDelay = time.Second
		output, err := command.Output()
		return strings.TrimSuffix(string(output), "\n"), err
	})
}

func privateDNSOff(readSetting func(string) (string, error)) bool {
	mode, err := readSetting("private_dns_mode")
	if err != nil {
		return false
	}
	// Android uses the default setting when the mode is unset and defaults to
	// opportunistic DNS when both are unset. The settings CLI prints null for
	// missing values. Unknown modes or read errors cannot establish that it is off.
	if mode == "" || mode == "null" {
		mode, err = readSetting("private_dns_default_mode")
	}
	return err == nil && mode == "off"
}
