package ai.rightone.finderplus.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import ai.rightone.finderplus.db.entity.FaceEntity
import ai.rightone.finderplus.db.entity.PersonEntity

@Dao
interface FaceDao {

    /** Idempotent per (item, box): re-running detection on an item converges instead of duplicating. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFaces(faces: List<FaceEntity>)

    @Query("DELETE FROM face WHERE item_id = :itemId")
    suspend fun clearFaces(itemId: Long)

    @Query("SELECT COUNT(*) FROM face WHERE item_id = :itemId")
    suspend fun faceCount(itemId: Long): Int

    @Query("SELECT COUNT(*) FROM face")
    suspend fun totalFaces(): Int

    /** Faces awaiting an embedding — the backfill queue once a face model is installed. */
    @Query("SELECT * FROM face WHERE item_id = :itemId")
    suspend fun facesForItem(itemId: Long): List<FaceEntity>

    /** Every face that has an embedding — the input to identity clustering. */
    @Query("SELECT * FROM face WHERE embedding IS NOT NULL")
    suspend fun facesWithEmbedding(): List<FaceEntity>

    @Query("SELECT COUNT(*) FROM face WHERE embedding IS NOT NULL")
    suspend fun facesEmbeddedCount(): Int

    @Query("SELECT * FROM face WHERE embedding IS NULL LIMIT :limit")
    suspend fun facesMissingEmbedding(limit: Int): List<FaceEntity>

    @Query("UPDATE face SET embedding = :embedding WHERE id = :id")
    suspend fun setEmbedding(id: Long, embedding: ByteArray)

    @Query("UPDATE face SET cluster_id = :clusterId WHERE id = :id")
    suspend fun setCluster(id: Long, clusterId: Long)

    /** Undo of a person answer: detach every face that was assigned to [personId]. */
    @androidx.room.Query("UPDATE face SET cluster_id = NULL WHERE cluster_id = :personId")
    suspend fun clearClusterAssignments(personId: Long)

    @androidx.room.Query("DELETE FROM person WHERE id = :personId")
    suspend fun deletePerson(personId: Long)

    // ---- people (face clusters) ----

    @Insert
    suspend fun insertPerson(person: PersonEntity): Long

    @Query("SELECT * FROM person")
    suspend fun allPeople(): List<PersonEntity>

    @Query("UPDATE person SET centroid = :centroid, face_count = :count, updated_at = :now WHERE id = :id")
    suspend fun updateCentroid(id: Long, centroid: ByteArray, count: Int, now: Long)

    /** Naming a cluster is what makes those photos findable by name. */
    @Query("UPDATE person SET name = :name, updated_at = :now WHERE id = :id")
    suspend fun setName(id: Long, name: String?, now: Long)

    /** Names of everyone appearing in an item — folded into its FTS row and AI-revision profile. */
    @Query(
        """
        SELECT group_concat(DISTINCT p.name) FROM face f
        JOIN person p ON p.id = f.cluster_id
        WHERE f.item_id = :itemId AND p.name IS NOT NULL
        """
    )
    suspend fun personNamesFor(itemId: Long): String?

    /** Clusters appearing in this item. A proposal is only applied when exactly one person is present,
     *  so a name can never be attached to the wrong face in a group shot. */
    @Query("SELECT DISTINCT cluster_id FROM face WHERE item_id = :itemId AND cluster_id IS NOT NULL")
    suspend fun clusterIdsFor(itemId: Long): List<Long>

    @Query("SELECT COUNT(*) FROM person WHERE name IS NOT NULL")
    suspend fun namedPeopleCount(): Int

    /**
     * Record (or reinforce) a VLM-suggested name for a cluster. Votes accumulate only while the
     * suggestion agrees with itself; a different suggestion resets the count, so a one-off
     * hallucination cannot out-vote consistent agreement.
     */
    @Query(
        """
        UPDATE person
        SET proposal_votes = CASE WHEN proposed_name = :name THEN proposal_votes + 1 ELSE 1 END,
            proposed_name = :name,
            updated_at = :now
        WHERE id = :clusterId AND name IS NULL
        """
    )
    suspend fun proposeName(clusterId: Long, name: String, now: Long)

    /** Suggestions worth surfacing to the user, strongest agreement first. */
    @Query(
        "SELECT id, proposed_name AS name, proposal_votes AS votes FROM person " +
            "WHERE name IS NULL AND proposed_name IS NOT NULL AND proposal_votes >= :minVotes " +
            "ORDER BY proposal_votes DESC"
    )
    suspend fun pendingProposals(minVotes: Int): List<ProposalRow>

    /** User accepted a suggestion: it becomes the cluster's real name and propagates to every photo. */
    @Query("UPDATE person SET name = proposed_name, proposed_name = NULL, updated_at = :now WHERE id = :clusterId")
    suspend fun confirmProposal(clusterId: Long, now: Long)

    @Query("UPDATE person SET proposed_name = NULL, proposal_votes = 0, updated_at = :now WHERE id = :clusterId")
    suspend fun rejectProposal(clusterId: Long, now: Long)
}

/** A pending VLM name suggestion for a face cluster. */
data class ProposalRow(val id: Long, val name: String, val votes: Int)
