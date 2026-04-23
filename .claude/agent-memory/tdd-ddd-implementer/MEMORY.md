# Agent Memory Index — tdd-ddd-implementer

- [Glacier domain architecture](project_glacier_domain.md) — MessageCache/SubscriptionManager/SubscriptionListener relationships, killswitch gating, ADR-05 eviction contract
- [Test conventions](feedback_test_conventions.md) — *Test.java=Surefire, *IT.java=Failsafe, 6-arg MessageCacheImpl constructor, peer lane test file risks
- [LogScrubber canonical hash](project_log_scrubber.md) — hash8 is the one canonical SHA-256-first-8-hex; null→"null", blank→"blank"; pin: hash8("my-wall-id")="70c8ebbb"
