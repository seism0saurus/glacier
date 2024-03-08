package de.seism0saurus.glacier.webservice.storage;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface SubscriptionRepository extends MongoRepository<Subscription, String> {

    void deleteByWallId(String wallId);

    Optional<Subscription> findByDestination(String destination);
    Optional<Subscription> findByWallIdAndHashtag(String wallId, String hashtag);

    void deleteBySessionIdAndSubId(String sessionId, String subId);
}