package io.minclash.app;

/** Keep a user's edits separate from the last root-owned policy snapshot. */
class ProtectionEdits {
    ProtectionPolicy draft;
    boolean dirty;
    private ProtectionPolicy unconfirmed;

    boolean acceptSnapshot(ProtectionPolicy saved, boolean replace) {
        boolean persisted = unconfirmed != null && unconfirmed.sameAs(saved) && unconfirmed.sameAs(draft);
        if (persisted) { dirty = false; unconfirmed = null; }
        if (replace || draft == null || !dirty) draft = saved;
        return persisted;
    }

    void finishApply(boolean persisted) {
        finishApply(persisted, null);
    }

    void finishApply(boolean persisted, ProtectionPolicy attempted) {
        if (persisted) dirty = false;
        unconfirmed = persisted ? null : attempted;
    }
}
