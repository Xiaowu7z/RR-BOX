package rule

// Copied temporarily into the pinned sing-box route/rule package by
// verify-app-node-routing.py. This invokes its real rule constructors and Match
// methods with supplied Android owner metadata; it does not emulate Android's
// UID lookup or alter the production core used for the socket checks.

import (
	"context"
	"encoding/base64"
	stdjson "encoding/json"
	"os"
	"path/filepath"
	"testing"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common/json"
	M "github.com/sagernet/sing/common/metadata"
)

type rrboxAppVariant struct {
	File      string `json:"file"`
	Engine    string `json:"engine"`
	Smart     bool   `json:"smart"`
	Available bool   `json:"secondary_available"`
}

type rrboxAppCheck struct {
	Name     string `json:"name"`
	Expected string `json:"expected"`
	Actual   string `json:"actual"`
	Passed   bool   `json:"passed"`
}

type rrboxAppResult struct {
	File   string          `json:"file"`
	Checks []rrboxAppCheck `json:"checks"`
}

func TestRRBOXAppNodeRoutingFixtures(t *testing.T) {
	directory, reportPath := os.Getenv("RRBOX_APP_NODE_FIXTURES"), os.Getenv("RRBOX_APP_NODE_RULE_REPORT")
	if directory == "" || reportPath == "" {
		t.Fatal("fixture and report paths are required")
	}
	var manifest struct {
		BoundPackage    string            `json:"bound_package"`
		OrdinaryPackage string            `json:"ordinary_package"`
		SecondaryID     string            `json:"secondary_node_id"`
		Variants        []rrboxAppVariant `json:"variants"`
	}
	read := func(path string, target any) {
		t.Helper()
		data, err := os.ReadFile(path)
		if err != nil {
			t.Fatal(err)
		}
		if err = stdjson.Unmarshal(data, target); err != nil {
			t.Fatal(err)
		}
	}
	read(filepath.Join(directory, "manifest.json"), &manifest)
	if len(manifest.Variants) != 12 {
		t.Fatalf("need all 12 production variants, got %d", len(manifest.Variants))
	}
	extra := "rr-app-node-" + base64.RawURLEncoding.EncodeToString([]byte(manifest.SecondaryID))
	var results []*rrboxAppResult
	t.Cleanup(func() {
		encoded, err := stdjson.MarshalIndent(results, "", "  ")
		if err != nil {
			t.Error(err)
			return
		}
		if err = os.WriteFile(reportPath, append(encoded, '\n'), 0600); err != nil {
			t.Error(err)
		}
	})
	for _, variant := range manifest.Variants {
		t.Run(variant.File, func(t *testing.T) {
			result := &rrboxAppResult{File: variant.File}
			results = append(results, result)
			var config struct {
				Inbounds []struct {
					Tag string `json:"tag"`
				} `json:"inbounds"`
				Route struct {
					Rules []stdjson.RawMessage `json:"rules"`
					Final string               `json:"final"`
				} `json:"route"`
				DNS struct {
					Rules []stdjson.RawMessage `json:"rules"`
					Final string               `json:"final"`
				} `json:"dns"`
			}
			read(filepath.Join(directory, variant.File), &config)
			ctx := context.Background()
			logger := log.NewNOPFactory().NewLogger("rrbox-app-routing")
			var routes []adapter.Rule
			var resolvers []adapter.DNSRule
			for _, raw := range config.Route.Rules {
				var options option.Rule
				if err := json.UnmarshalContext(ctx, raw, &options); err != nil {
					t.Fatal(err)
				}
				rule, err := NewRule(ctx, logger, options, true)
				if err != nil {
					t.Fatal(err)
				}
				if err = rule.Start(); err != nil {
					t.Fatal(err)
				}
				t.Cleanup(func() { _ = rule.Close() })
				routes = append(routes, rule)
			}
			for _, raw := range config.DNS.Rules {
				var options option.DNSRule
				if err := json.UnmarshalContext(ctx, raw, &options); err != nil {
					t.Fatal(err)
				}
				rule, err := NewDNSRule(ctx, logger, options, true, false)
				if err != nil {
					t.Fatal(err)
				}
				if err = rule.Start(); err != nil {
					t.Fatal(err)
				}
				t.Cleanup(func() { _ = rule.Close() })
				resolvers = append(resolvers, rule)
			}
			check := func(name, want, got string) {
				t.Helper()
				result.Checks = append(result.Checks, rrboxAppCheck{name, want, got, want == got})
				if want != got {
					t.Errorf("%s: want %q, got %q", name, want, got)
				}
			}
			chooseRoute := func(metadata adapter.InboundContext) string {
				for _, rule := range routes {
					metadata.ResetRuleCache()
					if !rule.Match(&metadata) {
						continue
					}
					switch action := rule.Action().(type) {
					case *RuleActionRoute:
						return action.Outbound
					case *RuleActionReject:
						return "reject"
					case *RuleActionHijackDNS:
						return "hijack-dns"
					}
				}
				return config.Route.Final
			}
			chooseDNS := func(metadata adapter.InboundContext) string {
				for _, rule := range resolvers {
					metadata.ResetRuleCache()
					if !rule.Match(&metadata) {
						continue
					}
					switch action := rule.Action().(type) {
					case *RuleActionDNSRoute:
						return action.Server
					case *RuleActionReject:
						return "reject"
					}
				}
				return config.DNS.Final
			}
			metadata := func(packages []string, inbound, network, domain string, port uint16) adapter.InboundContext {
				value := adapter.InboundContext{
					Inbound: inbound, Network: network, IPVersion: 4, Domain: domain,
					Destination: M.ParseSocksaddrHostPort("1.1.1.2", port),
					Source:      M.ParseSocksaddrHostPort("172.19.0.2", 23456), QueryType: 1,
				}
				if packages != nil {
					value.ProcessInfo = &adapter.ConnectionOwner{AndroidPackageNames: packages}
				}
				return value
			}
			mainInbound := config.Inbounds[0].Tag
			boundInbound := mainInbound
			if variant.Engine == "hev" {
				if len(config.Inbounds) != 2 {
					t.Fatalf("HEV needs main and one bound inlet, got %d", len(config.Inbounds))
				}
				boundInbound = config.Inbounds[1].Tag
			}
			boundOwner, ordinaryOwner := []string{manifest.BoundPackage}, []string{manifest.OrdinaryPackage}
			if variant.Engine == "hev" {
				boundOwner, ordinaryOwner = nil, nil
			}
			wantBound, wantBoundDNS := extra, "dns-"+extra
			if !variant.Available {
				wantBound, wantBoundDNS = "reject", "reject"
			}
			for _, network := range []string{"tcp", "udp"} {
				check(network+"-bound-app", wantBound, chooseRoute(metadata(boundOwner, boundInbound, network, "probe.invalid", 443)))
				check(network+"-ordinary-app", "proxy", chooseRoute(metadata(ordinaryOwner, mainInbound, network, "probe.invalid", 443)))
				check(network+"-bound-overrides-domestic", wantBound, chooseRoute(metadata(boundOwner, boundInbound, network, "gateway.kugou.com", 443)))
				if variant.Engine != "hev" {
					for name, owner := range map[string][]string{"nil-owner": nil, "empty-owner": {}, "blank-package": {""}} {
						check(network+"-"+name+"-reject", "reject", chooseRoute(metadata(owner, mainInbound, network, "probe.invalid", 443)))
					}
					check(network+"-lookalike-package-unbound", "proxy", chooseRoute(metadata([]string{manifest.BoundPackage + ".lookalike"}, mainInbound, network, "probe.invalid", 443)))
					check(network+"-shared-uid-listed-package", wantBound, chooseRoute(metadata([]string{manifest.OrdinaryPackage, manifest.BoundPackage}, mainInbound, network, "probe.invalid", 443)))
				}
				dnsMetadata := metadata(boundOwner, boundInbound, network, "probe.invalid", 53)
				dnsMetadata.Protocol = "dns"
				check(network+"-dns-hijack-precedes-app-terminal", "hijack-dns", chooseRoute(dnsMetadata))
			}
			check("route-final-main-unchanged", "proxy", config.Route.Final)
			check("dns-final-main-unchanged", "dns-remote", config.DNS.Final)
			check("bound-app-dns", wantBoundDNS, chooseDNS(metadata(boundOwner, boundInbound, "udp", "probe.invalid", 53)))
			check("bound-app-domestic-dns", wantBoundDNS, chooseDNS(metadata(boundOwner, boundInbound, "udp", "gateway.kugou.com", 53)))
			check("ordinary-app-dns", "dns-remote", chooseDNS(metadata(ordinaryOwner, mainInbound, "udp", "probe.invalid", 53)))
			check("shared-netd-dns-keeps-default", "dns-remote", chooseDNS(metadata(nil, mainInbound, "udp", "probe.invalid", 53)))
			check("main-bootstrap-remains-direct", "dns-direct", chooseDNS(metadata(boundOwner, boundInbound, "udp", "la-bootstrap.invalid", 53)))
			if variant.Available {
				check("secondary-bootstrap-remains-direct", "dns-direct", chooseDNS(metadata(boundOwner, boundInbound, "udp", "hk-bootstrap.invalid", 53)))
			}
			wantDomestic := "dns-remote"
			if variant.Smart {
				wantDomestic = "dns-direct"
			}
			check("ordinary-domestic-dns-keeps-policy", wantDomestic, chooseDNS(metadata(ordinaryOwner, mainInbound, "udp", "gateway.kugou.com", 53)))
		})
	}
}
