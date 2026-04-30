package com.systemdesign.collaboration.ot;

import com.systemdesign.collaboration.model.Operation;

/**
 * The output of an OT transform: two adjusted operations.
 *
 * Given two concurrent operations A and B (both created against the same
 * base version), the transform function produces:
 *   - transformedLocal  (A')  — A adjusted so it can be applied AFTER B
 *   - transformedRemote (B')  — B adjusted so it can be applied AFTER A
 *
 * The key invariant (TP1):
 *   apply(apply(doc, A), B')  ==  apply(apply(doc, B), A')
 *
 * Both paths must converge to the same document state.
 */
public class TransformResult {

    private final Operation transformedLocal;
    private final Operation transformedRemote;

    public TransformResult(Operation transformedLocal, Operation transformedRemote) {
        this.transformedLocal = transformedLocal;
        this.transformedRemote = transformedRemote;
    }

    public Operation getTransformedLocal()  { return transformedLocal; }
    public Operation getTransformedRemote() { return transformedRemote; }

    @Override
    public String toString() {
        return "TransformResult{local'=" + transformedLocal +
               ", remote'=" + transformedRemote + "}";
    }
}
