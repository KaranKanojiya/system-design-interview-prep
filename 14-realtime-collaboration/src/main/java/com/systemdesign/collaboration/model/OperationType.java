package com.systemdesign.collaboration.model;

/**
 * Types of operations that can be applied to a document.
 *
 * In Operational Transformation (OT), every edit is one of:
 *   - INSERT: adds new text at a position
 *   - DELETE: removes text starting at a position
 *   - RETAIN: skips over existing text (used in some OT representations
 *             to express "keep N characters unchanged")
 *
 * Interview note: Google Docs uses INSERT and DELETE as the primary
 * operations. RETAIN is part of the "operation composition" model where
 * an operation describes the entire document traversal.
 */
public enum OperationType {

    /** Insert text at a given position in the document. */
    INSERT,

    /** Delete a number of characters starting at a given position. */
    DELETE,

    /**
     * Retain (skip) characters — used when composing operations that
     * describe a full document traversal.  Not always needed in a
     * simplified OT implementation, but included for completeness.
     */
    RETAIN
}
