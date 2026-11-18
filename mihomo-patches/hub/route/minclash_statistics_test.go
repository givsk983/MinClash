package route

import (
	"encoding/json"
	"testing"

	"github.com/metacubex/http/httptest"
)

func TestMinClashStatistics(t *testing.T) {
	read := func(path string) map[string]json.RawMessage {
		t.Helper()
		w := httptest.NewRecorder()
		minclashStatistics(w, httptest.NewRequest("GET", path, nil))
		var data map[string]json.RawMessage
		if w.Code != 200 || json.Unmarshal(w.Body.Bytes(), &data) != nil {
			t.Fatal("expected a JSON statistics response")
		}
		return data
	}
	summary := read("/minclash/statistics")
	if len(summary) != 3 || len(summary["session"]) == 0 || summary["uploadTotal"] == nil || summary["downloadTotal"] == nil {
		t.Fatalf("unexpected summary fields: %v", summary)
	}
	detail := read("/minclash/statistics?details=1")
	var count int
	if string(detail["session"]) != string(summary["session"]) || json.Unmarshal(detail["connections"], &count) != nil || count < 0 {
		t.Fatal("expected a stable session and numeric connection count")
	}
}
