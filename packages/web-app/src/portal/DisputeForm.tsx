import { useState } from 'react';
import {
  DISPUTE_CATEGORIES,
  preTag,
  type DisputeCategory,
} from './statementModel.ts';

// Dispute submission (TG4-SEL-4, FR-704): the four structured categories,
// free notes, optional attachment names, and the ledger/deal reference;
// the symptom picker pre-tags the category via the backward-chaining query
// so triage starts classified.

export interface DisputeFormProps {
  readonly ledgerId?: string;
  readonly transactionId?: string;
  readonly onSubmit: (payload: DisputePayload) => void;
}

export interface DisputePayload {
  readonly category: DisputeCategory;
  readonly notes: string;
  readonly ledgerId?: string | undefined;
  readonly transactionId?: string | undefined;
  readonly symptom: string;
  readonly attachments: readonly string[];
}

export const DISPUTE_SYMPTOMS = [
  { value: 'late_arrival', label: 'Deal arrived after the period closed' },
  { value: 'missing_source_row', label: 'A source row is missing' },
  { value: 'currency_mismatch', label: 'Currency looks wrong' },
  { value: 'split_sum_off', label: 'My credit split does not sum to 100%' },
  { value: 'window_boundary', label: 'The window reset date looks wrong' },
  { value: 'payout_wrong', label: 'The payout math looks wrong' },
] as const;

export function DisputeForm({ ledgerId, transactionId, onSubmit }: DisputeFormProps) {
  const [symptom, setSymptom] = useState<string>(DISPUTE_SYMPTOMS[0].value);
  const [category, setCategory] = useState<DisputeCategory>(preTag(DISPUTE_SYMPTOMS[0].value));
  const [notes, setNotes] = useState('');
  const [attachments, setAttachments] = useState<string>('');

  const pickSymptom = (value: string): void => {
    setSymptom(value);
    setCategory(preTag(value)); // backward-chaining pre-tag
  };

  return (
    <form
      aria-labelledby="dispute-heading"
      onSubmit={(e) => {
        e.preventDefault();
        onSubmit({
          category,
          notes,
          ledgerId,
          transactionId,
          symptom,
          attachments: attachments
            .split(',')
            .map((a) => a.trim())
            .filter((a) => a.length > 0),
        });
      }}
    >
      <h3 id="dispute-heading">Submit a dispute</h3>
      <label>
        What happened?
        <select
          aria-label="symptom"
          value={symptom}
          onChange={(e) => pickSymptom(e.target.value)}
        >
          {DISPUTE_SYMPTOMS.map((s) => (
            <option key={s.value} value={s.value}>
              {s.label}
            </option>
          ))}
        </select>
      </label>
      <p data-testid="pretag">
        Pre-tagged category: <strong>{category}</strong>
        <span role="note"> (auto-derived; you can change it)</span>
      </p>
      <label>
        Category
        <select
          aria-label="category"
          value={category}
          onChange={(e) => setCategory(e.target.value as DisputeCategory)}
        >
          {DISPUTE_CATEGORIES.map((c) => (
            <option key={c} value={c}>
              {c.replaceAll('_', ' ')}
            </option>
          ))}
        </select>
      </label>
      <label>
        Notes
        <textarea
          aria-label="notes"
          rows={4}
          value={notes}
          onChange={(e) => setNotes(e.target.value)}
          required
        />
      </label>
      <label>
        Attachments (comma-separated names)
        <input
          aria-label="attachments"
          value={attachments}
          onChange={(e) => setAttachments(e.target.value)}
          placeholder="offer_letter.pdf, crm_screenshot.png"
        />
      </label>
      {ledgerId ? <input type="hidden" name="ledgerId" value={ledgerId} /> : null}
      {transactionId ? (
        <p role="note">Disputing deal {transactionId.slice(0, 8)}…</p>
      ) : null}
      <button type="submit" data-testid="dispute-submit">
        Submit dispute
      </button>
    </form>
  );
}
