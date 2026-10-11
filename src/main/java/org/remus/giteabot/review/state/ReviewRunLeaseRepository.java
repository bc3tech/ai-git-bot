package org.remus.giteabot.review.state;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Repository
public interface ReviewRunLeaseRepository extends JpaRepository<ReviewRunLease, Long> {

    boolean existsByBotIdAndRepoOwnerAndRepoNameAndPrNumber(
            Long botId, String repoOwner, String repoName, Long prNumber);

    Optional<ReviewRunLease> findByBotIdAndRepoOwnerAndRepoNameAndPrNumber(
            Long botId, String repoOwner, String repoName, Long prNumber);

    boolean existsByBotIdAndRepoOwnerAndRepoNameAndPrNumberAndLeaseOwnerAndLeaseTokenAndLeaseExpiresAtAfter(
            Long botId, String repoOwner, String repoName, Long prNumber, String leaseOwner,
            long leaseToken, Instant now);

    @Modifying
    @Transactional
    @Query("UPDATE ReviewRunLease l SET l.leaseOwner = :leaseOwner, l.leaseToken = l.leaseToken + 1, "
            + "l.leaseExpiresAt = :expiresAt WHERE l.botId = :botId AND l.repoOwner = :owner "
            + "AND l.repoName = :repo AND l.prNumber = :pr AND "
            + "(l.leaseOwner IS NULL OR l.leaseExpiresAt IS NULL OR l.leaseExpiresAt <= :now)")
    int acquireIfExpired(@Param("botId") Long botId, @Param("owner") String owner,
                         @Param("repo") String repo, @Param("pr") Long prNumber,
                         @Param("leaseOwner") String leaseOwner, @Param("now") Instant now,
                         @Param("expiresAt") Instant expiresAt);

    @Modifying
    @Transactional
    @Query("UPDATE ReviewRunLease l SET l.leaseExpiresAt = :expiresAt WHERE l.botId = :botId "
            + "AND l.repoOwner = :owner AND l.repoName = :repo AND l.prNumber = :pr "
            + "AND l.leaseOwner = :leaseOwner AND l.leaseToken = :leaseToken AND l.leaseExpiresAt > :now")
    int renewIfHeld(@Param("botId") Long botId, @Param("owner") String owner,
                    @Param("repo") String repo, @Param("pr") Long prNumber,
                    @Param("leaseOwner") String leaseOwner, @Param("leaseToken") long leaseToken,
                    @Param("now") Instant now, @Param("expiresAt") Instant expiresAt);

    @Modifying
    @Transactional
    @Query("UPDATE ReviewRunLease l SET l.leaseOwner = NULL, l.leaseExpiresAt = NULL "
            + "WHERE l.botId = :botId AND l.repoOwner = :owner AND l.repoName = :repo "
            + "AND l.prNumber = :pr AND l.leaseOwner = :leaseOwner AND l.leaseToken = :leaseToken")
    int releaseIfHeld(@Param("botId") Long botId, @Param("owner") String owner,
                      @Param("repo") String repo, @Param("pr") Long prNumber,
                      @Param("leaseOwner") String leaseOwner, @Param("leaseToken") long leaseToken);
}
