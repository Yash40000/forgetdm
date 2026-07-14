'use client';

import { Tooltip } from '@mantine/core';

import { BE_STAGES, type BeStageState } from '../utils';

/**
 * The lifecycle as a single thin rail — filled dots for done, ring for current,
 * hollow for todo, faded for stages still in the classic console. No boxes.
 */
export function JourneyRail({
  states,
  activeTab,
  onNavigate
}: {
  states: Record<string, BeStageState>;
  activeTab: string | null;
  onNavigate: (tab: string) => void;
}) {
  return (
    <div className="be-journey-rail" role="navigation" aria-label="Entity lifecycle">
      {BE_STAGES.map((stage) => {
        const state = states[stage.id];
        const isCurrent = stage.tab && stage.tab === activeTab;
        const className = [
          'be-journey-step',
          state?.done ? 'is-done' : '',
          isCurrent ? 'is-current' : '',
          stage.classic ? 'is-classic' : ''
        ]
          .filter(Boolean)
          .join(' ');
        return (
          <Tooltip
            key={stage.id}
            label={
              stage.classic
                ? `${stage.goal} (still in the classic console)`
                : `${stage.goal}${state?.hint ? ` — ${state.hint}` : ''}`
            }
            withArrow
            openDelay={300}
          >
            <button type="button" className={className} disabled={!stage.tab} onClick={() => stage.tab && onNavigate(stage.tab)}>
              <span className="be-journey-dot" aria-hidden />
              <span className="be-journey-label">{stage.label}</span>
            </button>
          </Tooltip>
        );
      })}
    </div>
  );
}
