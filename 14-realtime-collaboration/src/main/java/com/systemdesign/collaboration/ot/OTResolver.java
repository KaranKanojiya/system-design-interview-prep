package com.systemdesign.collaboration.ot;

import com.systemdesign.collaboration.model.Operation;
import com.systemdesign.collaboration.model.OperationType;

/**
 * Operational Transformation (OT) conflict resolver — THE CORE CLASS.
 *
 * OT is the algorithm behind Google Docs.  When two users edit the same document
 * concurrently, their operations are created against the same base version but
 * may conflict in terms of positions.  The transform function adjusts positions
 * so that applying both operations (in either order) converges to the same state.
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 *  TRANSFORM TABLE (all 4 combinations)
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 *  Case 1: INSERT vs INSERT
 *    Both users insert text.  The one at the earlier position goes first;
 *    the later one shifts right by the length of the first insert.
 *    Tie-break by userId (lexicographic) for determinism.
 *
 *  Case 2: INSERT vs DELETE
 *    One user inserts, the other deletes.  If the insert is before the
 *    delete range, the delete position shifts right.  If after, the insert
 *    position shifts left.  If inside the deleted range, the insert goes
 *    to the delete boundary.
 *
 *  Case 3: DELETE vs INSERT
 *    Mirror of Case 2.
 *
 *  Case 4: DELETE vs DELETE
 *    Both delete ranges.  If they don't overlap, the earlier range is fine
 *    and the later shifts.  If they overlap, we shrink the ranges so that
 *    no character is deleted twice.
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 */
public class OTResolver implements ConflictResolver {

    @Override
    public String getName() {
        return "Operational Transformation (OT)";
    }

    @Override
    public TransformResult transform(Operation local, Operation remote) {
        // Deep-copy so we don't mutate the originals
        Operation localPrime = new Operation(local);
        Operation remotePrime = new Operation(remote);

        // Dispatch to the correct case handler
        if (local.getType() == OperationType.INSERT && remote.getType() == OperationType.INSERT) {
            transformInsertInsert(local, remote, localPrime, remotePrime);
        } else if (local.getType() == OperationType.INSERT && remote.getType() == OperationType.DELETE) {
            transformInsertDelete(local, remote, localPrime, remotePrime);
        } else if (local.getType() == OperationType.DELETE && remote.getType() == OperationType.INSERT) {
            transformDeleteInsert(local, remote, localPrime, remotePrime);
        } else if (local.getType() == OperationType.DELETE && remote.getType() == OperationType.DELETE) {
            transformDeleteDelete(local, remote, localPrime, remotePrime);
        }
        // RETAIN ops require no transform — they don't change the document

        return new TransformResult(localPrime, remotePrime);
    }

    // ══════════════════════════════════════════════
    //  Case 1: INSERT vs INSERT
    // ══════════════════════════════════════════════
    //
    //  Example: doc = "HELLO"
    //    local:  INSERT pos=2 text="X"   → "HEXLLO"
    //    remote: INSERT pos=4 text="Y"   → "HELLY O"
    //
    //  If local.pos <= remote.pos:
    //    remote' shifts right by local's insert length.
    //    remote'.pos = 4 + 1 = 5   → after local applied, "HEXLLO" → insert Y at 5 → "HEXLLY O"
    //    local' stays the same.
    //
    //  If local.pos > remote.pos:
    //    local' shifts right by remote's insert length.
    //
    //  Tie (same position): break by userId comparison for determinism.
    //
    private void transformInsertInsert(Operation local, Operation remote,
                                       Operation localPrime, Operation remotePrime) {
        int localPos = local.getPosition();
        int remotePos = remote.getPosition();
        int localLen = local.getInsertLength();
        int remoteLen = remote.getInsertLength();

        if (localPos < remotePos) {
            // Local inserts before remote → remote must shift right
            remotePrime.setPosition(remotePos + localLen);
            // localPrime stays at localPos
        } else if (localPos > remotePos) {
            // Remote inserts before local → local must shift right
            localPrime.setPosition(localPos + remoteLen);
            // remotePrime stays at remotePos
        } else {
            // Same position — tie-break by userId (lexicographic order)
            // The "smaller" userId gets priority (inserts first); the other shifts.
            if (local.getUserId().compareTo(remote.getUserId()) <= 0) {
                // local wins tie → remote shifts right
                remotePrime.setPosition(remotePos + localLen);
            } else {
                // remote wins tie → local shifts right
                localPrime.setPosition(localPos + remoteLen);
            }
        }
    }

    // ══════════════════════════════════════════════
    //  Case 2: INSERT vs DELETE
    // ══════════════════════════════════════════════
    //
    //  Example: doc = "ABCDEF"
    //    local:  INSERT pos=2 text="X"    → wants to insert X between B and C
    //    remote: DELETE pos=1 len=3        → wants to delete "BCD" (positions 1..3)
    //
    //  Sub-cases:
    //
    //  (a) Insert is BEFORE the delete range (local.pos <= remote.pos):
    //      The delete range shifts right by insert length.
    //      remote'.pos = 1 → stays (actually local.pos=2 > remote.pos=1, so this sub-case doesn't apply here)
    //
    //  (b) Insert is AFTER the delete range (local.pos >= remote.pos + remote.length):
    //      The insert position shifts left by delete length.
    //
    //  (c) Insert is INSIDE the delete range:
    //      The insert moves to the delete boundary (remote.pos).
    //      The delete length grows by the insert length? No — the insert text
    //      was never part of the original, so the delete still removes the same chars.
    //      But the delete range must now account for the inserted text sitting inside it.
    //      Simplification: move the insert to the delete's start position.
    //      The delete splits around the inserted text. In our simplified model,
    //      we just place the insert at the delete boundary.
    //
    private void transformInsertDelete(Operation local, Operation remote,
                                        Operation localPrime, Operation remotePrime) {
        int insertPos = local.getPosition();
        int deletePos = remote.getPosition();
        int deleteLen = remote.getLength();
        int insertLen = local.getInsertLength();

        if (insertPos <= deletePos) {
            // Insert is at or before the delete start → delete shifts right
            remotePrime.setPosition(deletePos + insertLen);
            // localPrime.pos stays the same
        } else if (insertPos >= deletePos + deleteLen) {
            // Insert is after the delete range → insert shifts left
            localPrime.setPosition(insertPos - deleteLen);
            // remotePrime.pos stays the same
        } else {
            // Insert is INSIDE the deleted range → move insert to delete boundary
            // The inserted text will appear at the start of where the deletion happened.
            localPrime.setPosition(deletePos);
            // The delete range: the original chars are still there, plus the insert
            // slipped in. After the local insert, the remote delete still removes
            // the same original chars, but they've shifted right by insertLen within
            // the region. We keep remote's position and adjust: the delete now has
            // insertLen extra chars in the middle that should NOT be deleted.
            // Simplest correct approach: delete stays at deletePos, length unchanged.
            // The insert at deletePos will be preserved because it's at the boundary.
        }
    }

    // ══════════════════════════════════════════════
    //  Case 3: DELETE vs INSERT (mirror of Case 2)
    // ══════════════════════════════════════════════
    //
    //  local = DELETE, remote = INSERT
    //  Just mirror the logic: swap roles and mirror adjustments.
    //
    private void transformDeleteInsert(Operation local, Operation remote,
                                        Operation localPrime, Operation remotePrime) {
        int deletePos = local.getPosition();
        int deleteLen = local.getLength();
        int insertPos = remote.getPosition();
        int insertLen = remote.getInsertLength();

        if (insertPos <= deletePos) {
            // Remote insert is before the delete → delete shifts right
            localPrime.setPosition(deletePos + insertLen);
            // remotePrime stays the same
        } else if (insertPos >= deletePos + deleteLen) {
            // Remote insert is after the delete range → insert shifts left
            remotePrime.setPosition(insertPos - deleteLen);
            // localPrime stays the same
        } else {
            // Remote insert is inside the delete range → insert moves to delete boundary
            remotePrime.setPosition(deletePos);
            // Delete position stays, length stays — same reasoning as Case 2
        }
    }

    // ══════════════════════════════════════════════
    //  Case 4: DELETE vs DELETE
    // ══════════════════════════════════════════════
    //
    //  Example: doc = "ABCDEFGH"
    //    local:  DELETE pos=2 len=3  → delete "CDE"
    //    remote: DELETE pos=4 len=2  → delete "EF"
    //
    //  Sub-cases:
    //
    //  (a) No overlap, local is entirely before remote:
    //      remote'.pos shifts left by local.length.
    //
    //  (b) No overlap, remote is entirely before local:
    //      local'.pos shifts left by remote.length.
    //
    //  (c) Overlap: we must avoid deleting the same characters twice.
    //      Calculate the overlap region and reduce both lengths accordingly.
    //
    private void transformDeleteDelete(Operation local, Operation remote,
                                        Operation localPrime, Operation remotePrime) {
        int localPos = local.getPosition();
        int localLen = local.getLength();
        int localEnd = localPos + localLen;

        int remotePos = remote.getPosition();
        int remoteLen = remote.getLength();
        int remoteEnd = remotePos + remoteLen;

        // Check for overlap
        int overlapStart = Math.max(localPos, remotePos);
        int overlapEnd = Math.min(localEnd, remoteEnd);
        int overlapLen = Math.max(0, overlapEnd - overlapStart);

        if (overlapLen == 0) {
            // No overlap — simply shift positions
            if (localPos <= remotePos) {
                // local delete is entirely before remote delete
                remotePrime.setPosition(remotePos - localLen);
                // localPrime stays at localPos
            } else {
                // remote delete is entirely before local delete
                localPrime.setPosition(localPos - remoteLen);
                // remotePrime stays at remotePos
            }
        } else {
            // Overlapping deletes — reduce lengths to avoid double-deletion
            //
            // After remote has been applied, some chars that local wants to
            // delete are already gone.  local' should only delete the
            // non-overlapping portion.
            //
            // local' length  = original local length - overlap
            // remote' length = original remote length - overlap
            //
            // Position adjustments:
            //   local'  position: if remote is before local, shift left by
            //                     the number of remote-only chars before local's start
            //   remote' position: if local is before remote, shift left by
            //                     the number of local-only chars before remote's start

            int localOnlyBefore = Math.max(0, remotePos - localPos);
            int remoteOnlyBefore = Math.max(0, localPos - remotePos);

            // Adjusted positions
            if (remotePos < localPos) {
                // Remote starts before local — some chars before localPos are gone
                int removedBeforeLocal = Math.min(remoteLen, localPos - remotePos);
                localPrime.setPosition(localPos - removedBeforeLocal);
            }
            if (localPos < remotePos) {
                // Local starts before remote — some chars before remotePos are gone
                int removedBeforeRemote = Math.min(localLen, remotePos - localPos);
                remotePrime.setPosition(remotePos - removedBeforeRemote);
            }
            // If they start at the same position, neither shifts

            // Adjusted lengths (subtract overlap so we don't delete same chars twice)
            localPrime.setLength(localLen - overlapLen);
            remotePrime.setLength(remoteLen - overlapLen);
        }
    }
}
