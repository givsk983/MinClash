package route

import (
	"time"

	"github.com/metacubex/chi/render"
	"github.com/metacubex/http"
	"github.com/metacubex/mihomo/tunnel/statistic"
)

var minclashSession = time.Now().UTC().Format(time.RFC3339Nano)

func minclashStatistics(w http.ResponseWriter, r *http.Request) {
	manager := statistic.DefaultManager
	up, down := manager.Total()
	data := struct {
		Session       string `json:"session"`
		UploadTotal   int64  `json:"uploadTotal"`
		DownloadTotal int64  `json:"downloadTotal"`
		Connections   *int   `json:"connections,omitempty"`
		Memory        uint64 `json:"memory,omitempty"`
	}{Session: minclashSession, UploadTotal: up, DownloadTotal: down}
	if r.URL.Query().Get("details") == "1" {
		count := manager.ActiveCount()
		data.Connections = &count
		data.Memory = manager.Memory()
	}
	render.JSON(w, r, data)
}
