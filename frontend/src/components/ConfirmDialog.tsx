import { useEffect, useRef, useState } from 'react';

/**
 * Confirmation for destructive or operational actions. Optionally requires a reason (sent to the
 * audit log) and/or typing a confirmation phrase.
 */
export function ConfirmDialog({ title, body, confirmLabel, requireReason, typeToConfirm, busy, onConfirm, onCancel }: {
  title: string;
  body: React.ReactNode;
  confirmLabel: string;
  requireReason?: boolean;
  typeToConfirm?: string;
  busy?: boolean;
  onConfirm: (reason: string) => void;
  onCancel: () => void;
}) {
  const [reason, setReason] = useState('');
  const [typed, setTyped] = useState('');
  const ref = useRef<HTMLDialogElement>(null);

  useEffect(() => {
    const d = ref.current;
    if (d && !d.open) d.showModal();
  }, []);

  const ready = (!requireReason || reason.trim().length > 0) && (!typeToConfirm || typed === typeToConfirm) && !busy;
  return (
    <dialog ref={ref} className="dialog" onCancel={(e) => { e.preventDefault(); onCancel(); }}>
      <form method="dialog" onSubmit={(e) => { e.preventDefault(); if (ready) onConfirm(reason.trim()); }}>
        <h2>{title}</h2>
        <div className="dialog-body">{body}</div>
        {requireReason && (
          <label className="field">
            Reason (recorded in the audit log)
            <input autoFocus value={reason} maxLength={500} onChange={(e) => setReason(e.target.value)} />
          </label>
        )}
        {typeToConfirm && (
          <label className="field">
            <span>Type <code>{typeToConfirm}</code> to confirm</span>
            <input autoFocus={!requireReason} value={typed} onChange={(e) => setTyped(e.target.value)} />
          </label>
        )}
        <div className="dialog-actions">
          <button type="button" className="btn" onClick={onCancel}>Cancel</button>
          <button type="submit" className="btn danger" disabled={!ready}>{busy ? 'Working…' : confirmLabel}</button>
        </div>
      </form>
    </dialog>
  );
}
