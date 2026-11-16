# Protection chains belong to the saved policy, NOT to a TUN session.
export MINCLASH_PROTECTION_FILE="$DATA/protection.json"

protection_command() { "$CORE" -minclash-protection "$1"; }

set_protection() {
    local was_running=0
    read_pid daemon >/dev/null && was_running=1
    if [ "$1" = set ]; then
        [ ! -f "$DATA/hotspot-on" ] || fail '请先关闭热点共享代理，再启用应用防泄漏保护。'
        # stdin is bounded and decoded by the native helper; no shell eval.
        protection_command stage || fail '保护策略未完整应用；已有阻断会保留，请检查设置或明确关闭保护。'
    else
        # Explicit recovery also works when the saved policy is corrupt.
        # Stop first, so releasing HOLD cannot expose a still-running old policy.
        :
    fi
    stop || fail '无法停止原内核；已保留阻断规则。'
    if [ "$1" = off ]; then
        protection_command disable || fail '无法完整移除保护规则，请重试关闭保护。'
    else
        protection_command activate || fail '防火墙或应用 UID 校验失败；已保持阻断，请重新选择应用或关闭保护。'
    fi
    if [ "$was_running" = 1 ]; then start; fi
}
