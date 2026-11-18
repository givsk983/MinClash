package statistic

func (m *Manager) ActiveCount() int {
	return m.connections.Size()
}
