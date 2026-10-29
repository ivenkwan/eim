import type { Protections } from './planModel.ts';

// Protections editor (FR-405): draws, caps, clawback windows with enum
// guards — free text never reaches the plan; every field maps onto the
// validated DSL attributes.

export interface ProtectionsEditorProps {
  readonly protections: Protections;
  readonly onChange: (next: Protections) => void;
  readonly disabled?: boolean;
}

export function ProtectionsEditor({ protections, onChange, disabled }: ProtectionsEditorProps) {
  const set = (patch: Partial<Protections>): void => onChange({ ...protections, ...patch });

  return (
    <section className="protections" aria-labelledby="protections-heading">
      <h4 id="protections-heading">Protections</h4>
      <div style={{ display: 'grid', gap: 6 }}>
        <label>
          draw kind:{' '}
          <select
            aria-label="draw kind"
            value={protections.drawKind ?? ''}
            disabled={disabled}
            onChange={(e) =>
              set({ drawKind: (e.target.value || undefined) as Protections['drawKind'] })
            }
          >
            <option value="">none</option>
            <option value="RECOVERABLE">recoverable</option>
            <option value="NON_RECOVERABLE">non-recoverable</option>
          </select>
        </label>
        <label>
          cap scope:{' '}
          <select
            aria-label="cap scope"
            value={protections.capScope ?? ''}
            disabled={disabled}
            onChange={(e) =>
              set({ capScope: (e.target.value || undefined) as Protections['capScope'] })
            }
          >
            <option value="">none</option>
            <option value="PERIOD">period</option>
            <option value="ANNUAL">annual</option>
          </select>
        </label>
        <label>
          cap basis:{' '}
          <select
            aria-label="cap basis"
            value={protections.capBasis ?? ''}
            disabled={disabled}
            onChange={(e) =>
              set({ capBasis: (e.target.value || undefined) as Protections['capBasis'] })
            }
          >
            <option value="">amount</option>
            <option value="AMOUNT">amount</option>
            <option value="PERCENT">percent</option>
          </select>
        </label>
        <label>
          cap value:{' '}
          <input
            aria-label="cap value"
            value={protections.capValue ?? ''}
            disabled={disabled}
            inputMode="decimal"
            onChange={(e) => set({ capValue: e.target.value || undefined })}
          />
        </label>
        <label>
          clawback window (days):{' '}
          <input
            aria-label="clawback window days"
            value={protections.clawbackWindowDays ?? ''}
            disabled={disabled}
            inputMode="numeric"
            onChange={(e) => set({ clawbackWindowDays: e.target.value || undefined })}
          />
        </label>
      </div>
    </section>
  );
}
