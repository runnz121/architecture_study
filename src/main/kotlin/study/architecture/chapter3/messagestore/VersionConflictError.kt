package study.architecture.chapter3.messagestore

class VersionConflictError(
    val streamName: String,
    val actualVersion: Long?,
    val expectedVersion: Long
) : RuntimeException(
    "Wrong expected version: $expectedVersion " +
        "(Stream: $streamName, Actual version: $actualVersion)"
)
