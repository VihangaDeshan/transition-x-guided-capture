// SPDX-License-Identifier: Apache-2.0
// Decides what to say out loud (TDD 6.6). Pure, so the policy is testable: speak only when the
// shown rule changes, never for "nothing to say", never the same rule twice within a few seconds.
import type { GuidancePayload, RuleId } from '../../modules/guided-capture';
import { ruleText, type Lang } from '../i18n/strings';

export interface Utterance {
  text: string;
  languageTag: string;
  fallbackEnglish: string;
}

export class VoicePolicy {
  private lastRule: RuleId | null = null;
  private lastSpokenAt = new Map<RuleId, number>();

  constructor(private readonly repeatGapMs = 8000) {}

  next(g: GuidancePayload, lang: Lang, nowMs: number): Utterance | null {
    const id = g.ruleId;
    const changed = id !== this.lastRule;
    this.lastRule = id;
    if (!id || !changed) return null;
    const last = this.lastSpokenAt.get(id);
    if (last !== undefined && nowMs - last < this.repeatGapMs) return null;
    this.lastSpokenAt.set(id, nowMs);
    return {
      text: stripArrows(ruleText(lang, id, g.direction).short),
      languageTag: lang === 'si' ? 'si-LK' : 'en-US',
      fallbackEnglish: stripArrows(ruleText('en', id, g.direction).short),
    };
  }
}

/** Arrows and symbols are for the eyes, not the voice. */
export function stripArrows(s: string): string {
  return s.replace(/[←→↑↓↻]/g, '').replace(/\s+—\s+/g, '. ').replace(/\s{2,}/g, ' ').trim();
}
