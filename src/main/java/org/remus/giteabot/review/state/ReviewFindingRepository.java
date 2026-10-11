package org.remus.giteabot.review.state;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface ReviewFindingRepository extends JpaRepository<ReviewFindingRecord, UUID> {

    List<ReviewFindingRecord> findByBotIdAndRepoOwnerAndRepoNameAndPrNumberAndWorkflowKeyAndStatusInOrderByCreatedAtAsc(
            Long botId, String repoOwner, String repoName, Long prNumber, String workflowKey,
            Collection<ReviewFindingStatus> statuses);

    @Modifying
    @Transactional
    @Query("DELETE FROM ReviewFindingRecord f WHERE f.botId = :botId AND f.repoOwner = :owner "
            + "AND f.repoName = :repo AND f.prNumber = :pr")
    int deleteForPullRequest(@Param("botId") Long botId, @Param("owner") String owner,
                             @Param("repo") String repo, @Param("pr") Long prNumber);
}
