package tunnel

import (
	"time"

	"github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/tunnel"
	"github.com/metacubex/mihomo/tunnel/statistic"
)

// RuleInsight is the stable mobile bridge representation of a loaded Mihomo rule.
type RuleInsight struct {
	Index   int               `json:"index"`
	Type    string            `json:"type"`
	Payload string            `json:"payload"`
	Proxy   string            `json:"proxy"`
	Size    int               `json:"size"`
	Extra   *RuleInsightExtra `json:"extra,omitempty"`
}

// RuleInsightExtra carries runtime hit statistics when the rule supports them.
type RuleInsightExtra struct {
	Disabled  bool      `json:"disabled"`
	HitCount  uint64    `json:"hitCount"`
	HitAt     time.Time `json:"hitAt"`
	MissCount uint64    `json:"missCount"`
	MissAt    time.Time `json:"missAt"`
}

// QueryConnections returns one point-in-time snapshot without opening a controller port.
func QueryConnections() *statistic.Snapshot {
	return statistic.DefaultManager.Snapshot()
}

// CloseConnection closes the matching tracker and treats an already-ended connection as success.
func CloseConnection(id string) {
	if connection := statistic.DefaultManager.Get(id); connection != nil {
		_ = connection.Close()
	}
}

// QueryRules returns the active ordered rule chain and its optional runtime counters.
func QueryRules() []*RuleInsight {
	rawRules := tunnel.Rules()
	result := make([]*RuleInsight, 0, len(rawRules))
	for index, rule := range rawRules {
		item := &RuleInsight{
			Index: index, Type: rule.RuleType().String(), Payload: rule.Payload(), Proxy: rule.Adapter(), Size: -1,
		}
		if wrapper, ok := rule.(constant.RuleWrapper); ok {
			item.Extra = &RuleInsightExtra{
				Disabled: wrapper.IsDisabled(), HitCount: wrapper.HitCount(), HitAt: wrapper.HitAt(),
				MissCount: wrapper.MissCount(), MissAt: wrapper.MissAt(),
			}
			rule = wrapper.Unwrap()
		}
		result = append(result, item)
	}
	return result
}
