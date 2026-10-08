package org.remus.giteabot.review.state;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface ReviewRevisionRepository extends JpaRepository<ReviewRevisionRecord, Long> {

    Optional<ReviewRevisionRecord> findByBotIdAndRepoOwnerAndRepoNameAndPrNumberAndWorkflowKey(
            Long botId, String repoOwner, String repoName, Long prNumber, String workflowKey);

    List<ReviewRevisionRecord> findByBotIdAndRepoOwnerAndRepoNameAndPrNumber(
            Long botId, String repoOwner, String repoName, Long prNumber);

    List<ReviewRevisionRecord> findByPrClosedAtBefore(Instant cutoff);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM ReviewRevisionRecord r WHERE r.botId = :botId AND r.repoOwner = :owner "
            + "AND r.repoName = :repo AND r.prNumber = :pr")
    List<ReviewRevisionRecord> lockForPullRequest(@Param("botId") Long botId, @Param("owner") String owner,
                                                 @Param("repo") String repo, @Param("pr") Long prNumber);

    @Modifying
    @Transactional
    @Query("UPDATE ReviewRevisionRecord r SET r.prClosedAt = :closedAt WHERE r.botId = :botId "
            + "AND r.repoOwner = :owner AND r.repoName = :repo AND r.prNumber = :pr")
    int stampPrClosed(@Param("botId") Long botId, @Param("owner") String owner,
                      @Param("repo") String repo, @Param("pr") Long prNumber,
                      @Param("closedAt") Instant closedAt);

    @Modifying
    @Transactional
    @Query("UPDATE ReviewRevisionRecord r SET r.prClosedAt = NULL WHERE r.botId = :botId "
            + "AND r.repoOwner = :owner AND r.repoName = :repo AND r.prNumber = :pr")
    int clearPrClosed(@Param("botId") Long botId, @Param("owner") String owner,
                      @Param("repo") String repo, @Param("pr") Long prNumber);

    @Modifying
    @Transactional
    @Query("DELETE FROM ReviewRevisionRecord r WHERE r.botId = :botId AND r.repoOwner = :owner "
            + "AND r.repoName = :repo AND r.prNumber = :pr")
    int deleteForPullRequest(@Param("botId") Long botId, @Param("owner") String owner,
                             @Param("repo") String repo, @Param("pr") Long prNumber);
}
