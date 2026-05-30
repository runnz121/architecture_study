package study.architecture.chapter4.messagestore

data class MessageWrittenEvent(
    val streamName: String,
    val type: String,
    val position: Long,
    val globalPosition: Long,
    val data: String,
    val metadata: String
)
