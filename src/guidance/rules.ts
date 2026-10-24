// SPDX-License-Identifier: Apache-2.0
// Mirror of GuidanceRuleId tiers (Kotlin core/Contracts.kt). A unit test parses the Kotlin enum
// and fails if the two drift apart. Used only for display (summary colours), never for decisions.
import type { RuleId, Severity } from '../../modules/guided-capture';

export const RULE_TIER: Record<RuleId, number> = {
  T1: 0, T2: 0, T4: 0, T5: 0,
  G4: 1,
  G1: 2, L1: 2, L3: 2, S1: 2,
  G2: 3, L2: 3, T6: 3, S2: 3, T7: 3,
  G5: 4, L4: 4, L5: 4, S3: 4, T3: 4,
  G3: 5, C3: 5, C5: 5,
  C1: 6, C2: 6,
  C6: 7,
  S4: 4, C4: 6,
};

export function severityForRule(id: RuleId): Severity {
  const tier = RULE_TIER[id];
  if (tier <= 1) return 'CRITICAL';
  if (tier <= 3) return 'WARNING';
  return 'INFO';
}
