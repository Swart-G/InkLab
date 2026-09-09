package dev.swart.inklab.core.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CloudContractsTest {
    private fun rev(id: String, parents: Set<String> = emptySet(), device: String = "a") = CloudRevision(
        revisionId = id,
        documentId = "doc",
        packageSha256 = "a".repeat(64),
        packageSize = 10,
        parents = parents,
        snapshotSequence = 1,
        createdAt = 1,
        deviceId = device
    )

    @Test fun causalOrderDoesNotDependOnClock() {
        val a = rev("a")
        val b = rev("b", setOf("a")).copy(createdAt = 1)
        assertEquals(RevisionRelation.REMOTE_AHEAD, CloudCausality.relation("a", "b", listOf(a, b)))
        assertEquals(RevisionRelation.LOCAL_AHEAD, CloudCausality.relation("b", "a", listOf(a, b)))
    }

    @Test fun twoOfflineBranchesRemainDiverged() {
        val base = rev("base")
        val left = rev("left", setOf("base"), "phone")
        val right = rev("right", setOf("base"), "tablet")
        assertEquals(RevisionRelation.DIVERGED, CloudCausality.relation("left", "right", listOf(base, left, right)))
        assertEquals(setOf("left", "right"), CloudCausality.heads(listOf(base, left, right), "doc"))
    }

    @Test fun duplicateRevisionIdWithDifferentContentIsRejected() {
        val first = rev("same")
        val second = first.copy(packageSha256 = "b".repeat(64))
        assertThrows(IllegalArgumentException::class.java) { CloudCausality.validate(listOf(first, second)) }
    }

    @Test fun operationIdsAreStableButAccountScoped() {
        val a = CloudOperation.stableId("account-a", CloudOperationKind.UPLOAD_REVISION, "doc", 42)
        val again = CloudOperation.stableId("account-a", CloudOperationKind.UPLOAD_REVISION, "doc", 42)
        val b = CloudOperation.stableId("account-b", CloudOperationKind.UPLOAD_REVISION, "doc", 42)
        assertEquals(a, again)
        assertNotEquals(a, b)
    }
}
