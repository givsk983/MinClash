package protection

import (
	"errors"
	"testing"
)

func TestMinClashEffectivePrivateDNSMode(t *testing.T) {
	for _, test := range []struct {
		name, selected, defaultMode string
		readFails, wantOff          bool
	}{
		{"off", "off", "opportunistic", false, true},
		{"automatic", "opportunistic", "off", false, false},
		{"provider", "hostname", "off", false, false},
		{"default off", "null", "off", false, true},
		{"default automatic", "", "opportunistic", false, false},
		{"unset", "null", "null", false, false},
		{"unknown", "disabled", "off", false, false},
		{"read failure", "off", "off", true, false},
	} {
		t.Run(test.name, func(t *testing.T) {
			read := func(key string) (string, error) {
				if test.readFails {
					return "", errors.New("settings service unavailable")
				}
				if key == "private_dns_mode" {
					return test.selected, nil
				}
				return test.defaultMode, nil
			}
			if got := privateDNSOff(read); got != test.wantOff {
				t.Fatalf("private DNS off = %v, want %v", got, test.wantOff)
			}
		})
	}
}
